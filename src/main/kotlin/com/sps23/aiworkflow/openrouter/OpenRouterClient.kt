package com.sps23.aiworkflow.openrouter

import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val MODELS_PATH = "api/v1/models"

data class Completion(
    val text: String,
    val requestedModelId: String,
    /** Model ID reported by the provider response, when Koog exposes it. */
    val responseModelId: String?,
)

/**
 * Minimal OpenRouter client: verifies the configured `:free` model, then sends one
 * Koog inference request (no tools, fallbacks, plugins or retries).
 */
class OpenRouterClient(
    private val config: OpenRouterConfig,
) {
    private val baseUrl = config.baseUrl.trimEnd('/')

    /** Reads live model metadata and checks the configured model is free and text-capable. */
    suspend fun fetchLimits(): ModelLimits {
        val http =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeoutMillis
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = config.requestTimeoutMillis
                }
            }
        try {
            val response = http.get("$baseUrl/$MODELS_PATH")
            if (!response.status.isSuccess()) {
                throw OpenRouterWorkflowException(
                    FailureKind.MODEL_UNAVAILABLE,
                    "Model metadata request failed with HTTP ${response.status.value}.",
                    diagnostics = diagnostics("Model metadata", response.status.value, response.bodyAsText()),
                )
            }
            return verifyFreeModel(response.bodyAsText(), config.modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenRouterWorkflowException) {
            throw OpenRouterWorkflowException(
                e.kind,
                e.message.orEmpty(),
                e,
                e.diagnostics ?: OpenRouterErrorDiagnostics("Model metadata"),
            )
        } catch (e: Exception) {
            throw classify(e, "Model metadata")
        } finally {
            http.close()
        }
    }

    /**
     * Sends one prompt and returns the non-empty, non-truncated answer.
     * Fetches model limits first unless [limits] is supplied.
     * When [context] is given, the user message becomes delimited data blocks (see [buildUserMessage]).
     * Fails with [FailureKind.REQUEST_TOO_LARGE] before inference if the estimated request exceeds the model context.
     */
    suspend fun complete(
        userMessage: String,
        systemPrompt: String? = null,
        context: String? = null,
        limits: ModelLimits? = null,
        maxCompletionTokens: Long = config.maxCompletionTokens,
    ): Completion {
        require(userMessage.isNotBlank()) { "Prompt must not be blank." }
        val modelLimits = limits ?: fetchLimits()
        val completionTokens = minOf(maxCompletionTokens, modelLimits.maxCompletionTokens ?: Long.MAX_VALUE)
        val message = context?.let { buildUserMessage(userMessage, it) } ?: userMessage
        val inputTokens = estimateInputTokens(systemPrompt.orEmpty(), message)
        if (inputTokens + completionTokens > modelLimits.contextLength) {
            throw OpenRouterWorkflowException(
                FailureKind.REQUEST_TOO_LARGE,
                "Request does not fit: estimated $inputTokens input tokens + $completionTokens completion tokens " +
                    "exceeds the model context of ${modelLimits.contextLength} tokens (estimate is a conservative " +
                    "heuristic, not a tokenizer count). Nothing was sent.",
                diagnostics = OpenRouterErrorDiagnostics("Request size validation"),
            )
        }

        val model =
            LLModel(
                provider = LLMProvider.OpenRouter,
                id = config.modelId,
                capabilities = listOf(LLMCapability.Completion),
                contextLength = modelLimits.contextLength,
                maxOutputTokens = modelLimits.maxCompletionTokens,
            )
        val request =
            prompt(
                id = "openrouter-completion",
                params =
                    OpenRouterParams(
                        temperature = 0.2,
                        maxTokens = completionTokens.toInt(),
                        // Empty list disables OpenRouter's default middle-out truncation.
                        transforms = emptyList(),
                    ),
            ) {
                systemPrompt?.let { system(it) }
                user(message)
            }

        val client =
            OpenRouterLLMClient(
                apiKey = config.apiKey,
                settings =
                    OpenRouterClientSettings(
                        baseUrl = baseUrl,
                        timeoutConfig =
                            ConnectionTimeoutConfig(
                                requestTimeoutMillis = config.requestTimeoutMillis,
                                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                                socketTimeoutMillis = config.requestTimeoutMillis,
                            ),
                    ),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
        val response =
            try {
                client.execute(request, model, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e, "Chat completion")
            } finally {
                client.close()
            }

        val text = response.textContent()
        if (response.finishReason == "length") {
            throw OpenRouterWorkflowException(
                FailureKind.TRUNCATED_RESPONSE,
                "The model response was truncated at the $completionTokens-token output limit " +
                    "(configured: $maxCompletionTokens; model maximum: " +
                    "${modelLimits.maxCompletionTokens ?: "not reported"}). " +
                    "This budget may also include reasoning tokens, not just the visible answer.",
                diagnostics = OpenRouterErrorDiagnostics("Response validation"),
                rawOutput = text,
            )
        }
        if (text.isBlank()) {
            throw OpenRouterWorkflowException(
                FailureKind.EMPTY_RESPONSE,
                "The model returned an empty response.",
                diagnostics = OpenRouterErrorDiagnostics("Response validation"),
            )
        }
        return Completion(text, config.modelId, response.metaInfo.modelId)
    }

    private fun diagnostics(
        stage: String,
        status: Int?,
        body: String,
    ): OpenRouterErrorDiagnostics {
        fun parseObject(value: String): JsonObject? =
            try {
                Json.parseToJsonElement(value) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        val root = parseObject(body)
        val providerError = root?.get("error") as? JsonObject
        val metadata = providerError?.get("metadata") as? JsonObject
        val upstream =
            when (val raw = metadata?.get("raw")) {
                is JsonObject -> raw
                is JsonPrimitive -> raw.contentOrNull?.let(::parseObject)
                else -> null
            }
        val upstreamError = upstream?.get("error") as? JsonObject ?: upstream

        fun JsonObject?.field(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull?.let(::safeDetail)
        return OpenRouterErrorDiagnostics(
            stage = stage,
            httpStatus = status,
            providerCode = providerError.field("code"),
            providerMessage = providerError.field("message"),
            providerName = metadata.field("provider_name"),
            requestId = root.field("request_id") ?: metadata.field("request_id"),
            upstreamCode = upstreamError.field("code"),
            upstreamMessage = upstreamError.field("message"),
        )
    }

    private fun safeDetail(value: String): String {
        val redacted = if (config.apiKey.isNotEmpty()) value.replace(config.apiKey, "[REDACTED]") else value
        return redacted
            .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer [REDACTED]")
            .replace(Regex("sk-or-[A-Za-z0-9_-]+"), "[REDACTED]")
            .let { if (it.length > 4_000) it.take(4_000) + "\n[truncated]" else it }
    }

    private fun classify(
        error: Exception,
        stage: String,
    ): OpenRouterWorkflowException {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull()
        val status = http?.statusCode
        if (status != null) {
            val body = http.errorBody.orEmpty()
            val failure =
                when {
                    status == 401 || status == 403 -> {
                        OpenRouterWorkflowException(
                            FailureKind.AUTHENTICATION,
                            "Authentication failed (HTTP $status). Check $API_KEY_VARIABLE.",
                            error,
                        )
                    }

                    status == 429 -> {
                        OpenRouterWorkflowException(
                            FailureKind.RATE_LIMIT,
                            "Rate limited by OpenRouter or the free provider (HTTP 429). Retry later.",
                            error,
                        )
                    }

                    status == 408 || status == 504 -> {
                        OpenRouterWorkflowException(FailureKind.TIMEOUT, "The request timed out (HTTP $status).", error)
                    }

                    (status == 400 || status == 413 || status == 422) && body.contains("context", ignoreCase = true) -> {
                        OpenRouterWorkflowException(
                            FailureKind.CONTEXT,
                            "The provider rejected the request as exceeding the context limit (HTTP $status).",
                            error,
                        )
                    }

                    else -> {
                        OpenRouterWorkflowException(FailureKind.PROVIDER, "Provider error (HTTP $status).", error)
                    }
                }
            return OpenRouterWorkflowException(
                failure.kind,
                failure.message.orEmpty(),
                error,
                diagnostics(stage, status, body),
            )
        }
        if (chain.any { it.javaClass.simpleName.contains("Timeout", ignoreCase = true) }) {
            return OpenRouterWorkflowException(
                FailureKind.TIMEOUT,
                "The request timed out after a configured limit of ${config.requestTimeoutMillis} ms.",
                error,
                OpenRouterErrorDiagnostics(stage),
            )
        }
        val detail =
            (error as? LLMClientException)
                ?.message
                ?.lineSequence()
                ?.drop(1)
                ?.firstOrNull() ?: error.message.orEmpty()
        // Only Koog's explicit API error text is safe to show; decoding exceptions can contain raw payloads.
        val summary =
            if (detail.startsWith("OpenRouter API error:")) safeDetail(detail) else error.javaClass.simpleName
        val failure =
            when {
                summary.contains("context", ignoreCase = true) -> {
                    OpenRouterWorkflowException(FailureKind.CONTEXT, "Provider reported a context problem: $summary", error)
                }

                summary.contains("rate", ignoreCase = true) -> {
                    OpenRouterWorkflowException(FailureKind.RATE_LIMIT, "Provider reported rate limiting: $summary", error)
                }

                chain.any { it.message?.contains("Empty choices in response") == true } -> {
                    OpenRouterWorkflowException(FailureKind.EMPTY_RESPONSE, "The provider returned no choices.", error)
                }

                else -> {
                    OpenRouterWorkflowException(FailureKind.PROVIDER, "Provider error: $summary", error)
                }
            }
        return OpenRouterWorkflowException(
            failure.kind,
            failure.message.orEmpty(),
            error,
            OpenRouterErrorDiagnostics(stage = stage),
        )
    }
}
