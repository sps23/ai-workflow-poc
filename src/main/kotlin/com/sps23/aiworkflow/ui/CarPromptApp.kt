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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.LOCAL_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.ManualApiConfig
import com.sps23.aiworkflow.openrouter.OpenRouterCarWorkflow
import com.sps23.aiworkflow.openrouter.chatBackendFor
import com.sps23.aiworkflow.recommendation.CarSelection
import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import com.sps23.aiworkflow.recommendation.DataModelPdfStore
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.PROTOTYPE_DISCLAIMER
import com.sps23.aiworkflow.recommendation.displayLabel
import com.sps23.aiworkflow.recommendation.toReadableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import kotlin.time.Duration.Companion.milliseconds

private val dataModelCatalog by lazy { DataModelCatalogContext.loadBundled() }
private val catalogJson: String by lazy { dataModelCatalog.promptJson }

/**
 * Reads keys per request so a missing key surfaces as a UI error, not a startup crash.
 * Remote APIs use their selected model; local APIs use their loaded model. Key overrides stay in memory.
 */
private suspend fun answerFromLlm(
    prompt: String,
    api: LlmApi,
    modelId: String,
    apiKeyOverride: String?,
    cachedRequest: LlmRequest?,
    onTranslated: suspend (LlmRequest) -> Unit,
): CarAnswer =
    withContext(Dispatchers.IO) {
        val workflow = OpenRouterCarWorkflow(chatBackendFor(api, modelId, apiKeyOverride, System.getenv()), catalogJson)
        workflow.answer(prompt, cachedRequest) {
            withContext(Dispatchers.Main) { onTranslated(it) }
        }
    }

private suspend fun answerFromManual(
    prompt: String,
    config: ManualApiConfig,
    cachedRequest: LlmRequest?,
    onTranslated: suspend (LlmRequest) -> Unit,
): CarAnswer =
    withContext(Dispatchers.IO) {
        OpenRouterCarWorkflow(config.backend(), catalogJson).answer(prompt, cachedRequest) {
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
                    CarCatalogApp(
                        remember { CarPromptController(manualAnswer = ::answerFromManual, answer = ::answerFromLlm) },
                        remember { PdfExtractorController() },
                    )
                }
            }
        }
    }

enum class AppTab(
    val label: String,
) {
    RECOMMENDER("Recommender"),
    PDF_EXTRACTOR("PDF Extractor"),
}

/** Tabs are locked while a request runs: leaving a tab would cancel its in-flight coroutine. */
@Composable
fun CarCatalogApp(
    recommender: CarPromptController,
    pdfExtractor: PdfExtractorController,
    initialTab: AppTab = AppTab.RECOMMENDER,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val busy = recommender.state == CarPromptState.Loading || pdfExtractor.extracting
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            AppTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    enabled = !busy || tab == entry,
                    text = { Text(entry.label) },
                )
            }
        }
        when (tab) {
            AppTab.RECOMMENDER -> CarPromptScreen(recommender)
            AppTab.PDF_EXTRACTOR -> PdfExtractorScreen(pdfExtractor)
        }
    }
}

@Composable
internal fun LlmProviderControls(
    controller: CarPromptController,
    enabled: Boolean,
    pdfExtraction: Boolean = false,
) {
    val loading = !enabled
    LaunchedEffect(controller, controller.selectedApi, loading) {
        if (controller.selectedApi == LlmApi.OPENROUTER && !loading) controller.loadModels()
    }
    LaunchedEffect(controller, controller.selectedApi, controller.openAiApiKey, loading) {
        if (controller.selectedApi == LlmApi.OPENAI && !loading) {
            delay(600.milliseconds)
            controller.loadOpenAiModels()
        }
    }
    LaunchedEffect(controller, controller.selectedApi, controller.manualBaseUrl, controller.manualApiKey, loading) {
        if (controller.selectedApi == LlmApi.MANUAL && !loading && controller.manualBaseUrl.isNotBlank()) {
            delay(600.milliseconds)
            controller.loadManualModels()
        }
    }
    LaunchedEffect(controller, controller.selectedApi, controller.googleApiKey, loading) {
        if (controller.selectedApi == LlmApi.GOOGLE_AI && !loading) {
            delay(600.milliseconds)
            controller.loadGoogleModels()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Selector("API:", controller.selectedApi.label, controller.apis, { it.label }, controller::selectApi, enabled = !loading)
        }
        if (controller.selectedApi.isLocal) {
            OutlinedTextField(
                value = controller.localApiKey,
                onValueChange = controller::updateLocalApiKey,
                enabled = !loading,
                label = { Text("Local API key (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Uses the model loaded in ${controller.selectedApi.label} at ${controller.selectedApi.baseUrl}. " +
                    "Blank key uses $LOCAL_API_KEY_VARIABLE if set; typed keys stay in memory only. " +
                    "${if (pdfExtraction) "PDF text" else "The full catalog"} needs a large context window.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            if (controller.selectedApi == LlmApi.GOOGLE_AI) {
                OutlinedTextField(
                    value = controller.googleApiKey,
                    onValueChange = controller::updateGoogleApiKey,
                    enabled = !loading,
                    label = { Text("Google AI Studio API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Blank uses GOOGLE_AI_API_KEY. Typed keys stay in memory; available models load after key entry.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (controller.selectedApi == LlmApi.MANUAL) {
                OutlinedTextField(
                    value = controller.manualBaseUrl,
                    onValueChange = controller::updateManualBaseUrl,
                    enabled = !loading,
                    label = { Text("API base URL (including /v1 if required)") },
                    placeholder = { Text("https://provider.example/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = controller.manualApiKey,
                    onValueChange = controller::updateManualApiKey,
                    enabled = !loading,
                    label = { Text("Manual API key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Blank key sends no authentication. URL/key stay in memory. HTTPS required except loopback HTTP.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (controller.selectedApi == LlmApi.OPENAI) {
                OutlinedTextField(
                    value = controller.openAiApiKey,
                    onValueChange = controller::updateOpenAiApiKey,
                    enabled = !loading,
                    label = { Text("OpenAI API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Blank uses OPENAI_API_KEY. Typed keys stay in memory only. Model discovery refreshes after key entry.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RemoteModelPicker(controller, enabled = !loading, pdfExtraction = pdfExtraction)
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
        LlmProviderControls(controller, enabled = !loading)
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
internal fun RemoteModelPicker(
    controller: CarPromptController,
    enabled: Boolean,
    pdfExtraction: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val openAi = controller.selectedApi == LlmApi.OPENAI
    val manual = controller.selectedApi == LlmApi.MANUAL
    val google = controller.selectedApi == LlmApi.GOOGLE_AI
    val provider = controller.selectedApi.label
    val modelsLoading =
        when {
            manual -> controller.manualModelsLoading
            google -> controller.googleModelsLoading
            openAi -> controller.openAiModelsLoading
            else -> controller.modelsLoading
        }
    val choices =
        when {
            manual -> controller.manualModels.map { ModelChoice(it) }
            google -> googleModelChoices(controller.googleModels)
            openAi -> controller.openAiModels.map { ModelChoice(it) }
            else -> controller.availableModels.map { it.toModelChoice() }.sortedWith(compareBy<ModelChoice> { it.tier }.thenBy { it.id })
        }
    var expanded by remember(controller.selectedApi) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = controller.selectedModel,
            onValueChange = controller::selectModel,
            enabled = enabled,
            label = { Text("$provider selected model ID (exact entry)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("Browse / search models") }
                if (expanded && enabled) {
                    ModelPickerPopup(
                        choices = choices,
                        onDismiss = { expanded = false },
                        onSelect = {
                            controller.selectModel(it)
                            expanded = false
                        },
                    )
                }
            }
            TextButton(onClick = { controller.selectModel("") }, enabled = enabled) { Text("Clear selection") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        when {
                            manual -> controller.loadManualModels(refresh = true)
                            google -> controller.loadGoogleModels(refresh = true)
                            openAi -> controller.loadOpenAiModels(refresh = true)
                            else -> controller.loadModels(refresh = true)
                        }
                    }
                },
                enabled = enabled && !modelsLoading,
            ) { Text("Refresh models") }
            if (modelsLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        if (google) {
            Text(
                "Browse all Google generateContent models; search inside the picker or enter an exact ID. " +
                    "Selected models and limits are reverified before inference. Preset labels are display-only, not thinking settings or prices.",
                style = MaterialTheme.typography.bodySmall,
            )
            controller.googleModelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            controller.selectedGoogleModel?.let {
                Text(
                    "Input limit: ${it.inputTokenLimit}; output limit: ${it.outputTokenLimit} tokens.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Pricing is not reported by model discovery. " +
                    "${if (pdfExtraction) "The whole PDF goes" else "Prompt/catalog go"} to Google; " +
                    "quota limits and charges may apply. Free-tier content may be used to improve Google products. No fallback.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (manual) {
            Text(
                "Requires OpenAI-compatible /chat/completions with model, messages and max_tokens. " +
                    "Model discovery uses /models if supported; otherwise type an exact ID. Pricing/context limits are unavailable.",
                style = MaterialTheme.typography.bodySmall,
            )
            controller.manualModelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "${if (pdfExtraction) "Extracted PDF text" else "Prompt and full catalog"} " +
                    "are sent to this endpoint; charges may apply. Review the URL before sending. No automatic fallback.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (openAi) {
            Text(
                "Browse all OpenAI-listed IDs or type an exact model ID. Compatibility unknown: the list includes non-chat models; " +
                    "Chat Completions support, pricing and context/output limits are not reported. " +
                    "Unsupported models and context errors are reported by OpenAI.",
                style = MaterialTheme.typography.bodySmall,
            )
            controller.openAiModelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                "Paid API: " +
                    "${if (pdfExtraction) "extracted PDF text is" else "prompt and full catalog are"} sent to OpenAI. " +
                    "Requests may incur charges. No automatic fallback.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "Search inside the picker by ID/name, or enter an exact model ID. Only verified text models are listed. " +
                    "Output USD / 1M token tiers: Low <=1, Medium >1 to 10, High >10. Free requires all non-discount charges zero. " +
                    "Other provider charges may apply; tiers are UI-defined, not provider ratings.",
                style = MaterialTheme.typography.bodySmall,
            )
            controller.modelsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val model = controller.selectedModelInfo
            if (model != null) {
                Text(
                    "${if (model.isFree) "Free" else "Paid"} model. " +
                        "USD/token: input ${model.promptPrice}; output ${model.completionPrice}. Other provider charges may apply.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (model?.isFree != true) {
                Text(
                    if (model == null) {
                        "Pricing unverified: this ID may incur charges. Live metadata checks run on Send; no automatic fallback."
                    } else {
                        "Paid model: OpenRouter charges apply to " +
                            (
                                if (pdfExtraction) {
                                    "PDF text extraction requests."
                                } else {
                                    "translation and full-catalog recommendation requests."
                                }
                            )
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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
