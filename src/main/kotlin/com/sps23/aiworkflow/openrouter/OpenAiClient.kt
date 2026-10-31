package com.sps23.aiworkflow.openrouter

import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

const val OPENAI_BASE_URL = "https://api.openai.com"
const val OPENAI_API_KEY_VARIABLE = "OPENAI_API_KEY"
const val OPENAI_MAX_COMPLETION_TOKENS_VARIABLE = "OPENAI_MAX_COMPLETION_TOKENS"
private const val OPENAI_CONNECT_TIMEOUT_MILLIS = 15_000L

class OpenAiConfig(
    val apiKey: String,
    val modelId: String,
    val baseUrl: String = OPENAI_BASE_URL,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val maxCompletionTokens: Long = 32_768,
) {
    init {
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "OpenAI completion budget must be a positive Int." }
    }

    override fun toString(): String = "OpenAiConfig(modelId=$modelId, baseUrl=$baseUrl)"

    companion object {
        fun apiKey(
            environment: Map<String, String?>,
            override: String?,
        ): String =
            override?.trim()?.takeIf { it.isNotEmpty() }
                ?: environment[OPENAI_API_KEY_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OpenRouterWorkflowException(
                    FailureKind.CONFIGURATION,
                    "Enter an OpenAI API key or set $OPENAI_API_KEY_VARIABLE.",
                )

        fun fromEnvironment(
            environment: Map<String, String?>,
            modelId: String,
            apiKeyOverride: String? = null,
        ): OpenAiConfig {
            if (modelId.isBlank()) {
                throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Select or type an OpenAI model ID.")
            }
            return OpenAiConfig(
                apiKey(environment, apiKeyOverride),
                modelId.trim(),
                maxCompletionTokens = completionTokenBudget(environment, OPENAI_MAX_COMPLETION_TOKENS_VARIABLE, 32_768),
            )
        }
    }
}

/** The model-list endpoint reports IDs, not endpoint compatibility, prices or token limits. */
fun parseOpenAiModels(json: String): List<String> {
    val root =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "OpenAI model metadata is not valid JSON.", e)
        }
    val data =
        root?.get("data") as? JsonArray
            ?: throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "OpenAI model metadata has no model list.")
    val ids =
        data
            .map { entry ->
                val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                id?.takeIf { it.isNotBlank() }
                    ?: throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "OpenAI model metadata contains an invalid ID.")
            }.distinct()
            .sorted()
    if (ids.isEmpty()) throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "OpenAI returned no available models.")
    return ids
}

suspend fun fetchOpenAiModels(
    apiKey: String,
    baseUrl: String = OPENAI_BASE_URL,
    manual: Boolean = false,
): List<String> =
    withContext(Dispatchers.IO) {
        if (!manual &&
            apiKey.isBlank()
        ) {
            throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Enter an OpenAI API key to fetch models.")
        }
        val http =
            HttpClient(CIO) {
                if (manual) followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = OPENAI_CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = 30_000
                }
            }
        try {
            val response =
                http.get("${baseUrl.trimEnd('/')}/${if (manual) "models" else "v1/models"}") {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw openAiStatusFailure(response.status.value, body, apiKey, "Model metadata")
            parseOpenAiModels(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenRouterWorkflowException) {
            throw e
        } catch (e: Exception) {
            throw openAiTransportFailure(e, apiKey, "Model metadata")
        } finally {
            http.close()
        }
    }

/** Koog inference with exact IDs; OpenAI, not a fabricated metadata limit, validates model/context compatibility. */
class OpenAiClient(
    private val config: OpenAiConfig,
) : ChatBackend {
    override val maxCompletionTokens: Long get() = config.maxCompletionTokens

    override suspend fun fetchLimits(): ModelLimits? = null

    override suspend fun complete(
        userMessage: String,
        systemPrompt: String?,
        context: String?,
        limits: ModelLimits?,
        maxCompletionTokens: Long,
    ): Completion {
        require(userMessage.isNotBlank()) { "Prompt must not be blank." }
        if (config.apiKey.isBlank() || config.modelId.isBlank()) {
            throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "OpenAI API key and model ID are required.")
        }
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "Completion token budget must be a positive Int." }
        val message = context?.let { buildUserMessage(userMessage, it) } ?: userMessage
        return measuredCompletion("OpenAI", config.modelId, systemPrompt.orEmpty(), message, maxCompletionTokens) { recorder ->
            recorder.limits(null, maxCompletionTokens)
            val model =
                LLModel(
                    LLMProvider.OpenAI,
                    config.modelId,
                    capabilities = listOf(LLMCapability.Completion, LLMCapability.OpenAIEndpoint.Completions),
                )
            val request =
                prompt("openai-completion", params = OpenAIChatParams(maxTokens = maxCompletionTokens.toInt(), store = false)) {
                    systemPrompt?.let { system(it) }
                    user(message)
                }
            val transport =
                HttpClient(CIO) {
                    HttpResponseValidator {
                        validateResponse { response ->
                            recorder.response(metricsEnvelope(response.bodyAsText()), response.status.value, config.apiKey)
                        }
                    }
                }
            val client =
                OpenAILLMClient(
                    apiKey = config.apiKey,
                    settings =
                        OpenAIClientSettings(
                            baseUrl = config.baseUrl.trimEnd('/'),
                            timeoutConfig =
                                ConnectionTimeoutConfig(
                                    requestTimeoutMillis = config.requestTimeoutMillis,
                                    connectTimeoutMillis = OPENAI_CONNECT_TIMEOUT_MILLIS,
                                    socketTimeoutMillis = config.requestTimeoutMillis,
                                ),
                        ),
                    httpClientFactory = KtorKoogHttpClient.Factory(baseClient = transport),
                )
            val response =
                try {
                    recorder.metrics = recorder.metrics.copy(dispatched = true)
                    client.execute(request, model, emptyList())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw openAiTransportFailure(e, config.apiKey, "Chat completion")
                } finally {
                    client.close()
                    transport.close()
                }
            val text = response.textContent()
            if (response.finishReason == "length") {
                throw OpenRouterWorkflowException(
                    FailureKind.TRUNCATED_RESPONSE,
                    "OpenAI response was truncated at the $maxCompletionTokens-token output/reasoning budget. " +
                        "Model context/output limits are unavailable from the model-list API.",
                    diagnostics = OpenRouterErrorDiagnostics("Response validation"),
                    rawOutput = text,
                )
            }
            if (text.isBlank()) {
                throw OpenRouterWorkflowException(
                    FailureKind.EMPTY_RESPONSE,
                    "OpenAI returned an empty response.",
                    diagnostics = OpenRouterErrorDiagnostics("Response validation"),
                )
            }
            Completion(text, config.modelId, recorder.metrics.responseModelId ?: response.metaInfo.modelId)
        }
    }
}

private fun openAiStatusFailure(
    status: Int,
    body: String,
    key: String,
    stage: String,
): OpenRouterWorkflowException {
    val kind =
        when {
            status == 401 || status == 403 -> FailureKind.AUTHENTICATION
            status == 429 -> FailureKind.RATE_LIMIT
            status == 408 || status == 504 -> FailureKind.TIMEOUT
            status == 404 -> FailureKind.MODEL_UNAVAILABLE
            status in 400..499 && body.contains("context", ignoreCase = true) -> FailureKind.CONTEXT
            else -> FailureKind.PROVIDER
        }
    return OpenRouterWorkflowException(
        kind,
        "OpenAI request failed (HTTP $status).",
        diagnostics = providerDiagnostics(stage, status, body, key),
    )
}

private fun openAiTransportFailure(
    error: Exception,
    key: String,
    stage: String,
): OpenRouterWorkflowException {
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull()
    http?.statusCode?.let { return openAiStatusFailure(it, http.errorBody.orEmpty(), key, stage) }
    val kind =
        when {
            chain.any { it.javaClass.simpleName.contains("Timeout", ignoreCase = true) } -> FailureKind.TIMEOUT
            chain.any { it is ConnectException || it is UnresolvedAddressException } -> FailureKind.CONNECTION
            chain.any { it.message?.contains("Empty choices in response") == true } -> FailureKind.EMPTY_RESPONSE
            else -> FailureKind.PROVIDER
        }
    return OpenRouterWorkflowException(
        kind,
        "OpenAI request failed [${kind.name}].",
        error,
        OpenRouterErrorDiagnostics(stage),
    )
}
