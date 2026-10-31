package com.sps23.aiworkflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.GoogleAiConfig
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.LOCAL_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.LOCAL_MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.MANUAL_MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.ManualApiConfig
import com.sps23.aiworkflow.openrouter.OPENAI_MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenAiConfig
import com.sps23.aiworkflow.openrouter.OpenRouterModel
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import com.sps23.aiworkflow.openrouter.completionTokenBudget
import com.sps23.aiworkflow.openrouter.fetchGoogleAiModels
import com.sps23.aiworkflow.openrouter.fetchManualModels
import com.sps23.aiworkflow.openrouter.fetchOpenAiModels
import com.sps23.aiworkflow.openrouter.fetchOpenRouterModels
import com.sps23.aiworkflow.openrouter.validateManualBaseUrl
import com.sps23.aiworkflow.recommendation.CarSelection
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.LlmRequestCodec
import com.sps23.aiworkflow.recommendation.PROTOTYPE_DISCLAIMER
import com.sps23.aiworkflow.recommendation.toReadableText
import kotlinx.coroutines.CancellationException

sealed interface CarPromptState {
    data object Idle : CarPromptState

    data object Loading : CarPromptState

    data class Answered(
        val selection: CarSelection,
        val rawOutput: String,
        val modelLabel: String,
    ) : CarPromptState {
        val readableOutput: String
            get() = "${selection.toReadableText()}\n\nModel: $modelLabel\n$PROTOTYPE_DISCLAIMER"
    }

    data class Failed(
        val message: String,
        val rawOutput: String? = null,
    ) : CarPromptState
}

val OPENROUTER_MODELS: List<String> =
    listOf(
        "openrouter/free",
    )

/**
 * Holds UI state and runs one prompt at a time through [answer] with the selected API and model ID.
 * Remote APIs use the selected ID; local APIs use the server's loaded model.
 */
class CarPromptController(
    val models: List<String> = OPENROUTER_MODELS,
    val apis: List<LlmApi> = LlmApi.entries,
    private val discoverModels: suspend () -> List<OpenRouterModel> = { fetchOpenRouterModels() },
    private val discoverOpenAiModels: suspend (String) -> List<String> = { fetchOpenAiModels(it) },
    private val resolveOpenAiKey: (String) -> String = { OpenAiConfig.apiKey(System.getenv(), it) },
    private val discoverGoogleModels: suspend (String) -> List<GoogleAiModel> = { fetchGoogleAiModels(it) },
    private val resolveGoogleKey: (String) -> String = { GoogleAiConfig.apiKey(System.getenv(), it) },
    private val discoverManualModels: suspend (String, String?) -> List<String> = ::fetchManualModels,
    private val manualAnswer: suspend (String, ManualApiConfig, LlmRequest?, suspend (LlmRequest) -> Unit) -> CarAnswer =
        { _, _, _, _ -> throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Manual API backend is not configured.") },
    private val answer: suspend (
        prompt: String,
        api: LlmApi,
        modelId: String,
        apiKeyOverride: String?,
        cachedRequest: LlmRequest?,
        onTranslated: suspend (LlmRequest) -> Unit,
    ) -> CarAnswer,
) {
    init {
        require(models.isNotEmpty()) { "At least one model is required." }
        require(apis.isNotEmpty()) { "At least one API is required." }
    }

    var state: CarPromptState by mutableStateOf(CarPromptState.Idle)
        private set

    var translatedRequestJson: String? by mutableStateOf(null)
        private set

    var progressLabel: String by mutableStateOf("")
        private set

    var metadata: WorkflowMetadata? by mutableStateOf(null)
        private set

    val metadataJson: String? get() = metadata?.let(WorkflowMetadataCodec::encode)

    var selectedModel: String by mutableStateOf(models.first())
        private set

    var selectedApi: LlmApi by mutableStateOf(apis.first())
        private set

    var localApiKey: String by mutableStateOf("")
        private set

    var googleApiKey: String by mutableStateOf("")
        private set
    var googleModels: List<GoogleAiModel> by mutableStateOf(emptyList())
        private set
    var googleModelsLoading: Boolean by mutableStateOf(false)
        private set
    var googleModelsError: String? by mutableStateOf(null)
        private set
    private var googleDiscoveryVersion = 0
    private var googleDiscoveryAttempted = false
    private var googleModelId = ""

    val matchingGoogleModels: List<GoogleAiModel>
        get() =
            googleModels.filter {
                it.id.contains(selectedModel, ignoreCase = true) || it.name.contains(selectedModel, ignoreCase = true)
            }
    val selectedGoogleModel: GoogleAiModel?
        get() = googleModels.firstOrNull { it.id == selectedModel.trim().removePrefix("models/") }

    fun updateGoogleApiKey(value: String) {
        if (state == CarPromptState.Loading || value == googleApiKey) return
        googleApiKey = value
        googleDiscoveryVersion++
        googleDiscoveryAttempted = false
        googleModels = emptyList()
        googleModelsError = null
        googleModelsLoading = false
    }

    suspend fun loadGoogleModels(refresh: Boolean = false) {
        if (state == CarPromptState.Loading || googleModelsLoading || (!refresh && googleDiscoveryAttempted)) return
        val version = googleDiscoveryVersion
        googleDiscoveryAttempted = true
        googleModelsLoading = true
        googleModelsError = null
        try {
            val fetched = discoverGoogleModels(resolveGoogleKey(googleApiKey))
            if (version == googleDiscoveryVersion) googleModels = fetched
        } catch (e: CancellationException) {
            if (version == googleDiscoveryVersion) googleDiscoveryAttempted = false
            throw e
        } catch (e: OpenRouterWorkflowException) {
            if (version == googleDiscoveryVersion) {
                googleModelsError =
                    when (e.kind) {
                        FailureKind.CONFIGURATION -> "Enter a Google AI key or set GOOGLE_AI_API_KEY, then Refresh models."
                        FailureKind.AUTHENTICATION -> "Google AI model discovery authentication failed. Check the key and project access."
                        else -> "Google AI model discovery failed [${e.kind}]. Refresh to retry; selected models are reverified on Send."
                    }
            }
        } finally {
            if (version == googleDiscoveryVersion) googleModelsLoading = false
        }
    }

    var manualBaseUrl: String by mutableStateOf("")
        private set
    var manualApiKey: String by mutableStateOf("")
        private set
    var manualModels: List<String> by mutableStateOf(emptyList())
        private set
    var manualModelsLoading: Boolean by mutableStateOf(false)
        private set
    var manualModelsError: String? by mutableStateOf(null)
        private set
    private var manualDiscoveryVersion = 0
    private var manualDiscoveryAttempted = false
    private var manualModelId = ""

    val matchingManualModels: List<String>
        get() = manualModels.filter { it.contains(selectedModel, ignoreCase = true) }

    fun updateManualBaseUrl(value: String) {
        if (state == CarPromptState.Loading || value == manualBaseUrl) return
        manualBaseUrl = value
        invalidateManualDiscovery()
    }

    fun updateManualApiKey(value: String) {
        if (state == CarPromptState.Loading || value == manualApiKey) return
        manualApiKey = value
        invalidateManualDiscovery()
    }

    private fun invalidateManualDiscovery() {
        manualDiscoveryVersion++
        manualDiscoveryAttempted = false
        manualModels = emptyList()
        manualModelsError = null
        manualModelsLoading = false
    }

    suspend fun loadManualModels(refresh: Boolean = false) {
        if (state == CarPromptState.Loading || manualModelsLoading || (!refresh && manualDiscoveryAttempted)) return
        val version = manualDiscoveryVersion
        manualDiscoveryAttempted = true
        manualModelsLoading = true
        manualModelsError = null
        try {
            val url = validateManualBaseUrl(manualBaseUrl)
            val fetched = discoverManualModels(url, manualApiKey.trim().takeIf { it.isNotEmpty() })
            if (version == manualDiscoveryVersion) manualModels = fetched
        } catch (e: CancellationException) {
            if (version == manualDiscoveryVersion) manualDiscoveryAttempted = false
            throw e
        } catch (e: OpenRouterWorkflowException) {
            if (version == manualDiscoveryVersion) {
                manualModelsError =
                    if (e.kind == FailureKind.CONFIGURATION) {
                        e.message
                    } else {
                        "Manual model discovery failed [${e.kind}]. Check URL/key and /models support. Typed IDs remain available; Refresh to retry."
                    }
            }
        } finally {
            if (version == manualDiscoveryVersion) manualModelsLoading = false
        }
    }

    var openAiApiKey: String by mutableStateOf("")
        private set

    var openAiModels: List<String> by mutableStateOf(emptyList())
        private set

    var openAiModelsLoading: Boolean by mutableStateOf(false)
        private set

    var openAiModelsError: String? by mutableStateOf(null)
        private set

    private var openAiDiscoveryVersion = 0
    private var openAiDiscoveryAttempted = false
    private var openRouterModelId = models.first()
    private var openAiModelId = ""

    val matchingOpenAiModels: List<String>
        get() = openAiModels.filter { it.contains(selectedModel, ignoreCase = true) }

    fun updateOpenAiApiKey(value: String) {
        if (state == CarPromptState.Loading || value == openAiApiKey) return
        openAiApiKey = value
        openAiDiscoveryVersion++
        openAiDiscoveryAttempted = false
        openAiModels = emptyList()
        openAiModelsError = null
        openAiModelsLoading = false
    }

    suspend fun loadOpenAiModels(refresh: Boolean = false) {
        if (state == CarPromptState.Loading || openAiModelsLoading || (!refresh && openAiDiscoveryAttempted)) return
        val version = openAiDiscoveryVersion
        openAiDiscoveryAttempted = true
        openAiModelsLoading = true
        openAiModelsError = null
        try {
            val key = resolveOpenAiKey(openAiApiKey)
            val fetched = discoverOpenAiModels(key)
            if (version == openAiDiscoveryVersion) openAiModels = fetched
        } catch (e: CancellationException) {
            if (version == openAiDiscoveryVersion) openAiDiscoveryAttempted = false
            throw e
        } catch (e: OpenRouterWorkflowException) {
            if (version == openAiDiscoveryVersion) {
                openAiModelsError =
                    when (e.kind) {
                        FailureKind.CONFIGURATION -> "Enter an OpenAI API key or set OPENAI_API_KEY, then Refresh models."
                        FailureKind.AUTHENTICATION -> "OpenAI model discovery authentication failed. Check the key and account access."
                        else -> "OpenAI model discovery failed [${e.kind}]. Refresh to retry; typed IDs remain available."
                    }
            }
        } finally {
            if (version == openAiDiscoveryVersion) openAiModelsLoading = false
        }
    }

    var availableModels: List<OpenRouterModel> by mutableStateOf(emptyList())
        private set

    var modelsLoading: Boolean by mutableStateOf(false)
        private set

    var modelsError: String? by mutableStateOf(null)
        private set

    private var discoveryAttempted = false

    val matchingModels: List<OpenRouterModel>
        get() =
            availableModels.filter {
                it.id.contains(selectedModel, ignoreCase = true) ||
                    it.name.contains(selectedModel, ignoreCase = true)
            }

    val selectedModelInfo: OpenRouterModel?
        get() = availableModels.firstOrNull { it.id == selectedModel.trim() }

    suspend fun loadModels(refresh: Boolean = false) {
        if (modelsLoading || (!refresh && discoveryAttempted)) return
        discoveryAttempted = true
        modelsLoading = true
        modelsError = null
        try {
            availableModels = discoverModels()
        } catch (e: CancellationException) {
            discoveryAttempted = false
            throw e
        } catch (e: OpenRouterWorkflowException) {
            modelsError =
                "Model discovery failed [${e.kind}]. Check connectivity and Refresh. Typed IDs remain available; verified on Send."
        } finally {
            modelsLoading = false
        }
    }

    fun updateLocalApiKey(value: String) {
        if (state != CarPromptState.Loading) localApiKey = value
    }

    var prompt: String by mutableStateOf("")
        private set

    private var translatedRequest: LlmRequest? = null

    fun updatePrompt(value: String) {
        if (state == CarPromptState.Loading || value == prompt) return
        prompt = value
        translatedRequest = null
        translatedRequestJson = null
        progressLabel = ""
        metadata = null
        state = CarPromptState.Idle
    }

    fun selectModel(modelId: String) {
        if (state == CarPromptState.Loading) return
        selectedModel = modelId
        if (selectedApi == LlmApi.OPENAI) {
            openAiModelId = modelId
        } else if (selectedApi == LlmApi.GOOGLE_AI) {
            googleModelId = modelId
        } else if (selectedApi == LlmApi.MANUAL) {
            manualModelId = modelId
        } else if (selectedApi == LlmApi.OPENROUTER) {
            openRouterModelId = modelId
        }
    }

    /** Keeps the cached translation: an unchanged prompt reruns only the recommendation stage. */
    fun selectApi(api: LlmApi) {
        if (state == CarPromptState.Loading) return
        require(api in apis) { "Unknown API: $api" }
        selectedApi = api
        selectedModel =
            when (api) {
                LlmApi.OPENAI -> openAiModelId
                LlmApi.GOOGLE_AI -> googleModelId
                LlmApi.MANUAL -> manualModelId
                else -> openRouterModelId
            }
    }

    suspend fun submit(prompt: String) {
        if (state == CarPromptState.Loading) return
        updatePrompt(prompt)
        val requestedApi = selectedApi
        val requestedModel = selectedModel.trim()
        val requestedKey =
            when {
                requestedApi.isLocal -> localApiKey.takeIf { it.isNotBlank() }
                requestedApi == LlmApi.OPENAI -> openAiApiKey.takeIf { it.isNotBlank() }
                requestedApi == LlmApi.GOOGLE_AI -> googleApiKey.takeIf { it.isNotBlank() }
                requestedApi == LlmApi.MANUAL -> manualApiKey.takeIf { it.isNotBlank() }
                else -> null
            }
        val targetLabel = if (requestedApi.isLocal) "${requestedApi.label} loaded model (${requestedApi.baseUrl})" else requestedModel
        progressLabel = if (translatedRequest == null) "1/2 Translating request" else "2/2 Finding cars"
        state = CarPromptState.Loading
        metadata = null
        val started = System.nanoTime()
        state =
            try {
                val onTranslated: suspend (LlmRequest) -> Unit = {
                    translatedRequest = it
                    translatedRequestJson = LlmRequestCodec.encode(it)
                    progressLabel = "2/2 Finding cars"
                }
                val result =
                    if (requestedApi == LlmApi.MANUAL) {
                        val config =
                            ManualApiConfig(
                                manualBaseUrl,
                                requestedKey,
                                requestedModel,
                                completionTokenBudget(System.getenv(), MANUAL_MAX_COMPLETION_TOKENS_VARIABLE, 32768),
                            )
                        manualAnswer(prompt, config, translatedRequest, onTranslated)
                    } else {
                        answer(prompt, requestedApi, requestedModel, requestedKey, translatedRequest, onTranslated)
                    }
                metadata = result.metadata
                CarPromptState.Answered(
                    selection = result.selection,
                    rawOutput = result.text,
                    modelLabel =
                        if (requestedApi.isLocal) {
                            "${result.responseModelId ?: "loaded model (ID not reported)"} via ${requestedApi.label}"
                        } else {
                            result.responseModelId
                                ?: "${result.requestedModelId} (requested; response model ID not reported)"
                        },
                )
            } catch (e: CancellationException) {
                state = CarPromptState.Idle
                throw e
            } catch (e: OpenRouterWorkflowException) {
                metadata =
                    e.metadata ?: WorkflowMetadata(
                        elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                        stages =
                            listOf(
                                StageMetrics(
                                    e.diagnostics?.stage ?: "Application",
                                    "FAILED",
                                    (System.nanoTime() - started) / 1_000_000,
                                    e.kind.name,
                                    e.requestMetrics,
                                ),
                            ),
                    )
                CarPromptState.Failed(
                    buildString {
                        appendLine("Error [${e.kind}]: ${e.message}")
                        appendLine("API: ${requestedApi.label}")
                        appendLine("Model: $targetLabel")
                        appendLine("Stage: ${e.diagnostics?.stage ?: "Local validation / response processing"}")
                        e.diagnostics?.let { details ->
                            appendLine("HTTP status: ${details.httpStatus ?: "Not available"}")
                            details.providerCode?.let { appendLine("Provider code: $it") }
                            details.providerMessage?.let { appendLine("Provider message: $it") }
                            details.providerName?.let { appendLine("Provider: $it") }
                            details.requestId?.let { appendLine("Request ID: $it") }
                            details.upstreamCode?.let { appendLine("Upstream code: $it") }
                            details.upstreamMessage?.let { appendLine("Upstream message: $it") }
                        }
                        val action =
                            when {
                                requestedApi.isLocal -> e.kind.localSuggestedAction(requestedApi)
                                requestedApi == LlmApi.OPENAI -> e.kind.openAiSuggestedAction()
                                requestedApi == LlmApi.GOOGLE_AI -> e.kind.googleSuggestedAction()
                                requestedApi == LlmApi.MANUAL -> e.kind.manualSuggestedAction()
                                else -> e.kind.suggestedAction()
                            }
                        append("Suggested action: $action")
                    },
                    rawOutput = e.rawOutput,
                )
            } catch (e: Exception) {
                metadata =
                    WorkflowMetadata(
                        elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                        stages = listOf(StageMetrics("Application", "FAILED", (System.nanoTime() - started) / 1_000_000)),
                    )
                CarPromptState.Failed(
                    "Error: ${e.javaClass.simpleName}: " +
                        (if (e is IllegalArgumentException) e.message else "Unexpected application failure.") +
                        "\nAPI: ${requestedApi.label}\nModel: $targetLabel\nStage: Application" +
                        "\nSuggested action: Check configuration and input; " +
                        "report this error if it persists.",
                )
            }
    }
}

private fun FailureKind.googleSuggestedAction(): String =
    when (this) {
        FailureKind.CONFIGURATION, FailureKind.AUTHENTICATION -> {
            "Enter a valid Google AI Studio key (or set GOOGLE_AI_API_KEY) and select an available model."
        }

        FailureKind.MODEL_UNAVAILABLE, FailureKind.PROVIDER -> {
            "Refresh Google models and check Gemini API compatibility and the provider diagnostics; no fallback was made."
        }

        FailureKind.RATE_LIMIT -> {
            "Check Google AI project quota, billing and rate limits; wait before resending. No automatic retry was made."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Increase $GOOGLE_AI_MAX_COMPLETION_TOKENS_VARIABLE within the model output limit and restart, or request fewer results."
        }

        FailureKind.CONTEXT, FailureKind.REQUEST_TOO_LARGE -> {
            "Check the reported Google model input limit; shorten the prompt or choose a larger-input model. No catalog facts are dropped."
        }

        else -> {
            suggestedAction()
        }
    }

private fun FailureKind.manualSuggestedAction(): String =
    when (this) {
        FailureKind.CONFIGURATION -> {
            "Check the manual base URL and exact model ID. Use HTTPS remotely or HTTP on loopback only."
        }

        FailureKind.AUTHENTICATION -> {
            "Enter the key required by this endpoint; blank sends no authentication."
        }

        FailureKind.MODEL_UNAVAILABLE, FailureKind.PROVIDER -> {
            "Check the endpoint supports OpenAI-compatible /chat/completions and the selected model."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Increase $MANUAL_MAX_COMPLETION_TOKENS_VARIABLE within provider limits and restart, or request fewer results."
        }

        FailureKind.CONNECTION -> {
            "Check the manual endpoint URL and connectivity."
        }

        FailureKind.RATE_LIMIT -> {
            "Check this endpoint's quota/billing and retry later. No automatic retry was made."
        }

        else -> {
            suggestedAction()
        }
    }

private fun FailureKind.openAiSuggestedAction(): String =
    when (this) {
        FailureKind.CONFIGURATION, FailureKind.AUTHENTICATION -> {
            "Enter a valid OpenAI API key (or set OPENAI_API_KEY), select a model and check account access."
        }

        FailureKind.MODEL_UNAVAILABLE, FailureKind.PROVIDER -> {
            "Check the OpenAI provider diagnostics; select a model supporting Chat Completions text output. No fallback was made."
        }

        FailureKind.RATE_LIMIT -> {
            "Check OpenAI account quota, billing and rate limits; wait before resending. No automatic retry was made."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Increase $OPENAI_MAX_COMPLETION_TOKENS_VARIABLE within the model's supported limit and restart, or request fewer results."
        }

        FailureKind.CONTEXT, FailureKind.REQUEST_TOO_LARGE -> {
            "Check OpenAI's model context/output limits. Choose a larger-context model; no catalog facts are dropped."
        }

        else -> {
            suggestedAction()
        }
    }

private fun FailureKind.suggestedAction(): String =
    when (this) {
        FailureKind.CONFIGURATION -> {
            "Set the required environment variables and restart the app."
        }

        FailureKind.MODEL_UNAVAILABLE -> {
            "Check model metadata/connectivity or select another listed model."
        }

        FailureKind.REQUEST_TOO_LARGE, FailureKind.CONTEXT -> {
            "Shorten the prompt or choose a model with a larger context."
        }

        FailureKind.AUTHENTICATION -> {
            "Check OPENROUTER_API_KEY and the account's model access."
        }

        FailureKind.RATE_LIMIT -> {
            "Check OpenRouter's quota/rate limits; wait before resending. No automatic retry was made."
        }

        FailureKind.TIMEOUT -> {
            "Check connectivity/provider availability; retry later or select another model."
        }

        FailureKind.PROVIDER -> {
            "Use the provider reason/code to check model availability and OpenRouter service status."
        }

        FailureKind.EMPTY_RESPONSE -> {
            "The provider supplied no answer; retry later or select another model."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Increase $MAX_COMPLETION_TOKENS_VARIABLE and restart the app, request fewer results, or select another model; " +
                "the output/reasoning budget was exhausted. " +
                "The incomplete answer was not displayed."
        }

        FailureKind.INVALID_RESPONSE -> {
            "Copy the raw model output for diagnostics; retry or select another model. No automatic retry was made."
        }

        FailureKind.CONNECTION -> {
            "Check network connectivity and retry."
        }
    }

private fun FailureKind.localSuggestedAction(api: LlmApi): String =
    when (this) {
        FailureKind.CONNECTION -> {
            "Start ${api.label} at ${api.baseUrl} and load a model, then resend."
        }

        FailureKind.AUTHENTICATION -> {
            "Enter the key the local server expects, or set $LOCAL_API_KEY_VARIABLE and restart the app."
        }

        FailureKind.REQUEST_TOO_LARGE, FailureKind.CONTEXT -> {
            "Check the reported input estimate and context limit. In LM Studio, check the loaded context length, " +
                "not only the model's advertised maximum. Reload with sufficient context if supported, or choose " +
                "a larger-context model. No catalog facts are dropped to make requests fit."
        }

        FailureKind.MODEL_UNAVAILABLE -> {
            "In LM Studio, load exactly one chat model and check /api/v0/models reports loaded_context_length."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Increase $LOCAL_MAX_COMPLETION_TOKENS_VARIABLE and restart the app. LM Studio caps output to estimated " +
                "remaining loaded context; if context-limited, increase supported loaded context or request fewer results. " +
                "The incomplete answer was not displayed."
        }

        FailureKind.INVALID_RESPONSE -> {
            "Copy the raw model output for diagnostics; retry or load a stronger local model. No automatic retry was made."
        }

        else -> {
            "Check the ${api.label} server log and loaded model, then retry."
        }
    }
