package com.sps23.aiworkflow.openrouter

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

private const val LOCAL_CONNECT_TIMEOUT_MILLIS = 15_000L
const val LOCAL_MAX_COMPLETION_TOKENS_VARIABLE = "LOCAL_AI_MAX_COMPLETION_TOKENS"

class LocalLlmConfig(
    val api: LlmApi,
    /** Optional; sent as a Bearer token only when non-blank. */
    val apiKey: String?,
    val baseUrl: String = api.baseUrl,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val maxCompletionTokens: Long = DEFAULT_LOCAL_MAX_COMPLETION_TOKENS,
) {
    init {
        require(api.isLocal) { "${api.label} is not a local API." }
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) {
            "Local maxCompletionTokens must be an integer between 1 and ${Int.MAX_VALUE}."
        }
    }

    override fun toString(): String = "LocalLlmConfig(api=$api, baseUrl=$baseUrl)"

    companion object {
        fun fromEnvironment(
            environment: Map<String, String?>,
            api: LlmApi,
            baseUrl: String = api.baseUrl,
        ) = LocalLlmConfig(
            api,
            environment[LOCAL_API_KEY_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() },
            baseUrl,
            maxCompletionTokens =
                completionTokenBudget(
                    environment,
                    LOCAL_MAX_COMPLETION_TOKENS_VARIABLE,
                    DEFAULT_LOCAL_MAX_COMPLETION_TOKENS,
                ),
        )
    }
}

/**
 * Minimal OpenAI-compatible client for a locally running server (LM Studio, llama.cpp, Ollama).
 * LM Studio reports the loaded model/context via its native metadata endpoint; pin that model
 * and check the request budget. Other local presets retain their server-selected model behavior.
 */
class LocalLlmClient(
    private val config: LocalLlmConfig,
) : ChatBackend {
    override val maxCompletionTokens: Long get() = config.maxCompletionTokens

    private val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
    private val requestedModelLabel = "${config.api.label} loaded model"

    override suspend fun fetchLimits(): ModelLimits? {
        if (config.api != LlmApi.LM_STUDIO) return null
        val http =
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = config.requestTimeoutMillis
                    connectTimeoutMillis = LOCAL_CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = config.requestTimeoutMillis
                }
            }
        try {
            val metadataUrl = "${config.baseUrl.trimEnd('/').removeSuffix("/v1")}/api/v0/models"
            val response =
                http.get(metadataUrl) {
                    config.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw statusFailure(response.status.value, body)
            val models =
                Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?: throw IllegalArgumentException("Missing model list.")
            val loaded =
                models.map { it.jsonObject }.filter {
                    (it["state"] as? JsonPrimitive)?.contentOrNull == "loaded" &&
                        (it["type"] as? JsonPrimitive)?.contentOrNull in setOf("llm", "vlm")
                }
            if (loaded.size != 1) {
                throw OpenRouterWorkflowException(
                    FailureKind.MODEL_UNAVAILABLE,
                    "LM Studio must have exactly one loaded chat model; found ${loaded.size}. " +
                        "Load one model and unload other chat models before sending.",
                )
            }
            val model = loaded.single()
            val id = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            val context = (model["loaded_context_length"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            if (id == null || context == null) {
                throw OpenRouterWorkflowException(
                    FailureKind.MODEL_UNAVAILABLE,
                    "LM Studio did not report a model ID and positive loaded_context_length. " +
                        "Update LM Studio and reload the model; advertised max_context_length alone is insufficient.",
                )
            }
            return ModelLimits(context, null, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure =
                if (e is OpenRouterWorkflowException) {
                    e
                } else if (e is IllegalArgumentException) {
                    OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "LM Studio model metadata is invalid.", e)
                } else {
                    classify(e)
                }
            throw OpenRouterWorkflowException(
                failure.kind,
                failure.message.orEmpty(),
                failure,
                failure.diagnostics?.copy(stage = "Model metadata") ?: OpenRouterErrorDiagnostics("Model metadata"),
            )
        } finally {
            http.close()
        }
    }

    override suspend fun complete(
        userMessage: String,
        systemPrompt: String?,
        context: String?,
        limits: ModelLimits?,
        maxCompletionTokens: Long,
    ): Completion {
        require(userMessage.isNotBlank()) { "Prompt must not be blank." }
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "Completion token budget must be a positive Int." }
        val message = context?.let { buildUserMessage(userMessage, it) } ?: userMessage
        return measuredCompletion(config.api.label, requestedModelLabel, systemPrompt.orEmpty(), message, maxCompletionTokens) { recorder ->
            val modelLimits = limits ?: fetchLimits()
            recorder.limits(modelLimits)
            val inputTokens = estimateInputTokens(systemPrompt.orEmpty(), message)
            val targetTokens = minOf(maxCompletionTokens, modelLimits?.maxCompletionTokens ?: Long.MAX_VALUE)
            val completionTokens =
                if (modelLimits == null) {
                    targetTokens
                } else {
                    val remaining = modelLimits.contextLength - inputTokens
                    if (remaining <= 0) validateRequestSize(systemPrompt.orEmpty(), message, modelLimits, targetTokens)
                    minOf(targetTokens, remaining)
                }
            recorder.limits(modelLimits, completionTokens)
            modelLimits?.let { validateRequestSize(systemPrompt.orEmpty(), message, it, completionTokens) }
            val body =
                buildJsonObject {
                    modelLimits?.modelId?.let { put("model", it) }
                    putJsonArray("messages") {
                        systemPrompt?.let {
                            addJsonObject {
                                put("role", "system")
                                put("content", it)
                            }
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", message)
                        }
                    }
                    put("temperature", 0.2)
                    put("max_tokens", completionTokens)
                    put("stream", false)
                }.toString()

            val http =
                HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = config.requestTimeoutMillis
                        connectTimeoutMillis = LOCAL_CONNECT_TIMEOUT_MILLIS
                        socketTimeoutMillis = config.requestTimeoutMillis
                    }
                }
            val (status, responseText) =
                try {
                    recorder.metrics = recorder.metrics.copy(dispatched = true)
                    val response =
                        http.post(url) {
                            config.apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                            setBody(TextContent(body, ContentType.Application.Json))
                        }
                    response.status.value to response.bodyAsText()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw classify(e)
                } finally {
                    http.close()
                }
            recorder.response(metricsEnvelope(responseText), status, config.apiKey)
            if (status !in 200..299) throw statusFailure(status, responseText)

            val (first, responseModel) =
                try {
                    val root = Json.parseToJsonElement(responseText).jsonObject
                    root["choices"]?.jsonArray?.firstOrNull()?.jsonObject to (root["model"] as? JsonPrimitive)?.contentOrNull
                } catch (e: Exception) {
                    throw OpenRouterWorkflowException(
                        FailureKind.PROVIDER,
                        "${config.api.label} returned a response that is not OpenAI-compatible chat JSON.",
                        e,
                        OpenRouterErrorDiagnostics("Chat completion", status),
                    )
                }
            first ?: throw OpenRouterWorkflowException(
                FailureKind.EMPTY_RESPONSE,
                "${config.api.label} returned no choices.",
                diagnostics = OpenRouterErrorDiagnostics("Response validation"),
            )
            val text = ((first["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if ((first["finish_reason"] as? JsonPrimitive)?.contentOrNull == "length") {
                throw OpenRouterWorkflowException(
                    FailureKind.TRUNCATED_RESPONSE,
                    "The model response was truncated at the $completionTokens-token output limit " +
                        "(requested target: $maxCompletionTokens; estimated input: $inputTokens; loaded context: " +
                        "${modelLimits?.contextLength ?: "not reported"}; model output maximum: " +
                        "${modelLimits?.maxCompletionTokens ?: "not reported"}). " +
                        "The budget is adjusted to estimated remaining context and may also include reasoning tokens.",
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
            Completion(text, modelLimits?.modelId ?: requestedModelLabel, responseModel)
        }
    }

    private fun statusFailure(
        status: Int,
        body: String,
    ): OpenRouterWorkflowException {
        val (kind, message) =
            when {
                status == 401 || status == 403 -> {
                    FailureKind.AUTHENTICATION to "Authentication failed (HTTP $status). Check $LOCAL_API_KEY_VARIABLE."
                }

                status == 429 -> {
                    FailureKind.RATE_LIMIT to "${config.api.label} is busy (HTTP 429). Retry later."
                }

                status == 408 || status == 504 -> {
                    FailureKind.TIMEOUT to "The request timed out (HTTP $status)."
                }

                status in 400..499 && body.contains("context", ignoreCase = true) -> {
                    FailureKind.CONTEXT to
                        "${config.api.label} rejected the request as exceeding the loaded model's context (HTTP $status)."
                }

                else -> {
                    FailureKind.PROVIDER to "${config.api.label} error (HTTP $status)."
                }
            }
        return OpenRouterWorkflowException(
            kind,
            message,
            diagnostics = providerDiagnostics("Chat completion", status, body, config.apiKey),
        )
    }

    private fun classify(error: Exception): OpenRouterWorkflowException {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val diagnostics = OpenRouterErrorDiagnostics("Chat completion")
        return when {
            chain.any { it.javaClass.simpleName.contains("Timeout", ignoreCase = true) } -> {
                OpenRouterWorkflowException(
                    FailureKind.TIMEOUT,
                    "The request timed out after a configured limit of ${config.requestTimeoutMillis} ms.",
                    error,
                    diagnostics,
                )
            }

            chain.any { it is ConnectException || it is UnresolvedAddressException } -> {
                OpenRouterWorkflowException(
                    FailureKind.CONNECTION,
                    "Could not connect to ${config.api.label} at ${config.baseUrl}.",
                    error,
                    diagnostics,
                )
            }

            else -> {
                OpenRouterWorkflowException(
                    FailureKind.PROVIDER,
                    "${config.api.label} request failed: ${error.javaClass.simpleName}",
                    error,
                    diagnostics,
                )
            }
        }
    }
}
