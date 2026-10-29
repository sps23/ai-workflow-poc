package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterCarWorkflow
import com.sps23.aiworkflow.openrouter.OpenRouterConfig
import com.sps23.aiworkflow.recommendation.CarSelection
import com.sps23.aiworkflow.recommendation.CatalogContext
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.PROTOTYPE_DISCLAIMER
import com.sps23.aiworkflow.recommendation.SourcePdfStore
import com.sps23.aiworkflow.recommendation.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.JOptionPane

private val catalogJson: String by lazy { CatalogContext.bundledJson() }

/**
 * Reads the API key per request so a missing key surfaces as a UI error, not a startup crash.
 * The dropdown choice replaces `OPENROUTER_MODEL`.
 */
private suspend fun answerFromOpenRouter(
    prompt: String,
    modelId: String,
    cachedRequest: LlmRequest?,
    onTranslated: suspend (LlmRequest) -> Unit,
): CarAnswer =
    withContext(Dispatchers.IO) {
        val config = OpenRouterConfig.fromEnvironment(System.getenv() + (MODEL_VARIABLE to modelId))
        OpenRouterCarWorkflow(config, catalogJson).answer(prompt, cachedRequest) {
            withContext(Dispatchers.Main) { onTranslated(it) }
        }
    }

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Car catalog assistant",
            state = rememberWindowState(width = 900.dp, height = 800.dp),
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarPromptScreen(remember { CarPromptController(answer = ::answerFromOpenRouter) })
                }
            }
        }
    }

@Composable
fun CarPromptScreen(controller: CarPromptController) {
    val scope = rememberCoroutineScope()
    val state = controller.state
    val loading = state == CarPromptState.Loading

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelDropdown(controller, enabled = !loading)
        OutlinedTextField(
            value = controller.prompt,
            onValueChange = controller::updatePrompt,
            enabled = !loading,
            label = { Text("Ask about cars in the catalog") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { controller.submit(controller.prompt) } }, enabled = !loading) {
                Text("Send")
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            if (loading) Text(controller.progressLabel)
        }
        controller.translatedRequestJson?.let { RequestDownloadButton(it) }
        when (state) {
            is CarPromptState.Answered -> {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CopyButton("Copy raw JSON", state.rawOutput)
                    CopyButton("Copy readable output", state.readableOutput)
                }
                SelectionContainer {
                    CarTables(state.selection)
                }
                HorizontalDivider()
                Text("Model: ${state.modelLabel}", style = MaterialTheme.typography.bodySmall)
                Text(PROTOTYPE_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
            }

            is CarPromptState.Failed -> {
                ErrorPanel(state.message)
                state.rawOutput?.let {
                    Text("Raw model output may contain request data. Review before sharing.", style = MaterialTheme.typography.bodySmall)
                    CopyButton("Copy raw model output", it)
                }
            }

            CarPromptState.Idle, CarPromptState.Loading -> {
                Unit
            }
        }
    }
}

@Composable
private fun RequestDownloadButton(json: String) {
    val scope = rememberCoroutineScope()
    var message by remember(json) { mutableStateOf<String?>(null) }
    var saving by remember(json) { mutableStateOf(false) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        message = null
                        try {
                            val chooser = JFileChooser().apply { selectedFile = java.io.File("llm-request.json") }
                            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val target = chooser.selectedFile.toPath()
                                if (!Files.exists(target) ||
                                    JOptionPane.showConfirmDialog(
                                        null,
                                        "Replace the existing file?",
                                        "Save LLM Request JSON",
                                        JOptionPane.YES_NO_OPTION,
                                    ) == JOptionPane.YES_OPTION
                                ) {
                                    withContext(Dispatchers.IO) { Files.writeString(target, json, Charsets.UTF_8) }
                                    message = "LLM Request JSON saved."
                                }
                            }
                        } catch (e: IOException) {
                            message = "Could not save LLM Request JSON: ${e.message}"
                        } catch (e: SecurityException) {
                            message = "Permission denied saving LLM Request JSON: ${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Download LLM Request JSON") }
            CopyButton("Copy to clipboard", json)
        }
        Text("Contains your original request. Review before sharing.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it) }
    }
}

@Composable
private fun CarTables(selection: CarSelection) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selection.cars.isEmpty()) Text("No cars returned.")
        selection.cars.forEach { car ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(car.car, style = MaterialTheme.typography.titleMedium)
                Text("Trim: ${car.trim ?: "Unknown"}")
                Text("Configuration: ${car.configurationId}", style = MaterialTheme.typography.bodySmall)
                if (car.options.isEmpty()) {
                    Text("No requested options returned.")
                } else {
                    OptionTableRow("Requested option", "Availability", "Details", header = true)
                    car.options.forEach { option ->
                        HorizontalDivider()
                        OptionTableRow(option.name, option.status.displayLabel(), option.details, sources = option.sources)
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionTableRow(
    name: String,
    status: String,
    details: String,
    header: Boolean = false,
    sources: List<String> = emptyList(),
) {
    val style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, modifier = Modifier.weight(2f), style = style)
        Text(status, modifier = Modifier.weight(1.3f), style = style)
        Text(details, modifier = Modifier.weight(3f), style = style)
        Column(Modifier.weight(1.5f)) {
            if (header) {
                Text("Sources", style = style)
            } else {
                SourceLinks(sources)
            }
        }
    }
}

@Composable
private fun SourceLinks(sources: List<String>) {
    val scope = rememberCoroutineScope()
    var saving by remember(sources) { mutableStateOf(false) }
    var message by remember(sources) { mutableStateOf<String?>(null) }
    var failed by remember(sources) { mutableStateOf(false) }
    if (sources.isEmpty()) Text("None")
    sources.groupBy { it.substringBeforeLast(':') }.forEach { (document, references) ->
        TextButton(
            enabled = !saving,
            onClick = {
                scope.launch {
                    saving = true
                    message = null
                    failed = false
                    try {
                        val (filename, bytes) =
                            withContext(Dispatchers.IO) {
                                SourcePdfStore(CatalogContext.loadBundledCatalog().sources).read(references.first())
                            }
                        val chooser = JFileChooser().apply { selectedFile = java.io.File(filename) }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val target = chooser.selectedFile.toPath()
                            val overwrite =
                                !Files.exists(target) ||
                                    JOptionPane.showConfirmDialog(
                                        null,
                                        "Replace the existing file?",
                                        "Save source PDF",
                                        JOptionPane.YES_NO_OPTION,
                                    ) == JOptionPane.YES_OPTION
                            if (overwrite) {
                                withContext(Dispatchers.IO) { Files.write(target, bytes) }
                                message = "PDF saved."
                            }
                        }
                    } catch (e: IOException) {
                        failed = true
                        message = "Could not save source PDF: ${e.message}"
                    } catch (e: IllegalArgumentException) {
                        failed = true
                        message = "Could not load source catalog: ${e.message}"
                    } catch (e: IllegalStateException) {
                        failed = true
                        message = "Could not load source catalog: ${e.message}"
                    } catch (e: SecurityException) {
                        failed = true
                        message = "Permission denied while saving source PDF: ${e.message}"
                    } finally {
                        saving = false
                    }
                }
            },
        ) {
            Text("source", textDecoration = TextDecoration.Underline)
        }
        Text(
            "$document (pages ${references.joinToString(", ") { it.substringAfterLast(':') }})",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    message?.let {
        Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CopyButton(
    label: String,
    content: String,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(content) { mutableStateOf(false) }
    OutlinedButton(onClick = {
        clipboard.setText(AnnotatedString(content))
        copied = true
    }) {
        Text(if (copied) "$label (copied)" else label)
    }
}

@Composable
private fun ErrorPanel(details: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(details) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Request failed", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(details))
                copied = true
            }) {
                Text(if (copied) "Copied" else "Copy error details")
            }
        }
    }
}

@Composable
private fun ModelDropdown(
    controller: CarPromptController,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model:")
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text("${controller.selectedModel} ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                controller.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            controller.selectModel(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
