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
                )
            }
            return verifyFreeModel(response.bodyAsText(), config.modelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenRouterWorkflowException) {
            throw e
        } catch (e: Exception) {
            throw classify(e)
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
                throw classify(e)
            } finally {
                client.close()
            }

        val text = response.textContent().trim()
        if (text.isEmpty()) {
            throw OpenRouterWorkflowException(FailureKind.EMPTY_RESPONSE, "The model returned an empty response.")
        }
        if (response.finishReason == "length") {
            throw OpenRouterWorkflowException(
                FailureKind.TRUNCATED_RESPONSE,
                "The model response was truncated at the $completionTokens-token output limit.",
            )
        }
        return Completion(text, config.modelId, response.metaInfo.modelId)
    }

    private fun classify(error: Exception): OpenRouterWorkflowException {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull()
        val status = http?.statusCode
        if (status != null) {
            val body = http.errorBody.orEmpty()
            return when {
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
        }
        if (chain.any { it.javaClass.simpleName.contains("Timeout", ignoreCase = true) }) {
            return OpenRouterWorkflowException(FailureKind.TIMEOUT, "The request timed out.", error)
        }
        val detail =
            (error as? LLMClientException)
                ?.message
                ?.lineSequence()
                ?.drop(1)
                ?.firstOrNull() ?: error.message.orEmpty()
        val summary = detail.take(300)
        return when {
            summary.contains("context", ignoreCase = true) -> {
                OpenRouterWorkflowException(FailureKind.CONTEXT, "Provider reported a context problem: $summary", error)
            }

            summary.contains("rate", ignoreCase = true) -> {
                OpenRouterWorkflowException(FailureKind.RATE_LIMIT, "Provider reported rate limiting: $summary", error)
            }

            summary.contains("Empty choices", ignoreCase = true) -> {
                OpenRouterWorkflowException(FailureKind.EMPTY_RESPONSE, "The provider returned no choices.", error)
            }

            else -> {
                OpenRouterWorkflowException(FailureKind.PROVIDER, "Provider error: $summary", error)
            }
        }
    }
}
