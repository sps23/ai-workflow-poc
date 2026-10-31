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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.LOCAL_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.LocalLlmConfig
import com.sps23.aiworkflow.openrouter.MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterCarWorkflow
import com.sps23.aiworkflow.openrouter.OpenRouterConfig
import com.sps23.aiworkflow.openrouter.PAID_QWEN_MODEL_ID
import com.sps23.aiworkflow.recommendation.CarSelection
import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import com.sps23.aiworkflow.recommendation.DataModelPdfStore
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.PROTOTYPE_DISCLAIMER
import com.sps23.aiworkflow.recommendation.displayLabel
import com.sps23.aiworkflow.recommendation.toReadableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.JOptionPane

private val dataModelCatalog by lazy { DataModelCatalogContext.loadBundled() }
private val catalogJson: String by lazy { dataModelCatalog.promptJson }

/**
 * Reads keys per request so a missing key surfaces as a UI error, not a startup crash.
 * OpenRouter: the model dropdown replaces `OPENROUTER_MODEL`. Local: optional `LOCAL_AI_API_KEY`, loaded model.
 */
private suspend fun answerFromLlm(
    prompt: String,
    api: LlmApi,
    modelId: String,
    cachedRequest: LlmRequest?,
    onTranslated: suspend (LlmRequest) -> Unit,
): CarAnswer =
    withContext(Dispatchers.IO) {
        val workflow =
            if (api.isLocal) {
                OpenRouterCarWorkflow(LocalLlmConfig.fromEnvironment(System.getenv(), api), catalogJson)
            } else {
                OpenRouterCarWorkflow(OpenRouterConfig.fromEnvironment(System.getenv() + (MODEL_VARIABLE to modelId)), catalogJson)
            }
        workflow.answer(prompt, cachedRequest) {
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
                    CarPromptScreen(remember { CarPromptController(answer = ::answerFromLlm) })
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Selector("API:", controller.selectedApi.label, controller.apis, { it.label }, controller::selectApi, enabled = !loading)
            if (!controller.selectedApi.isLocal) {
                Selector(
                    "Model:",
                    controller.selectedModel,
                    controller.models,
                    { if (it == PAID_QWEN_MODEL_ID) "$it (paid)" else it },
                    controller::selectModel,
                    enabled = !loading,
                )
            }
        }
        if (controller.selectedApi.isLocal) {
            Text(
                "Uses the model loaded in ${controller.selectedApi.label} at ${controller.selectedApi.baseUrl}. " +
                    "Optional key: $LOCAL_API_KEY_VARIABLE. The full catalog needs a large context window.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!controller.selectedApi.isLocal && controller.selectedModel == PAID_QWEN_MODEL_ID) {
            Text(
                "Paid model: OpenRouter charges apply to translation and full-catalog recommendation requests.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
                    CopyButton(
                        "Copy readable output",
                        "${state.selection.toReadableText(dataModelCatalog)}\n\nModel: ${state.modelLabel}\n$PROTOTYPE_DISCLAIMER",
                    )
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
        controller.metadata?.let { DebugMetadataPanel(it) }
    }
}

@Composable
private fun RequestDownloadButton(json: String) {
    JsonExportButtons(
        json = json,
        fileName = "llm-request.json",
        contentLabel = "LLM Request JSON",
        downloadLabel = "Download LLM Request JSON",
        copyLabel = "Copy to clipboard",
        description = "Contains your original request. Review before sharing.",
    )
}

@Composable
internal fun JsonExportButtons(
    json: String,
    fileName: String,
    contentLabel: String,
    downloadLabel: String,
    copyLabel: String,
    description: String,
) {
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
                            val chooser = JFileChooser().apply { selectedFile = java.io.File(fileName) }
                            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                val target = chooser.selectedFile.toPath()
                                if (!Files.exists(target) ||
                                    JOptionPane.showConfirmDialog(
                                        null,
                                        "Replace the existing file?",
                                        "Save $contentLabel",
                                        JOptionPane.YES_NO_OPTION,
                                    ) == JOptionPane.YES_OPTION
                                ) {
                                    withContext(Dispatchers.IO) { Files.writeString(target, json, Charsets.UTF_8) }
                                    message = "$contentLabel saved."
                                }
                            }
                        } catch (e: IOException) {
                            message = "Could not save $contentLabel: ${e.message}"
                        } catch (e: SecurityException) {
                            message = "Permission denied saving $contentLabel: ${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(downloadLabel) }
            CopyButton(copyLabel, json)
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it) }
    }
}

@Composable
private fun CarTables(selection: CarSelection) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selection.cars.isEmpty()) Text("No cars returned.")
        selection.cars.groupBy { it.car }.forEach { (model, configurations) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model, style = MaterialTheme.typography.titleMedium)
                configurations.forEach { car ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Trim: ${car.trim ?: "Unknown"}")
                        Text("Configuration: ${car.configurationId}", style = MaterialTheme.typography.bodySmall)
                        val details = dataModelCatalog.details(car)
                        Text(details.engine)
                        details.priceLines.forEach { Text(it) }
                        OptionTableRow("Requested option", "Availability", "Details", header = true)
                        if (car.options.isEmpty()) {
                            OptionTableRow("No requested options returned.", "", "", configurationId = car.configurationId)
                        } else {
                            car.options.forEach { option ->
                                HorizontalDivider()
                                OptionTableRow(
                                    option.name,
                                    option.status.displayLabel(),
                                    option.details,
                                    configurationId = car.configurationId,
                                )
                            }
                        }
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
    configurationId: String? = null,
) {
    val style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, modifier = Modifier.weight(2f), style = style)
        Text(status, modifier = Modifier.weight(1.3f), style = style)
        Text(details, modifier = Modifier.weight(3f), style = style)
        Column(Modifier.weight(1.5f)) {
            if (header) {
                Text("PDF Source", style = style)
            } else if (configurationId != null) {
                DataModelSourcePdfLink(configurationId)
            }
        }
    }
}

@Composable
private fun DataModelSourcePdfLink(configurationId: String) {
    val scope = rememberCoroutineScope()
    var saving by remember(configurationId) { mutableStateOf(false) }
    var message by remember(configurationId) { mutableStateOf<String?>(null) }
    var failed by remember(configurationId) { mutableStateOf(false) }
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
                            DataModelPdfStore(dataModelCatalog).read(configurationId)
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
                } catch (e: SecurityException) {
                    failed = true
                    message = "Permission denied while saving source PDF: ${e.message}"
                } finally {
                    saving = false
                }
            }
        },
    ) {
        Text(if (saving) "Saving..." else "Download")
    }
    message?.let {
        Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun CopyButton(
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
private fun <T> Selector(
    title: String,
    selectedLabel: String,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
                Text("$selectedLabel ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
