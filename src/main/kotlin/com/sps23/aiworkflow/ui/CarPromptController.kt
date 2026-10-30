package com.sps23.aiworkflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.LOCAL_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.LOCAL_MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.MAX_COMPLETION_TOKENS_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.PAID_QWEN_MODEL_ID
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
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
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "nvidia/nemotron-3.5-super-vl:free",
        "nvidia/nemotron-3.5-lightning:free",
        PAID_QWEN_MODEL_ID,
    )

/**
 * Holds UI state and runs one prompt at a time through [answer] with the selected API and model ID.
 * The model ID applies only to OpenRouter; local APIs use the server's loaded model.
 */
class CarPromptController(
    val models: List<String> = OPENROUTER_MODELS,
    val apis: List<LlmApi> = LlmApi.entries,
    private val answer: suspend (
        prompt: String,
        api: LlmApi,
        modelId: String,
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
        require(modelId in models) { "Unknown model: $modelId" }
        selectedModel = modelId
    }

    /** Keeps the cached translation: an unchanged prompt reruns only the recommendation stage. */
    fun selectApi(api: LlmApi) {
        require(api in apis) { "Unknown API: $api" }
        selectedApi = api
    }

    suspend fun submit(prompt: String) {
        if (state == CarPromptState.Loading) return
        updatePrompt(prompt)
        val requestedApi = selectedApi
        val requestedModel = selectedModel
        val targetLabel = if (requestedApi.isLocal) "${requestedApi.label} loaded model (${requestedApi.baseUrl})" else requestedModel
        progressLabel = if (translatedRequest == null) "1/2 Translating request" else "2/2 Finding cars"
        state = CarPromptState.Loading
        metadata = null
        val started = System.nanoTime()
        state =
            try {
                val result =
                    answer(prompt, requestedApi, requestedModel, translatedRequest) {
                        translatedRequest = it
                        translatedRequestJson = LlmRequestCodec.encode(it)
                        progressLabel = "2/2 Finding cars"
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
                        val action = if (requestedApi.isLocal) e.kind.localSuggestedAction(requestedApi) else e.kind.suggestedAction()
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
            "Set $LOCAL_API_KEY_VARIABLE to the key the local server expects and restart the app."
        }

        FailureKind.REQUEST_TOO_LARGE, FailureKind.CONTEXT -> {
            "Run ./gradlew catalogSize to inspect the indexed catalog estimate. In LM Studio, check the loaded " +
                "context length, not only the model's advertised maximum. Reload with sufficient context if supported, " +
                "or choose a larger-context model. No catalog facts are dropped to make requests fit."
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
