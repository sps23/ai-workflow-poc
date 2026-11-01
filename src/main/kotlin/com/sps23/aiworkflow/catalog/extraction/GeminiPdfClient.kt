package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_BASE_URL
import com.sps23.aiworkflow.openrouter.ModelLimits
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.RequestMetricsRecorder
import com.sps23.aiworkflow.openrouter.elapsedMillis
import com.sps23.aiworkflow.openrouter.fetchGoogleAiModels
import com.sps23.aiworkflow.openrouter.metricsEnvelope
import com.sps23.aiworkflow.openrouter.providerDiagnostics
import com.sps23.aiworkflow.openrouter.redactSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.Base64

const val GEMINI_PDF_DEFAULT_MODEL = "gemini-3.8-flash"
const val GEMINI_PDF_MAX_BYTES = 50L * 1024 * 1024
const val GEMINI_PDF_MAX_OUTPUT_TOKENS = 65_536L

class GeminiPdfConfig(
    val apiKey: String,
    modelId: String = GEMINI_PDF_DEFAULT_MODEL,
    val baseUrl: String = GOOGLE_AI_BASE_URL,
    /** Null omits `temperature` so the model default applies (Google advises 1.0 for Gemini 3). */
    val temperature: Double? = null,
    val requestTimeoutMillis: Long = 600_000,
    val connectTimeoutMillis: Long = 15_000,
) {
    val modelId: String = modelId.trim().removePrefix("models/")

    init {
        if (apiKey.isBlank() || this.modelId.isBlank()) {
            throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Google AI API key and model ID are required.")
        }
    }

    override fun toString(): String = "GeminiPdfConfig(modelId=$modelId, baseUrl=$baseUrl, temperature=$temperature)"
}

data class GeminiUsage(
    val promptTokens: Long?,
    val candidatesTokens: Long?,
    val thoughtsTokens: Long?,
    val totalTokens: Long?,
) {
    /** Billed output: visible candidates plus thinking tokens. */
    val outputTokens: Long get() = (candidatesTokens ?: 0) + (thoughtsTokens ?: 0)
}

data class GeminiPdfResult(
    val text: String,
    val finishReason: String,
    val usage: GeminiUsage,
    val modelVersion: String?,
    val latencyMillis: Long,
    val metrics: RequestMetrics? = null,
)

/** Model call used by [PdfExtractionService]; failures are [OpenRouterWorkflowException]s. */
interface PdfExtractionClient {
    val modelId: String

    /** Verifies the model is available and returns its output-token limit. */
    suspend fun outputTokenLimit(): Long

    suspend fun extract(
        pdf: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        responseSchema: JsonObject?,
        maxOutputTokens: Long,
    ): GeminiPdfResult
}

/**
 * Sends one PDF inline to Gemini's native `generateContent` and returns the JSON text answer.
 *
 * Only `finishReason == STOP` with non-empty text succeeds; truncation, blocking and HTTP errors
 * become [OpenRouterWorkflowException]s with the repository's [FailureKind]s. No retries.
 * The API key travels only in the `x-goog-api-key` header.
 */
class GeminiPdfClient(
    private val config: GeminiPdfConfig,
) : PdfExtractionClient {
    override val modelId: String get() = config.modelId
    private var verifiedLimits: ModelLimits? = null

    override suspend fun outputTokenLimit(): Long =
        fetchGoogleAiModels(config.apiKey, config.baseUrl)
            .firstOrNull { it.id == config.modelId }
            ?.also { verifiedLimits = it.limits }
            ?.outputTokenLimit
            ?: throw OpenRouterWorkflowException(
                FailureKind.MODEL_UNAVAILABLE,
                "Google AI model '${config.modelId}' is not listed for generateContent with this key.",
            )

    fun requestBody(
        pdf: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        responseSchema: JsonObject?,
        maxOutputTokens: Long,
    ): JsonObject {
        checkPdf(pdf)
        require(maxOutputTokens in 1..Int.MAX_VALUE.toLong()) { "maxOutputTokens must be a positive Int." }
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(
                                buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", "application/pdf")
                                        put("data", Base64.getEncoder().encodeToString(pdf))
                                    }
                                },
                            )
                            add(buildJsonObject { put("text", userPrompt) })
                        }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                responseSchema?.let { put("responseJsonSchema", it) }
                put("maxOutputTokens", maxOutputTokens)
                config.temperature?.let { put("temperature", it) }
            }
        }
    }

    override suspend fun extract(
        pdf: ByteArray,
        systemPrompt: String,
        userPrompt: String,
        responseSchema: JsonObject?,
        maxOutputTokens: Long,
    ): GeminiPdfResult {
        val started = System.nanoTime()
        val recorder =
            RequestMetricsRecorder(
                RequestMetrics(
                    api = "Google AI (native generateContent)",
                    requestedModelId = redactSecrets(config.modelId, config.apiKey).take(200),
                    inputUtf8Bytes =
                        pdf.size.toLong() + systemPrompt.toByteArray().size + userPrompt.toByteArray().size +
                            (responseSchema?.toString()?.toByteArray()?.size ?: 0),
                    estimatedInputTokens = null,
                    requestedOutputTokens = GEMINI_PDF_MAX_OUTPUT_TOKENS,
                    effectiveOutputTokens = maxOutputTokens,
                    contextLength = verifiedLimits?.contextLength,
                    modelMaxOutputTokens = verifiedLimits?.maxCompletionTokens,
                ),
            )

        fun snapshot(): RequestMetrics {
            val millis = elapsedMillis(started)
            return recorder.metrics.copy(
                elapsedMillis = millis,
                completionTokensPerSecondOverRequest =
                    recorder.metrics.usage
                        ?.completionTokens
                        ?.takeIf { millis > 0 }
                        ?.let { it * 1000.0 / millis },
            )
        }

        return try {
            val body = requestBody(pdf, systemPrompt, userPrompt, responseSchema, maxOutputTokens).toString()
            withContext(Dispatchers.IO) {
                val http =
                    HttpClient(CIO) {
                        followRedirects = false
                        install(HttpTimeout) {
                            requestTimeoutMillis = config.requestTimeoutMillis
                            socketTimeoutMillis = config.requestTimeoutMillis
                            connectTimeoutMillis = config.connectTimeoutMillis
                        }
                    }
                try {
                    val started = System.nanoTime()
                    recorder.metrics = recorder.metrics.copy(dispatched = true)
                    val response =
                        http.post("${config.baseUrl.trimEnd('/')}/v1beta/models/${config.modelId}:generateContent") {
                            header("x-goog-api-key", config.apiKey)
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    recorder.metrics = recorder.metrics.copy(httpStatus = response.status.value)
                    val responseText = response.bodyAsText()
                    val latency = (System.nanoTime() - started) / 1_000_000
                    recorder.geminiResponse(metricsEnvelope(responseText), response.status.value, config.apiKey)
                    if (!response.status.isSuccess()) throw httpFailure(response.status.value, responseText, config.apiKey)
                    parseResponse(responseText, latency).copy(metrics = snapshot())
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
                        "Gemini PDF extraction failed [${kind.name}].",
                        e,
                        OpenRouterErrorDiagnostics(STAGE),
                    )
                } finally {
                    http.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenRouterWorkflowException) {
            throw OpenRouterWorkflowException(
                e.kind,
                e.message.orEmpty(),
                e,
                e.diagnostics,
                e.rawOutput,
                requestMetrics = snapshot(),
            )
        }
    }

    companion object {
        const val STAGE = "PDF extraction"

        fun checkPdf(pdf: ByteArray) {
            if (pdf.size > GEMINI_PDF_MAX_BYTES) {
                throw OpenRouterWorkflowException(
                    FailureKind.REQUEST_TOO_LARGE,
                    "PDF is ${pdf.size} bytes; Gemini accepts at most $GEMINI_PDF_MAX_BYTES bytes (50 MB).",
                )
            }
            val header = String(pdf, 0, minOf(pdf.size, 5), Charsets.ISO_8859_1)
            if (header != "%PDF-") {
                throw OpenRouterWorkflowException(
                    FailureKind.CONFIGURATION,
                    "Input is not a PDF (missing %PDF- header). Git LFS pointers need `git lfs pull`.",
                )
            }
        }

        /** Parses a successful generateContent body; non-STOP or empty answers throw. */
        fun parseResponse(
            body: String,
            latencyMillis: Long = 0,
        ): GeminiPdfResult {
            val root =
                try {
                    Json.parseToJsonElement(body) as? JsonObject
                } catch (e: SerializationException) {
                    throw OpenRouterWorkflowException(FailureKind.INVALID_RESPONSE, "Gemini response is not valid JSON.", e)
                } ?: throw OpenRouterWorkflowException(FailureKind.INVALID_RESPONSE, "Gemini response root is not an object.")
            val usageObject = root["usageMetadata"] as? JsonObject

            fun count(key: String): Long? = (usageObject?.get(key) as? JsonPrimitive)?.longOrNull
            val usage =
                GeminiUsage(count("promptTokenCount"), count("candidatesTokenCount"), count("thoughtsTokenCount"), count("totalTokenCount"))
            val modelVersion = (root["modelVersion"] as? JsonPrimitive)?.contentOrNull

            val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) {
                throw OpenRouterWorkflowException(FailureKind.INVALID_RESPONSE, "Gemini blocked the request: $blockReason.")
            }
            val candidate =
                (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: throw OpenRouterWorkflowException(FailureKind.EMPTY_RESPONSE, "Gemini returned no candidates.")
            val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            val text =
                parts
                    .mapNotNull { it as? JsonObject }
                    .filterNot { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
                    .mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                    .joinToString("")
            val finishReason = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull ?: "MISSING"
            when {
                finishReason == "MAX_TOKENS" -> {
                    throw OpenRouterWorkflowException(
                        FailureKind.TRUNCATED_RESPONSE,
                        "Gemini stopped at the output token limit (output tokens ${usage.outputTokens}, " +
                            "thinking ${usage.thoughtsTokens ?: 0}); the JSON is incomplete.",
                        rawOutput = text,
                    )
                }

                finishReason != "STOP" -> {
                    throw OpenRouterWorkflowException(
                        FailureKind.INVALID_RESPONSE,
                        "Gemini finished with $finishReason instead of STOP.",
                        rawOutput = text.ifEmpty { null },
                    )
                }

                text.isBlank() -> {
                    throw OpenRouterWorkflowException(FailureKind.EMPTY_RESPONSE, "Gemini returned no text.")
                }
            }
            return GeminiPdfResult(text, finishReason, usage, modelVersion, latencyMillis)
        }

        internal fun httpFailure(
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

                    status == 413 -> {
                        FailureKind.REQUEST_TOO_LARGE
                    }

                    status == 404 -> {
                        FailureKind.MODEL_UNAVAILABLE
                    }

                    status == 429 -> {
                        FailureKind.RATE_LIMIT
                    }

                    status == 408 || status == 504 -> {
                        FailureKind.TIMEOUT
                    }

                    else -> {
                        FailureKind.PROVIDER
                    }
                }
            return OpenRouterWorkflowException(
                kind,
                "Gemini PDF extraction failed (HTTP $status).",
                diagnostics = providerDiagnostics(STAGE, status, body, key),
            )
        }
    }
}
