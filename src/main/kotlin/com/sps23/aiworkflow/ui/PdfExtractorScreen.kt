package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogLoader
import com.sps23.aiworkflow.catalog.extraction.GeminiUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun PdfExtractorScreen(controller: PdfExtractorController) {
    val scope = rememberCoroutineScope()
    val busy = controller.extracting
    LaunchedEffect(controller, controller.apiKey, busy) {
        if (!busy) {
            delay(600)
            controller.loadModels()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Extract a car brochure PDF into catalog JSON with Google Gemini", style = MaterialTheme.typography.titleMedium)
        PathRow(
            value = controller.pdfPath,
            onValueChange = controller::updatePdfPath,
            label = "PDF file",
            buttonLabel = "Choose PDF…",
            enabled = !busy,
        ) {
            chooseFile(controller.pdfPath, directories = false)?.let(controller::updatePdfPath)
        }
        PathRow(
            value = controller.outputDir,
            onValueChange = controller::updateOutputDir,
            label = "Output folder (<pdf name>.json is written here)",
            buttonLabel = "Choose folder…",
            enabled = !busy,
        ) {
            chooseFile(controller.outputDir, directories = true)?.let(controller::updateOutputDir)
        }
        OutlinedTextField(
            value = controller.apiKey,
            onValueChange = controller::updateApiKey,
            enabled = !busy,
            label = { Text("Google AI Studio API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Blank uses GOOGLE_AI_API_KEY. Typed keys stay in memory only.", style = MaterialTheme.typography.bodySmall)
        GeminiModelPicker(controller, enabled = !busy)
        Text(
            "The whole PDF is uploaded to Google; paid-tier charges apply (about USD 0.05 per brochure). " +
                "Free-tier content may be used by Google to improve its products.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { controller.extract() } }, enabled = controller.canExtract) { Text("Extract") }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        ExtractionResult(controller)
        controller.metadata?.let { DebugMetadataPanel(it) }
    }

    controller.pendingOverwrite?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.answerOverwrite(false) },
            title = { Text("Replace existing file?") },
            text = { Text("${target.toAbsolutePath()} already exists. Replacing it runs a new paid extraction.") },
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
            Text("Kept existing ${state.file.toAbsolutePath()}; Gemini was not called.")
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
                    Text(
                        "Review the JSON, then copy it next to its PDF under car-pricelists/<brand>/ and run generateDataModelCatalog.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
private fun GeminiModelPicker(
    controller: PdfExtractorController,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    OutlinedTextField(
        value = controller.modelId,
        onValueChange = controller::selectModel,
        enabled = enabled,
        label = { Text("Gemini model ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("Browse / search models") }
            if (expanded && enabled) {
                ModelPickerPopup(
                    choices = controller.models.map { ModelChoice(it.id, it.name) },
                    onDismiss = { expanded = false },
                    onSelect = {
                        controller.selectModel(it)
                        expanded = false
                    },
                )
            }
        }
        OutlinedButton(
            onClick = { scope.launch { controller.loadModels(refresh = true) } },
            enabled = enabled && !controller.modelsLoading,
        ) { Text("Refresh models") }
        if (controller.modelsLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
    controller.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    controller.selectedModel?.let {
        Text("Output limit: ${it.outputTokenLimit} tokens (capped at 65,536).", style = MaterialTheme.typography.bodySmall)
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
    "input ${usage.promptTokens ?: "n/a"}, output ${usage.outputTokens} (thinking ${usage.thoughtsTokens ?: 0}), " +
        "total ${usage.totalTokens ?: "n/a"}"

/** Swing chooser; starts at the current value, else the bundled car-pricelists folder when present. */
private fun chooseFile(
    current: String,
    directories: Boolean,
): String? {
    val start =
        current.takeIf { it.isNotBlank() }?.let { File(it.trim()) }?.let { if (it.isDirectory) it else it.parentFile }
            ?: File("src/main/resources${DataModelCatalogLoader.DEFAULT_RESOURCE_ROOT}").takeIf { it.isDirectory }
    val chooser =
        JFileChooser(start).apply {
            if (directories) {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            } else {
                fileFilter = FileNameExtensionFilter("PDF documents", "pdf")
            }
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
