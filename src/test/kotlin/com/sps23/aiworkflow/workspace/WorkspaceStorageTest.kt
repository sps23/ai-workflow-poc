package com.sps23.aiworkflow.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceStorageTest {
    @Test
    fun `settings persist a normalized absolute workspace path`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("config/settings.json")
            val store = WorkspaceSettingsStore(settingsFile)
            val workspace = directory.resolve("work/../CarCatalogAssistant")

            store.save(workspace)

            assertEquals(directory.resolve("CarCatalogAssistant").toString(), store.load()?.workspacePath)
            assertTrue(Files.exists(settingsFile))
        }

    @Test
    fun `settings reject unsupported schema versions and unknown fields`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val store = WorkspaceSettingsStore(settingsFile)

            Files.writeString(settingsFile, """{"schemaVersion":2,"workspacePath":"/tmp/workspace"}""")
            assertFailsWith<IOException> { store.load() }

            Files.writeString(
                settingsFile,
                """{"schemaVersion":1,"workspacePath":"/tmp/workspace","apiKey":"secret"}""",
            )
            assertFailsWith<IOException> { store.load() }
        }

    @Test
    fun `settings location follows platform-specific directories`() {
        val home = Path.of("/home/test")

        assertEquals(
            Path.of("/home/test/Library/Application Support/CarCatalogAssistant/settings.json"),
            WorkspaceSettingsLocation.resolve("Mac OS X", home.toString(), emptyMap()),
        )
        assertEquals(
            Path.of("/appdata/CarCatalogAssistant/settings.json"),
            WorkspaceSettingsLocation.resolve("Windows 11", home.toString(), mapOf("APPDATA" to "/appdata")),
        )
        assertEquals(
            Path.of("/xdg/CarCatalogAssistant/settings.json"),
            WorkspaceSettingsLocation.resolve("Linux", home.toString(), mapOf("XDG_CONFIG_HOME" to "/xdg")),
        )
        assertEquals(
            Path.of("/home/test/.config/CarCatalogAssistant/settings.json"),
            WorkspaceSettingsLocation.resolve("Linux", home.toString(), emptyMap()),
        )
        assertFailsWith<IOException> {
            WorkspaceSettingsLocation.resolve("Linux", home.toString(), mapOf("XDG_CONFIG_HOME" to "relative"))
        }
    }

    @Test
    fun `workspace startup distinguishes setup ready and recovery states`() =
        withTempDirectory { directory ->
            val settings = WorkspaceSettingsStore(directory.resolve("settings.json"))
            val manager = WorkspaceManager(settings, directory.resolve("default"))
            assertIs<WorkspaceStartup.SetupRequired>(manager.startup())

            val workspace = directory.resolve("CarCatalogAssistant")
            val unrelatedFile = workspace.resolve("keep.txt")
            Files.createDirectories(workspace)
            Files.writeString(unrelatedFile, "preserve")

            val paths = manager.initialize(workspace)
            assertEquals("preserve", Files.readString(unrelatedFile))
            assertTrue(Files.isDirectory(paths.input))
            assertTrue(Files.isDirectory(paths.data))
            assertTrue(Files.isDirectory(paths.logs))
            assertEquals(WorkspaceStartup.Ready(paths), manager.startup())

            Files.delete(paths.input)
            val recovery = assertIs<WorkspaceStartup.RecoveryRequired>(manager.startup())
            assertEquals(paths.root, recovery.savedWorkspace)
            assertTrue(recovery.reason.contains("input"))

            manager.initialize(paths.root)
            assertEquals(WorkspaceStartup.Ready(paths), manager.startup())
        }

    @Test
    fun `existing workspaces gain logs on startup without treating missing logs as lost data`() =
        withTempDirectory { directory ->
            val workspace = directory.resolve("old-workspace")
            Files.createDirectories(workspace.resolve("input"))
            Files.createDirectories(workspace.resolve("data"))
            val settings = WorkspaceSettingsStore(directory.resolve("settings.json"))
            settings.save(workspace)
            val startup = WorkspaceManager(settings, directory.resolve("default")).startup()
            val ready = assertIs<WorkspaceStartup.Ready>(startup)
            assertTrue(Files.isDirectory(ready.paths.logs))
            assertTrue(Files.exists(ready.paths.logs.resolve("application.log")))
        }

    @Test
    fun `corrupt settings are reported instead of falling back to default`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            Files.writeString(settingsFile, "not-json")
            val manager = WorkspaceManager(WorkspaceSettingsStore(settingsFile), directory.resolve("default"))

            val result = assertIs<WorkspaceStartup.SettingsUnavailable>(manager.startup())

            assertTrue(result.reason.contains("invalid"))
        }

    @Test
    fun `mutation guard rejects overlapping writes and releases after completion`() =
        withTempDirectory { directory ->
            val paths = WorkspacePaths.at(directory.resolve("workspace"))
            Files.createDirectories(paths.root)
            val guard = WorkspaceMutationGuard()

            val value =
                guard.withMutationLock(paths) {
                    assertFailsWith<WorkspaceBusyException> {
                        guard.withMutationLock(paths) { Unit }
                    }
                    "complete"
                }

            assertEquals("complete", value)
            assertEquals("available", guard.withMutationLock(paths) { "available" })
        }

    @Test
    fun `workspace initialization respects an active mutation lock`() =
        withTempDirectory { directory ->
            val paths = WorkspacePaths.at(directory.resolve("workspace"))
            Files.createDirectories(paths.root)
            val settings = WorkspaceSettingsStore(directory.resolve("settings.json"))
            val guard = WorkspaceMutationGuard()
            val manager = WorkspaceManager(settings, paths.root, guard)

            guard.withMutationLock(paths) {
                assertFailsWith<WorkspaceBusyException> { manager.initialize(paths.root) }
                assertFalse(Files.exists(paths.input))
                assertNull(settings.load())
            }
        }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("workspace-storage-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
