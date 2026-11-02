package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sps23.aiworkflow.catalog.datamodel.WorkspaceCatalogSourceRow
import com.sps23.aiworkflow.catalog.datamodel.WorkspaceCatalogSourceType
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
fun WorkspaceSetupScreen(controller: WorkspaceDesktopController) {
    val setup = controller.state as? WorkspaceDesktopState.Setup ?: return
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Choose your catalog workspace", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The workspace stores imported PDFs in input/ and validated extractions beside them. " +
                "A complete searchable catalog is written to data/data-model-catalog.json. " +
                "The app does not copy bundled examples or change system environment variables.",
        )
        OutlinedTextField(
            value = setup.selectedPath,
            onValueChange = controller::updateSelectedPath,
            label = { Text("Workspace folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                chooseDirectory(setup.selectedPath)?.let(controller::updateSelectedPath)
            }) { Text("Choose folder…") }
            Button(onClick = { controller.initializeSelectedPath() }) { Text("Create workspace") }
        }
        setup.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Default: ${setup.defaultPath}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun WorkspaceRecoveryScreen(controller: WorkspaceDesktopController) {
    val recovery = controller.state as? WorkspaceDesktopState.Recovery ?: return
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Workspace needs attention", style = MaterialTheme.typography.headlineSmall)
        Text(recovery.reason, color = MaterialTheme.colorScheme.error)
        recovery.savedPath?.let { Text("Saved workspace: $it") }
        OutlinedTextField(
            value = recovery.selectedPath,
            onValueChange = controller::updateSelectedPath,
            label = { Text("Workspace folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.retryRecovery() }) { Text("Retry") }
            OutlinedButton(onClick = {
                chooseDirectory(recovery.selectedPath)?.let(controller::updateSelectedPath)
            }) { Text("Locate workspace…") }
            Button(onClick = { controller.initializeSelectedPath() }) { Text("Use as replacement…") }
        }
        Text("The previous workspace is left unchanged. Choose replacement only if you intend to use another folder.")
    }
}

@Composable
fun WorkspaceUnavailableScreen(reason: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Workspace settings unavailable", style = MaterialTheme.typography.headlineSmall)
        Text(reason, color = MaterialTheme.colorScheme.error)
        Text("Resolve the per-user settings location or permissions, then restart the app.")
    }
}

@Composable
fun BuildCatalogScreen(controller: WorkspaceDesktopController) {
    val active = controller.state as? WorkspaceDesktopState.Active ?: return
    val scope = rememberCoroutineScope()
    var pendingBuild by remember(active.paths) { mutableStateOf(false) }
    val output = active.paths.data.resolve("data-model-catalog.json")
    val outputExists = Files.exists(output)
    LaunchedEffect(active.paths) { controller.refreshSources() }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Build the complete workspace catalog", style = MaterialTheme.typography.titleMedium)
        Text("Workspace: ${active.paths.root}")
        Text("Output: $output")
        Text(if (outputExists) "Existing catalog will be replaced only after confirmation." else "No catalog has been built yet.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { scope.launch { controller.refreshSources() } }, enabled = !active.building) {
                Text("Refresh")
            }
            Button(
                onClick = {
                    if (outputExists) {
                        pendingBuild = true
                    } else {
                        scope.launch { controller.buildCatalog(replaceExistingConfirmed = false) }
                    }
                },
                enabled =
                    !active.building && active.inventory?.canBuild == true &&
                        active.inventoryError == null,
            ) {
                Text("Build Catalog")
            }
            if (active.building) CircularProgressIndicator()
        }
        active.inventory?.let { inventory ->
            Text("${inventory.includedCount} included · ${inventory.excludedCount} excluded")
            if (!inventory.canBuild) {
                Text("No supported JSON sources found. Extract a PDF or add a supported JSON/PDF pair to input/.")
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                inventory.rows.forEach { row -> CatalogSourceRow(row) }
            }
        }
        active.inventoryError?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        active.catalogError?.let { Text("Active catalog unavailable: $it", color = MaterialTheme.colorScheme.error) }
        if (active.sourcesChanged) {
            Text("Workspace source files changed. The active catalog remains unchanged until a successful rebuild.")
        }
        active.buildReport?.let { report ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Catalog built: ${report.vehicleCount} vehicles")
                    Text("Included sources: ${report.includedSources.joinToString()}")
                }
            }
        }
    }

    if (pendingBuild) {
        AlertDialog(
            onDismissRequest = { pendingBuild = false },
            title = { Text("Replace existing catalog?") },
            text = { Text("The current catalog at $output will be replaced only if every source validates.") },
            confirmButton = {
                Button(onClick = {
                    pendingBuild = false
                    scope.launch { controller.buildCatalog(replaceExistingConfirmed = true) }
                }) { Text("Replace and build") }
            },
            dismissButton = { TextButton(onClick = { pendingBuild = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogSourceRow(row: WorkspaceCatalogSourceRow) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${row.sourcePath} · ${row.sourceType.label()} · " +
                if (row.included) "Included" else "Excluded",
            style = MaterialTheme.typography.bodyMedium,
        )
        row.linkedPdfPath?.let { Text("PDF: $it", style = MaterialTheme.typography.bodySmall) }
        row.errors.forEach {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()
    }
}

private fun WorkspaceCatalogSourceType.label(): String =
    when (this) {
        WorkspaceCatalogSourceType.DATA_MODEL -> "Data Model JSON"
        WorkspaceCatalogSourceType.EXTRACTION -> "Validated extraction"
        WorkspaceCatalogSourceType.DIAGNOSTIC -> "Raw diagnostic (not used)"
        WorkspaceCatalogSourceType.LEGACY_RAW -> "Legacy raw output (not used)"
        WorkspaceCatalogSourceType.INVALID -> "Invalid source"
    }

private fun chooseDirectory(current: String): String? {
    val start = current.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isDirectory)
    val chooser =
        JFileChooser(start).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

@Composable
fun WorkspaceOpenFolderButton(path: Path) {
    var error by remember(path) { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = {
        error =
            try {
                Desktop.getDesktop().open(path.toFile())
                null
            } catch (exception: IOException) {
                exception.message ?: "Cannot open workspace folder."
            } catch (exception: UnsupportedOperationException) {
                "Opening folders is not supported on this system."
            }
    }) { Text("Open folder") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
