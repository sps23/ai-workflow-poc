package com.sps23.aiworkflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
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

val FREE_MODELS: List<String> =
    listOf(
        "openrouter/free",
        "nvidia/nemotron-3-ultra-550b-a55b:free",
        "nvidia/nemotron-3.5-super-vl:free",
        "nvidia/nemotron-3.5-lightning:free",
        "cohere/north-mini-code:free",
        "recursive/flint-v1.0:free",
    )

/** Holds UI state and runs one prompt at a time through [answer] with the selected model ID. */
class CarPromptController(
    val models: List<String> = FREE_MODELS,
    private val answer: suspend (
        prompt: String,
        modelId: String,
        cachedRequest: LlmRequest?,
        onTranslated: suspend (LlmRequest) -> Unit,
    ) -> CarAnswer,
) {
    init {
        require(models.isNotEmpty()) { "At least one model is required." }
    }

    var state: CarPromptState by mutableStateOf(CarPromptState.Idle)
        private set

    var translatedRequestJson: String? by mutableStateOf(null)
        private set

    var progressLabel: String by mutableStateOf("")
        private set

    var selectedModel: String by mutableStateOf(models.first())
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
        state = CarPromptState.Idle
    }

    fun selectModel(modelId: String) {
        require(modelId in models) { "Unknown model: $modelId" }
        selectedModel = modelId
    }

    suspend fun submit(prompt: String) {
        if (state == CarPromptState.Loading) return
        updatePrompt(prompt)
        val requestedModel = selectedModel
        progressLabel = if (translatedRequest == null) "1/2 Translating request" else "2/2 Finding cars"
        state = CarPromptState.Loading
        state =
            try {
                val result =
                    answer(prompt, requestedModel, translatedRequest) {
                        translatedRequest = it
                        translatedRequestJson = LlmRequestCodec.encode(it)
                        progressLabel = "2/2 Finding cars"
                    }
                CarPromptState.Answered(
                    selection = result.selection,
                    rawOutput = result.text,
                    modelLabel =
                        result.responseModelId
                            ?: "${result.requestedModelId} (requested; response model ID not reported)",
                )
            } catch (e: CancellationException) {
                state = CarPromptState.Idle
                throw e
            } catch (e: OpenRouterWorkflowException) {
                CarPromptState.Failed(
                    buildString {
                        appendLine("Error [${e.kind}]: ${e.message}")
                        appendLine("Model: $requestedModel")
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
                        append("Suggested action: ${e.kind.suggestedAction()}")
                    },
                    rawOutput = e.rawOutput,
                )
            } catch (e: Exception) {
                CarPromptState.Failed(
                    "Error: ${e.javaClass.simpleName}: " +
                        (if (e is IllegalArgumentException) e.message else "Unexpected application failure.") +
                        "\nModel: $requestedModel\nStage: Application\nSuggested action: Check configuration and input; " +
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
            "Check model metadata/connectivity or select another listed free model."
        }

        FailureKind.REQUEST_TOO_LARGE, FailureKind.CONTEXT -> {
            "Shorten the prompt or choose a free model with a larger context."
        }

        FailureKind.AUTHENTICATION -> {
            "Check OPENROUTER_API_KEY and the account's model access."
        }

        FailureKind.RATE_LIMIT -> {
            "Check OpenRouter's quota/rate limits; wait before resending. No automatic retry was made."
        }

        FailureKind.TIMEOUT -> {
            "Check connectivity/provider availability; retry later or select another free model."
        }

        FailureKind.PROVIDER -> {
            "Use the provider reason/code to check model availability and OpenRouter service status."
        }

        FailureKind.EMPTY_RESPONSE -> {
            "The provider supplied no answer; retry later or select another free model."
        }

        FailureKind.TRUNCATED_RESPONSE -> {
            "Request fewer results or select another free model; the output/reasoning budget was exhausted. " +
                "The incomplete answer was not displayed."
        }

        FailureKind.INVALID_RESPONSE -> {
            "Copy the raw model output for diagnostics; retry or select another free model. No automatic retry was made."
        }
    }
