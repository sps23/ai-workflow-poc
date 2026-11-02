package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogLoader
import com.sps23.aiworkflow.catalog.extraction.GeminiUsage
import com.sps23.aiworkflow.openrouter.LlmApi
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
fun PdfExtractorScreen(
    controller: PdfExtractorController,
    workspaceInput: Path? = null,
) {
    val scope = rememberCoroutineScope()
    val busy = controller.extracting

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Extract a car brochure PDF into catalog JSON", style = MaterialTheme.typography.titleMedium)
        PathRow(
            value = controller.pdfPath,
            onValueChange = controller::updatePdfPath,
            label = "PDF file",
            buttonLabel = "Choose PDF…",
            enabled = !busy,
        ) {
            chooseFile(controller.pdfPath, directories = false, useBundledFallback = workspaceInput == null)
                ?.let(controller::updatePdfPath)
        }
        if (workspaceInput == null) {
            PathRow(
                value = controller.outputDir,
                onValueChange = controller::updateOutputDir,
                label = "Output folder (<pdf name>.json is written here)",
                buttonLabel = "Choose folder…",
                enabled = !busy,
            ) {
                chooseFile(controller.outputDir, directories = true)?.let(controller::updateOutputDir)
            }
        } else {
            OutlinedTextField(
                value = workspaceInput.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Workspace input folder") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LlmProviderControls(controller.providerSelection, enabled = !busy, pdfExtraction = true)
        if (controller.providerSelection.selectedApi != LlmApi.GOOGLE_AI) {
            Text(
                "Only extracted PDF text is sent to the selected endpoint; images and visual layout are omitted. " +
                    "Scanned/image-only PDFs require Google Gemini. Review the JSON for missing table or equipment facts.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { controller.extract() } }, enabled = controller.canExtract) { Text("Extract") }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        if (workspaceInput != null) {
            Text(
                "The selected PDF is copied into the workspace only after Extract is clicked. " +
                    "The app searches using the last built catalog; build it again after extracting.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ExtractionResult(controller)
        controller.metadata?.let { DebugMetadataPanel(it) }
    }

    controller.pendingOverwrite?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.answerOverwrite(false) },
            title = { Text("Replace existing file?") },
            text = {
                if (controller.workspaceInputPath != null) {
                    Text(
                        "An identical PDF is already in the workspace at ${target.toAbsolutePath()}. " +
                            "Re-extract into its sibling JSON? This runs a new paid extraction; the PDF is kept.",
                    )
                } else {
                    Text("${target.toAbsolutePath()} already exists. Replacing it runs a new paid extraction.")
                }
            },
            confirmButton = { Button(onClick = { controller.answerOverwrite(true) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { controller.answerOverwrite(false) }) { Text("Keep existing") } },
        )
    }
}

@Composable
private fun ExtractionResult(controller: PdfExtractorController) {
    when (val state = controller.state) {
        PdfExtractorState.Idle -> {
            Unit
        }

        is PdfExtractorState.Extracting -> {
            Text("Extracting ${state.pdfFileName} with ${state.modelId}… this usually takes 1–3 minutes.")
        }

        is PdfExtractorState.Declined -> {
            Text("Kept existing ${state.file.toAbsolutePath()}; no model was called.")
        }

        is PdfExtractorState.Done -> {
            val outcome = state.outcome
            val vehicle = outcome.extraction.vehicle
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Extraction written", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Column {
                            Text("File: ${outcome.file.toAbsolutePath()}")
                            state.importedPdfPath?.let { Text("Imported PDF: ${it.toAbsolutePath()}") }
                            Text("Vehicle: ${vehicle.title} (${vehicle.make} ${vehicle.model})")
                            Text(
                                "${vehicle.trims.size} trims, ${vehicle.trims.sumOf { it.variants.size }} variants, " +
                                    "${vehicle.options.size} options",
                            )
                            Text("Model: ${outcome.extraction.provenance.model}")
                            Text("Tokens: ${tokenSummary(outcome.usage)}")
                            Text("Latency: ${outcome.latencyMillis / 1000.0} s")
                        }
                    }
                    if (outcome.softViolations.isNotEmpty()) {
                        Text("Warnings:", style = MaterialTheme.typography.titleSmall)
                        outcome.softViolations.forEach { Text("${it.rule}: ${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (controller.workspaceInputPath != null) {
                        Text("Extraction is ready. Refresh Build Catalog, then explicitly rebuild to make it searchable.")
                        state.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        Text(
                            "Review the JSON, then copy it next to its PDF under car-pricelists/<brand>/ and run generateDataModelCatalog.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OpenFolderButton(outcome.file.toAbsolutePath().parent)
                }
            }
        }

        is PdfExtractorState.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Extraction failed [${state.kind}]", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Column {
                            Text(state.message)
                            state.hardViolations.forEach {
                                Text(
                                    "${it.rule}: ${it.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            state.rawPath?.let { Text("Raw model output saved to ${it.toAbsolutePath()}") }
                            state.pdfPath?.let { Text("Imported PDF: ${it.toAbsolutePath()}") }
                            state.diagnosticPath?.let { Text("Diagnostic saved to ${it.toAbsolutePath()}") }
                            if (state.previousExtractionAvailable) {
                                Text("The previous validated extraction remains available at ${state.extractionPath}.")
                            } else if (state.pdfPath != null) {
                                Text("The imported PDF remains available for retry; no validated extraction was written.")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.rawText?.let { CopyButton("Copy raw output", it) }
                        state.rawPath?.let { OpenFolderButton(it.toAbsolutePath().parent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PathRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    buttonLabel: String,
    enabled: Boolean,
    choose: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = choose, enabled = enabled) { Text(buttonLabel) }
    }
}

@Composable
private fun OpenFolderButton(folder: Path?) {
    var error by remember(folder) { mutableStateOf<String?>(null) }
    if (folder == null || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) return
    OutlinedButton(onClick = {
        error =
            try {
                Desktop.getDesktop().open(folder.toFile())
                null
            } catch (e: IOException) {
                "Could not open folder: ${e.message}"
            }
    }) { Text("Open folder") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun tokenSummary(usage: GeminiUsage): String =
    "input ${usage.promptTokens ?: "n/a"}, " +
        "output ${if (usage.candidatesTokens == null && usage.thoughtsTokens == null) "n/a" else usage.outputTokens} " +
        "(thinking ${usage.thoughtsTokens ?: "n/a"}), " +
        "total ${usage.totalTokens ?: "n/a"}"

private fun chooseFile(
    current: String,
    directories: Boolean,
    useBundledFallback: Boolean = true,
): String? {
    val start =
        current.takeIf { it.isNotBlank() }?.let { File(it.trim()) }?.let { if (it.isDirectory) it else it.parentFile }
            ?: if (useBundledFallback) {
                File("src/main/resources${DataModelCatalogLoader.DEFAULT_RESOURCE_ROOT}").takeIf { it.isDirectory }
            } else {
                File(System.getProperty("user.home"))
            }
    if (!directories) {
        val dialog =
            FileDialog(null as Frame?, "Choose a PDF brochure", FileDialog.LOAD).apply {
                directory = start?.absolutePath
                setFilenameFilter { directory, name ->
                    File(directory, name).isDirectory || name.endsWith(".pdf", ignoreCase = true)
                }
            }
        return try {
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it).absolutePath }
        } finally {
            dialog.dispose()
        }
    }
    val chooser =
        JFileChooser(start).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
