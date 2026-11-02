package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceDesktopControllerTest {
    @Test
    fun `setup build and restart activate only the workspace catalog`() =
        withTempDirectory { directory ->
            val settings = directory.resolve("settings/settings.json")
            val default = directory.resolve("default")
            val controller = WorkspaceDesktopController.forTest(settings, default)
            val setup = assertIs<WorkspaceDesktopState.Setup>(controller.state)
            val root = directory.resolve("selected-workspace")

            assertEquals(default, setup.defaultPath)
            controller.updateSelectedPath(root.toString())
            assertTrue(controller.initializeSelectedPath())
            val active = assertIs<WorkspaceDesktopState.Active>(controller.state)
            assertNull(controller.catalogContext)

            val source = active.paths.input.resolve("brand/car.json")
            Files.createDirectories(source.parent)
            Files.copy(DataModelFixtures.pricelistRoot().resolve(DataModelFixtures.CUPRA_RAVAL), source)
            Files.write(source.resolveSibling("car.pdf"), "%PDF-1.7 fixture".toByteArray())

            runBlocking {
                controller.refreshSources()
                controller.buildCatalog(replaceExistingConfirmed = false)
            }

            assertNotNull(controller.catalogContext)
            assertEquals(1, controller.catalogGeneration)
            assertIs<WorkspaceDesktopState.Active>(controller.state).let {
                assertNull(it.inventoryError)
                assertEquals(1, it.buildReport?.vehicleCount)
            }

            val restarted = WorkspaceDesktopController.forTest(settings, default)
            assertIs<WorkspaceDesktopState.Active>(restarted.state)
            assertNotNull(restarted.catalogContext)
        }

    @Test
    fun `startup recovery retries the saved workspace without fallback`() =
        withTempDirectory { directory ->
            val settings = directory.resolve("settings/settings.json")
            val root = directory.resolve("workspace")
            val initial = WorkspaceDesktopController.forTest(settings, directory.resolve("default"))
            initial.updateSelectedPath(root.toString())
            assertTrue(initial.initializeSelectedPath())

            val paths = WorkspacePaths.at(root)
            Files.delete(paths.input)
            val recoveryController = WorkspaceDesktopController.forTest(settings, directory.resolve("default"))
            val recovery = assertIs<WorkspaceDesktopState.Recovery>(recoveryController.state)
            assertEquals(root, recovery.savedPath)
            assertEquals(root.toString(), recovery.selectedPath)

            Files.createDirectories(paths.input)
            assertTrue(recoveryController.retryRecovery())
            assertEquals(paths, assertIs<WorkspaceDesktopState.Active>(recoveryController.state).paths)
        }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("workspace-desktop-controller-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
