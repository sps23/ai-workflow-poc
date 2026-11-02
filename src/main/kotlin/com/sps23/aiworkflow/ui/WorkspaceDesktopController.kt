package com.sps23.aiworkflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sps23.aiworkflow.catalog.datamodel.WorkspaceCatalogBuildOutcome
import com.sps23.aiworkflow.catalog.datamodel.WorkspaceCatalogBuildService
import com.sps23.aiworkflow.catalog.datamodel.WorkspaceCatalogInventory
import com.sps23.aiworkflow.logging.WorkspaceLogs
import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import com.sps23.aiworkflow.workspace.WorkspaceManager
import com.sps23.aiworkflow.workspace.WorkspacePaths
import com.sps23.aiworkflow.workspace.WorkspaceSettingsLocation
import com.sps23.aiworkflow.workspace.WorkspaceSettingsStore
import com.sps23.aiworkflow.workspace.WorkspaceStartup
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

sealed interface WorkspaceDesktopState {
    data class Setup(
        val defaultPath: Path,
        val selectedPath: String,
        val error: String? = null,
    ) : WorkspaceDesktopState

    data class Recovery(
        val savedPath: Path?,
        val reason: String,
        val selectedPath: String,
    ) : WorkspaceDesktopState

    data class Active(
        val paths: WorkspacePaths,
        val catalogError: String? = null,
        val sourcesChanged: Boolean = false,
        val buildReport: WorkspaceCatalogBuildOutcome.Built? = null,
        val inventory: WorkspaceCatalogInventory? = null,
        val inventoryError: String? = null,
        val building: Boolean = false,
    ) : WorkspaceDesktopState

    data class Unavailable(
        val reason: String,
    ) : WorkspaceDesktopState
}

class WorkspaceDesktopController private constructor(
    private val manager: WorkspaceManager?,
    private val startupFailure: String? = null,
) {
    private var catalog: DataModelCatalogContext? = null
    var state: WorkspaceDesktopState by mutableStateOf(initialState())
        private set
    var catalogGeneration by mutableStateOf(0)
        private set

    init {
        if (state is WorkspaceDesktopState.Active) reloadCatalog()
    }

    val paths: WorkspacePaths? get() = (state as? WorkspaceDesktopState.Active)?.paths
    val catalogContext: DataModelCatalogContext?
        get() {
            val active = state as? WorkspaceDesktopState.Active ?: return null
            if (active.catalogError != null) return null
            return catalog
        }

    private fun initialState(): WorkspaceDesktopState {
        startupFailure?.let { return WorkspaceDesktopState.Unavailable(it) }
        return when (val startup = requireNotNull(manager).startup()) {
            is WorkspaceStartup.SetupRequired -> {
                WorkspaceDesktopState.Setup(startup.defaultWorkspace, startup.defaultWorkspace.toString())
            }

            is WorkspaceStartup.Ready -> {
                active(startup.paths)
            }

            is WorkspaceStartup.RecoveryRequired -> {
                WorkspaceDesktopState.Recovery(startup.savedWorkspace, startup.reason, startup.savedWorkspace.toString())
            }

            is WorkspaceStartup.SettingsUnavailable -> {
                WorkspaceDesktopState.Unavailable(startup.reason)
            }
        }
    }

    fun updateSelectedPath(value: String) {
        when (val current = state) {
            is WorkspaceDesktopState.Setup -> state = current.copy(selectedPath = value, error = null)
            is WorkspaceDesktopState.Recovery -> state = current.copy(selectedPath = value)
            else -> Unit
        }
    }

    fun initializeSelectedPath(): Boolean {
        val current = state
        val selected =
            when (current) {
                is WorkspaceDesktopState.Setup -> current.selectedPath
                is WorkspaceDesktopState.Recovery -> current.selectedPath
                else -> return false
            }
        return try {
            val path = Path.of(selected.trim())
            val workspace = requireNotNull(manager).initialize(path)
            state = active(workspace)
            reloadCatalog()
            true
        } catch (exception: Exception) {
            state =
                when (current) {
                    is WorkspaceDesktopState.Setup -> {
                        current.copy(error = exception.message ?: "Cannot initialize workspace.")
                    }

                    is WorkspaceDesktopState.Recovery -> {
                        current.copy(reason = exception.message ?: "Cannot initialize workspace.")
                    }
                }
            false
        }
    }

    fun retryRecovery(): Boolean {
        val current = state as? WorkspaceDesktopState.Recovery ?: return false
        val startup = requireNotNull(manager).startup()
        return when (startup) {
            is WorkspaceStartup.Ready -> {
                state = active(startup.paths)
                reloadCatalog()
                true
            }

            is WorkspaceStartup.RecoveryRequired -> {
                state = current.copy(reason = startup.reason)
                false
            }

            is WorkspaceStartup.SettingsUnavailable -> {
                state = WorkspaceDesktopState.Unavailable(startup.reason)
                false
            }

            is WorkspaceStartup.SetupRequired -> {
                false
            }
        }
    }

    suspend fun refreshSources() {
        val current = state as? WorkspaceDesktopState.Active ?: return
        state = current.copy(inventoryError = null)
        try {
            val inventory = WorkspaceCatalogBuildService(current.paths).refresh()
            WorkspaceLogs(
                current.paths,
            ).info("Source inventory refreshed: ${inventory.includedCount} included, ${inventory.excludedCount} excluded.")
            val latest = state as? WorkspaceDesktopState.Active ?: return
            state = latest.copy(inventory = inventory, inventoryError = null)
        } catch (exception: IOException) {
            val loggingFailure =
                try {
                    WorkspaceLogs(current.paths).error("Cannot refresh source inventory: ${exception.message}")
                    null
                } catch (failure: IOException) {
                    failure.message
                }
            val latest = state as? WorkspaceDesktopState.Active ?: return
            state =
                latest.copy(
                    inventory = null,
                    inventoryError =
                        (exception.message ?: "Cannot scan workspace input.") +
                            (loggingFailure?.let { "\nLogging also failed: $it" } ?: ""),
                )
        }
    }

    suspend fun buildCatalog(replaceExistingConfirmed: Boolean) {
        val current = state as? WorkspaceDesktopState.Active ?: return
        val inventory = current.inventory ?: return
        state = current.copy(building = true, inventoryError = null)
        val result =
            try {
                WorkspaceCatalogBuildService(current.paths).build(inventory, replaceExistingConfirmed)
            } catch (exception: CancellationException) {
                state = current.copy(building = false)
                throw exception
            } catch (exception: Exception) {
                WorkspaceCatalogBuildOutcome.Failed(
                    listOf(exception.message ?: "Catalog build failed."),
                    current.paths.data.resolve("data-model-catalog.json"),
                )
            }
        when (result) {
            is WorkspaceCatalogBuildOutcome.Built -> {
                catalog = result.context
                catalogGeneration++
                state = current.copy(building = false, catalogError = null, sourcesChanged = false, buildReport = result)
            }

            WorkspaceCatalogBuildOutcome.Declined -> {
                state = current.copy(building = false)
            }

            is WorkspaceCatalogBuildOutcome.Failed -> {
                state = current.copy(building = false, inventoryError = result.errors.joinToString("\n"))
            }
        }
    }

    fun markSourcesChanged() {
        val current = state as? WorkspaceDesktopState.Active ?: return
        state = current.copy(sourcesChanged = true)
    }

    fun reloadCatalog(): Boolean {
        val current = state as? WorkspaceDesktopState.Active ?: return false
        val catalogPath = current.paths.data.resolve("data-model-catalog.json")
        if (!Files.exists(catalogPath)) {
            WorkspaceLogs(current.paths).info("No catalog at $catalogPath; build a catalog before using Assistant.")
            catalog = null
            state = current.copy(catalogError = null)
            return false
        }
        return try {
            catalog = DataModelCatalogContext.loadWorkspace(current.paths)
            WorkspaceLogs(current.paths).info("Catalog loaded: $catalogPath")
            state = current.copy(catalogError = null)
            true
        } catch (exception: IOException) {
            try {
                WorkspaceLogs(current.paths).error("Cannot load catalog: ${exception.message}")
            } catch (loggingFailure: IOException) {
                catalog = null
                state = current.copy(catalogError = "${exception.message}; logging also failed: ${loggingFailure.message}")
                return false
            }
            catalog = null
            state = current.copy(catalogError = exception.message ?: "Workspace catalog is invalid.")
            false
        }
    }

    private fun active(paths: WorkspacePaths): WorkspaceDesktopState.Active {
        catalog = null
        return WorkspaceDesktopState.Active(paths)
    }

    companion object {
        fun forCurrentUser(
            osName: String = System.getProperty("os.name"),
            userHome: String? = System.getProperty("user.home"),
            environment: Map<String, String> = System.getenv(),
        ): WorkspaceDesktopController =
            try {
                val settingsPath = WorkspaceSettingsLocation.resolve(osName, userHome, environment)
                val defaultWorkspace =
                    Path.of(requireNotNull(userHome), "CarCatalogAssistant").toAbsolutePath().normalize()
                WorkspaceDesktopController(
                    WorkspaceManager(WorkspaceSettingsStore(settingsPath), defaultWorkspace),
                )
            } catch (exception: IOException) {
                WorkspaceDesktopController(null, exception.message ?: "Workspace settings are unavailable.")
            } catch (exception: InvalidPathException) {
                WorkspaceDesktopController(null, exception.message ?: "Workspace settings path is invalid.")
            }

        fun forTest(
            settingsFile: Path,
            defaultWorkspace: Path,
        ): WorkspaceDesktopController = WorkspaceDesktopController(WorkspaceManager(WorkspaceSettingsStore(settingsFile), defaultWorkspace))
    }
}
