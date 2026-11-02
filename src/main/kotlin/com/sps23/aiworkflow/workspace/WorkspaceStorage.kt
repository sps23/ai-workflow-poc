package com.sps23.aiworkflow.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val WORKSPACE_SETTINGS_SCHEMA_VERSION = 1
private const val WORKSPACE_LOCK_FILE = ".car-catalog-assistant.lock"

@Serializable
data class WorkspaceSettings(
    val schemaVersion: Int,
    val workspacePath: String,
)

data class WorkspacePaths(
    val root: Path,
    val input: Path,
    val data: Path,
) {
    val logs: Path get() = root.resolve("logs")

    init {
        require(root == root.toAbsolutePath().normalize()) { "Workspace root must be normalized and absolute." }
        require(input == root.resolve("input")) { "Workspace input directory must be under the workspace root." }
        require(data == root.resolve("data")) { "Workspace data directory must be under the workspace root." }
    }

    companion object {
        fun at(root: Path): WorkspacePaths {
            val normalizedRoot = root.toAbsolutePath().normalize()
            return WorkspacePaths(
                root = normalizedRoot,
                input = normalizedRoot.resolve("input"),
                data = normalizedRoot.resolve("data"),
            )
        }
    }
}

sealed interface WorkspaceStartup {
    data class SetupRequired(
        val defaultWorkspace: Path,
    ) : WorkspaceStartup

    data class Ready(
        val paths: WorkspacePaths,
    ) : WorkspaceStartup

    data class RecoveryRequired(
        val savedWorkspace: Path,
        val reason: String,
    ) : WorkspaceStartup

    data class SettingsUnavailable(
        val reason: String,
    ) : WorkspaceStartup
}

class WorkspaceBusyException(
    message: String,
) : IOException(message)

class WorkspaceSettingsStore(
    settingsFile: Path,
) {
    private val settingsFile = settingsFile.toAbsolutePath().normalize()
    private val json =
        Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            encodeDefaults = true
        }

    fun load(): WorkspaceSettings? {
        val content =
            try {
                Files.readString(settingsFile)
            } catch (_: NoSuchFileException) {
                return null
            } catch (exception: IOException) {
                throw IOException("Cannot read workspace settings at $settingsFile: ${exception.message}", exception)
            }
        val settings =
            try {
                json.decodeFromString<WorkspaceSettings>(content)
            } catch (exception: SerializationException) {
                throw IOException("Workspace settings at $settingsFile are invalid: ${exception.message}", exception)
            }
        if (settings.schemaVersion != WORKSPACE_SETTINGS_SCHEMA_VERSION) {
            throw IOException("Unsupported workspace settings schema version: ${settings.schemaVersion}.")
        }
        val savedPath =
            try {
                Path.of(settings.workspacePath)
            } catch (exception: InvalidPathException) {
                throw IOException("Workspace settings contain an invalid path.", exception)
            }
        if (!savedPath.isAbsolute) throw IOException("Workspace settings must contain an absolute path.")
        return settings.copy(workspacePath = savedPath.normalize().toString())
    }

    fun save(workspace: Path) {
        val normalized = workspace.toAbsolutePath().normalize()
        val settings =
            WorkspaceSettings(
                schemaVersion = WORKSPACE_SETTINGS_SCHEMA_VERSION,
                workspacePath = normalized.toString(),
            )
        val parent =
            settingsFile.toAbsolutePath().normalize().parent
                ?: throw IOException("Cannot determine the settings directory for $settingsFile.")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".settings-", ".tmp")
        try {
            Files.writeString(
                temporary,
                json.encodeToString(settings),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    settingsFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

object WorkspaceSettingsLocation {
    fun resolve(
        osName: String,
        userHome: String?,
        environment: Map<String, String>,
    ): Path {
        val home =
            userHome
                ?.takeIf(String::isNotBlank)
                ?.let(::pathOf)
                ?: throw IOException("Cannot resolve the user's home directory.")
        if (!home.isAbsolute) throw IOException("The user's home directory must be an absolute path.")
        return when {
            osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> {
                home.resolve("Library/Application Support/CarCatalogAssistant/settings.json")
            }

            osName.contains("win", ignoreCase = true) -> {
                val appData =
                    environment["APPDATA"]
                        ?.takeIf(String::isNotBlank)
                        ?.let(::pathOf)
                        ?: throw IOException("Cannot resolve the Windows APPDATA directory.")
                if (!appData.isAbsolute) throw IOException("APPDATA must be an absolute path.")
                appData.resolve("CarCatalogAssistant/settings.json")
            }

            else -> {
                val configHome =
                    environment["XDG_CONFIG_HOME"]
                        ?.takeIf(String::isNotBlank)
                        ?.let(::pathOf)
                        ?: home.resolve(".config")
                if (!configHome.isAbsolute) throw IOException("XDG_CONFIG_HOME must be an absolute path.")
                configHome.resolve("CarCatalogAssistant/settings.json")
            }
        }
    }

    private fun pathOf(value: String): Path =
        try {
            Path.of(value)
        } catch (exception: InvalidPathException) {
            throw IOException("Invalid settings directory path.", exception)
        }
}

class WorkspaceManager(
    private val settings: WorkspaceSettingsStore,
    private val defaultWorkspace: Path,
    private val mutationGuard: WorkspaceMutationGuard = WorkspaceMutationGuard(),
) {
    fun startup(): WorkspaceStartup {
        val saved =
            try {
                settings.load()
            } catch (exception: IOException) {
                return WorkspaceStartup.SettingsUnavailable(exception.message ?: "Workspace settings are unavailable.")
            } ?: return WorkspaceStartup.SetupRequired(defaultWorkspace.toAbsolutePath().normalize())

        val root =
            try {
                Path.of(saved.workspacePath)
            } catch (exception: InvalidPathException) {
                return WorkspaceStartup.SettingsUnavailable("Workspace settings contain an invalid path.")
            }
        val paths = WorkspacePaths.at(root)
        val missingOrInvalid =
            listOf(paths.root, paths.input, paths.data).firstOrNull { !Files.isDirectory(it) }
        if (missingOrInvalid != null) {
            return WorkspaceStartup.RecoveryRequired(
                paths.root,
                "Workspace directory is missing or is not a directory: $missingOrInvalid",
            )
        }
        return try {
            com.sps23.aiworkflow.logging
                .WorkspaceLogs(paths)
                .info("Workspace opened: ${paths.root}")
            paths.allDirectories().forEach(::verifyReadableAndWritable)
            WorkspaceStartup.Ready(paths)
        } catch (exception: IOException) {
            WorkspaceStartup.RecoveryRequired(
                paths.root,
                "Workspace is not accessible at $paths: ${exception.message}",
            )
        }
    }

    fun initialize(workspace: Path): WorkspacePaths {
        val paths = WorkspacePaths.at(workspace)
        try {
            Files.createDirectories(paths.root)
            if (!Files.isDirectory(paths.root)) throw IOException("Workspace root is not a directory: ${paths.root}")
            mutationGuard.withMutationLock(paths) {
                Files.createDirectories(paths.input)
                Files.createDirectories(paths.data)
                com.sps23.aiworkflow.logging
                    .WorkspaceLogs(paths)
                    .info("Workspace initialized: ${paths.root}")
                paths.allDirectories().forEach(::verifyReadableAndWritable)
                settings.save(paths.root)
            }
            return paths
        } catch (exception: WorkspaceBusyException) {
            throw exception
        } catch (exception: IOException) {
            throw IOException("Cannot initialize workspace at ${paths.root}: ${exception.message}", exception)
        }
    }

    private fun verifyReadableAndWritable(directory: Path) {
        val probe = Files.createTempFile(directory, ".workspace-access-", ".tmp")
        try {
            Files.writeString(
                probe,
                "workspace-access-check",
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            if (Files.readString(probe) != "workspace-access-check") {
                throw IOException("Workspace access check failed in $directory.")
            }
        } finally {
            Files.deleteIfExists(probe)
        }
    }
}

class WorkspaceMutationGuard {
    fun <T> withMutationLock(
        paths: WorkspacePaths,
        action: () -> T,
    ): T {
        val channel = openLockChannel(paths)
        channel.use {
            val lock = acquire(paths, channel)
            lock.use { return action() }
        }
    }

    suspend fun <T> withMutationLockSuspending(
        paths: WorkspacePaths,
        action: suspend () -> T,
    ): T {
        val channel = openLockChannel(paths)
        try {
            val lock = acquire(paths, channel)
            try {
                return action()
            } finally {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }

    private fun openLockChannel(paths: WorkspacePaths): FileChannel {
        if (!Files.isDirectory(paths.root)) throw IOException("Workspace root is unavailable: ${paths.root}")
        val lockPath = paths.root.resolve(WORKSPACE_LOCK_FILE)
        if (Files.isSymbolicLink(lockPath)) throw IOException("Workspace lock file must not be a symbolic link: $lockPath")
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    }

    private fun acquire(
        paths: WorkspacePaths,
        channel: FileChannel,
    ) = try {
        channel.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    } ?: throw WorkspaceBusyException("Workspace is being changed by another operation: ${paths.root}")
}

private fun WorkspacePaths.allDirectories(): List<Path> = listOf(root, input, data, logs)
