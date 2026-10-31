package com.sps23.aiworkflow.openrouter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

const val GOOGLE_AI_BASE_URL = "https://generativelanguage.googleapis.com"
const val GOOGLE_AI_API_KEY_VARIABLE = "GOOGLE_AI_API_KEY"
const val GOOGLE_AI_MODEL_VARIABLE = "GOOGLE_AI_MODEL"
const val GOOGLE_AI_MAX_COMPLETION_TOKENS_VARIABLE = "GOOGLE_AI_MAX_COMPLETION_TOKENS"

class GoogleAiConfig(
    val apiKey: String,
    modelId: String,
    val baseUrl: String = GOOGLE_AI_BASE_URL,
    val maxCompletionTokens: Long = 32_768,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    val modelId = modelId.trim().removePrefix("models/")

    init {
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "Google AI completion budget must be a positive Int." }
        if (apiKey.isBlank() || this.modelId.isBlank()) {
            throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Google AI API key and model ID are required.")
        }
    }

    override fun toString(): String = "GoogleAiConfig(modelId=$modelId, baseUrl=$baseUrl)"

    companion object {
        fun apiKey(
            environment: Map<String, String?>,
            override: String?,
        ): String =
            override?.trim()?.takeIf { it.isNotEmpty() }
                ?: environment[GOOGLE_AI_API_KEY_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Enter a Google AI key or set $GOOGLE_AI_API_KEY_VARIABLE.")

        fun fromEnvironment(
            environment: Map<String, String?>,
            modelId: String? = environment[GOOGLE_AI_MODEL_VARIABLE],
            apiKeyOverride: String? = null,
            baseUrl: String = GOOGLE_AI_BASE_URL,
        ): GoogleAiConfig =
            GoogleAiConfig(
                apiKey(environment, apiKeyOverride),
                modelId?.takeIf { it.isNotBlank() } ?: throw OpenRouterWorkflowException(
                    FailureKind.CONFIGURATION,
                    "Select a Google AI model or set $GOOGLE_AI_MODEL_VARIABLE for the CLI.",
                ),
                baseUrl,
                completionTokenBudget(environment, GOOGLE_AI_MAX_COMPLETION_TOKENS_VARIABLE, 32_768),
            )
    }
}

data class GoogleAiModel(
    val id: String,
    val name: String,
    val inputTokenLimit: Long,
    val outputTokenLimit: Long,
) {
    val limits: ModelLimits get() = ModelLimits(inputTokenLimit, outputTokenLimit, id)
}

internal fun parseGoogleModelPage(body: String): Pair<List<GoogleAiModel>, String?> {
    fun invalid(): Nothing = throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Google AI model metadata is invalid.")
    val root =
        try {
            Json.parseToJsonElement(body) as? JsonObject ?: invalid()
        } catch (e: IllegalArgumentException) {
            throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Google AI model metadata is not valid JSON.", e)
        }
    val models =
        (root["models"] as? JsonArray ?: invalid()).mapNotNull { entry ->
            val model = entry as? JsonObject ?: invalid()
            val methods = model["supportedGenerationMethods"] as? JsonArray ?: return@mapNotNull null
            if (methods.none { (it as? JsonPrimitive)?.contentOrNull == "generateContent" }) return@mapNotNull null

            fun text(key: String): String? = (model[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

            fun limit(key: String): Long = (model[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalid()
            val id = text("name")?.takeIf { it.startsWith("models/") && it.length > 7 }?.removePrefix("models/") ?: invalid()
            GoogleAiModel(id, text("displayName") ?: id, limit("inputTokenLimit"), limit("outputTokenLimit"))
        }
    val next =
        root["nextPageToken"]
            ?.let { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: invalid()
            }?.takeIf { it.isNotBlank() }
    return models to next
}

/** Native paginated listing; keys are headers, never URL parameters or exported metadata. */
suspend fun fetchGoogleAiModels(
    apiKey: String,
    baseUrl: String = GOOGLE_AI_BASE_URL,
): List<GoogleAiModel> =
    withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw OpenRouterWorkflowException(
                FailureKind.CONFIGURATION,
                "Google AI API key is required for model discovery.",
            )
        }
        val http =
            HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 30_000
                }
            }
        try {
            val models = mutableListOf<GoogleAiModel>()
            val seenTokens = mutableSetOf<String>()
            var next: String? = null
            do {
                val response =
                    http.get("${baseUrl.trimEnd('/')}/v1beta/models") {
                        header("x-goog-api-key", apiKey)
                        parameter("pageSize", 1000)
                        next?.let { parameter("pageToken", it) }
                    }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) throw googleMetadataFailure(response.status.value, body, apiKey)
                val page = parseGoogleModelPage(body)
                models += page.first
                next = page.second
                if (next != null && !seenTokens.add(next)) {
                    throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Google AI model pagination repeated a page token.")
                }
            } while (next != null)
            models.distinctBy { it.id }.sortedBy { it.id }.also {
                if (it.isEmpty()) {
                    throw OpenRouterWorkflowException(
                        FailureKind.MODEL_UNAVAILABLE,
                        "Google AI returned no generateContent models.",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenRouterWorkflowException) {
            throw e
        } catch (e: Exception) {
            val chain = generateSequence<Throwable>(e) { it.cause }.toList()
            val kind =
                when {
                    chain.any { it.javaClass.simpleName.contains("Timeout", true) } -> FailureKind.TIMEOUT
                    chain.any { it is ConnectException || it is UnresolvedAddressException } -> FailureKind.CONNECTION
                    else -> FailureKind.PROVIDER
                }
            throw OpenRouterWorkflowException(
                kind,
                "Google AI model discovery failed [${kind.name}].",
                e,
                OpenRouterErrorDiagnostics("Model metadata"),
            )
        } finally {
            http.close()
        }
    }

private fun googleMetadataFailure(
    status: Int,
    body: String,
    key: String,
): OpenRouterWorkflowException {
    val kind =
        when {
            status == 401 || status == 403 -> {
                FailureKind.AUTHENTICATION
            }

            status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid", ignoreCase = true)) -> {
                FailureKind.AUTHENTICATION
            }

            status == 429 -> {
                FailureKind.RATE_LIMIT
            }

            status == 408 || status == 504 -> {
                FailureKind.TIMEOUT
            }

            else -> {
                FailureKind.MODEL_UNAVAILABLE
            }
        }
    return OpenRouterWorkflowException(
        kind,
        "Google AI model discovery failed (HTTP $status).",
        diagnostics = providerDiagnostics("Model metadata", status, body, key),
    )
}

/** Google-specific discovery and independent input/output gates, reusing compatible chat inference. */
class GoogleAiClient(
    private val config: GoogleAiConfig,
) : ChatBackend {
    override val maxCompletionTokens: Long get() = config.maxCompletionTokens

    override suspend fun fetchLimits(): ModelLimits =
        fetchGoogleAiModels(config.apiKey, config.baseUrl).firstOrNull { it.id == config.modelId }?.limits
            ?: throw OpenRouterWorkflowException(
                FailureKind.MODEL_UNAVAILABLE,
                "Selected Google AI model is not listed for generateContent.",
            )

    override suspend fun complete(
        userMessage: String,
        systemPrompt: String?,
        context: String?,
        limits: ModelLimits?,
        maxCompletionTokens: Long,
    ): Completion {
        require(userMessage.isNotBlank()) { "Prompt must not be blank." }
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "Completion token budget must be a positive Int." }
        val verified = limits ?: fetchLimits()
        return LocalLlmClient(
            LocalLlmConfig(
                LlmApi.GOOGLE_AI,
                config.apiKey,
                "${config.baseUrl.trimEnd('/')}/v1beta/openai",
                config.requestTimeoutMillis,
                this.maxCompletionTokens,
                config.modelId,
            ),
        ).complete(userMessage, systemPrompt, context, verified, maxCompletionTokens)
    }
}
