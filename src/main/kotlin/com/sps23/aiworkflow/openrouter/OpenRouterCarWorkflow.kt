package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.logging.WorkspaceLogs
import com.sps23.aiworkflow.prompts.AppPrompts
import com.sps23.aiworkflow.recommendation.CarSelectionCodec
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.LlmRequestCodec
import com.sps23.aiworkflow.recommendation.REQUEST_TRANSLATION_INSTRUCTIONS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val OPENROUTER_BASE_URL = "https://openrouter.ai"
const val API_KEY_VARIABLE = "OPENROUTER_API_KEY"
const val MODEL_VARIABLE = "OPENROUTER_MODEL"
const val MAX_COMPLETION_TOKENS_VARIABLE = "OPENROUTER_MAX_COMPLETION_TOKENS"
const val PAID_QWEN_MODEL_ID = "qwen/qwen3.5-flash-02-23"

/** Conservative input-size heuristic: one token per two UTF-8 bytes. Real tokenizers differ by model. */
const val ESTIMATED_BYTES_PER_TOKEN = 2L

/** Fixed allowance for per-message framing overhead in the size estimate. */
const val MESSAGE_OVERHEAD_TOKENS = 256L

const val DEFAULT_LOCAL_MAX_COMPLETION_TOKENS = 32_768L
const val DEFAULT_OPENROUTER_MAX_COMPLETION_TOKENS = 32_768L

// Full-catalog inference on free providers can take several minutes before returning text.
const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 600_000L

val SYSTEM_INSTRUCTIONS: String get() = AppPrompts.recommendation

class OpenRouterConfig(
    val apiKey: String,
    val modelId: String,
    val baseUrl: String = OPENROUTER_BASE_URL,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val maxCompletionTokens: Long = DEFAULT_OPENROUTER_MAX_COMPLETION_TOKENS,
) {
    init {
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) {
            "OpenRouter maxCompletionTokens must be an integer between 1 and ${Int.MAX_VALUE}."
        }
    }

    override fun toString(): String = "OpenRouterConfig(modelId=$modelId, baseUrl=$baseUrl)"

    companion object {
        fun fromEnvironment(
            environment: Map<String, String?>,
            baseUrl: String = OPENROUTER_BASE_URL,
        ): OpenRouterConfig {
            fun required(name: String) =
                environment[name]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw OpenRouterWorkflowException(
                        FailureKind.CONFIGURATION,
                        "Environment variable $name is not set.",
                    )
            val completionTokens =
                completionTokenBudget(environment, MAX_COMPLETION_TOKENS_VARIABLE, DEFAULT_OPENROUTER_MAX_COMPLETION_TOKENS)
            return OpenRouterConfig(required(API_KEY_VARIABLE), required(MODEL_VARIABLE), baseUrl, maxCompletionTokens = completionTokens)
        }
    }
}

internal fun completionTokenBudget(
    environment: Map<String, String?>,
    variable: String,
    default: Long,
): Long =
    environment[variable]?.let { raw ->
        raw.trim().toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?: throw OpenRouterWorkflowException(
                FailureKind.CONFIGURATION,
                "$variable must be an integer between 1 and ${Int.MAX_VALUE}.",
            )
    } ?: default

enum class FailureKind {
    CONFIGURATION,
    MODEL_UNAVAILABLE,
    REQUEST_TOO_LARGE,
    AUTHENTICATION,
    RATE_LIMIT,
    TIMEOUT,
    CONTEXT,
    PROVIDER,
    EMPTY_RESPONSE,
    TRUNCATED_RESPONSE,
    INVALID_RESPONSE,
    CONNECTION,
}

class OpenRouterWorkflowException(
    val kind: FailureKind,
    message: String,
    cause: Throwable? = null,
    val diagnostics: OpenRouterErrorDiagnostics? = null,
    /** Model-authored content; workspace diagnostic logs redact credentials before persistence. */
    val rawOutput: String? = null,
    val requestMetrics: RequestMetrics? = null,
    val metadata: WorkflowMetadata? = null,
) : Exception(message, cause)

data class OpenRouterErrorDiagnostics(
    val stage: String,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    val providerMessage: String? = null,
    val providerName: String? = null,
    val requestId: String? = null,
    val upstreamCode: String? = null,
    val upstreamMessage: String? = null,
)

data class CarAnswer(
    val text: String,
    val requestedModelId: String,
    /** Model ID reported by the provider response, when Koog exposes it. */
    val responseModelId: String?,
    val metadata: WorkflowMetadata? = null,
) {
    val selection = CarSelectionCodec.decode(text)
}

data class ModelLimits(
    val contextLength: Long,
    val maxCompletionTokens: Long?,
    val modelId: String? = null,
)

/** Builds the single user message: request and catalog are delimited data blocks. */
fun buildUserMessage(
    prompt: String,
    catalogJson: String,
): String {
    fun String.defang(tag: String) = replace("</$tag>", "<\\/$tag>")
    return "<user_request>\n${prompt.defang("user_request").defang("catalog_json")}\n</user_request>\n" +
        "<catalog_json>\n$catalogJson\n</catalog_json>"
}

fun estimateInputTokens(
    system: String,
    user: String,
): Long {
    val bytes = system.toByteArray().size.toLong() + user.toByteArray().size
    return (bytes + ESTIMATED_BYTES_PER_TOKEN - 1) / ESTIMATED_BYTES_PER_TOKEN + 2 * MESSAGE_OVERHEAD_TOKENS
}

fun validateRequestSize(
    system: String,
    user: String,
    limits: ModelLimits,
    completionTokens: Long,
) {
    val inputTokens = estimateInputTokens(system, user)
    if (inputTokens > limits.contextLength - completionTokens) {
        val bytes = system.toByteArray().size.toLong() + user.toByteArray().size
        throw OpenRouterWorkflowException(
            FailureKind.REQUEST_TOO_LARGE,
            "Request does not fit: $bytes UTF-8 input bytes; estimated $inputTokens input tokens + " +
                "$completionTokens completion tokens exceeds the model context of ${limits.contextLength} tokens. " +
                "The estimate uses 1 token per 2 UTF-8 bytes plus message overhead, not a tokenizer count, " +
                "and may reject requests that the tokenizer could fit. No chat completion was sent.",
            diagnostics = OpenRouterErrorDiagnostics("Request size validation"),
        )
    }
}

/**
 * Checks exact listing, text output, nonnegative pricing and limits.
 * IDs advertised as free still require zero pricing; unverifiable data is rejected.
 */
fun verifyOpenRouterModel(
    modelsJson: String,
    modelId: String,
): ModelLimits {
    fun reject(reason: String): Nothing =
        throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Model $modelId rejected: $reason")
    if (modelId.isBlank()) reject("ID must not be blank.")
    val data = openRouterModelData(modelsJson)
    val model =
        data.map { it as? JsonObject }.firstOrNull { (it?.get("id") as? JsonPrimitive)?.contentOrNull == modelId }
            ?: reject("not listed by OpenRouter.")

    return verifiedOpenRouterModel(model).limits
}

internal fun openRouterModelData(modelsJson: String): JsonArray =
    try {
        Json.parseToJsonElement(modelsJson).jsonObject["data"]?.jsonArray
            ?: throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Model metadata response has no model list.")
    } catch (e: IllegalArgumentException) {
        throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Model metadata response is not valid JSON.", e)
    }

internal fun verifiedOpenRouterModel(model: JsonObject): OpenRouterModel {
    val modelId = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    fun reject(reason: String): Nothing =
        throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "Model $modelId rejected: $reason")
    if (modelId.isBlank()) reject("ID must not be blank.")
    val outputs = (model["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray
    if (outputs == null || outputs.none { (it as? JsonPrimitive)?.contentOrNull == "text" }) reject("text output not confirmed.")

    val pricing = model["pricing"] as? JsonObject ?: reject("pricing unavailable.")
    if ("prompt" !in pricing || "completion" !in pricing) reject("prompt/completion pricing unavailable.")
    var isFree = true
    pricing.forEach { (name, value) ->
        if (name == "discount") return@forEach
        val amount =
            (value as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
                ?: reject("pricing field $name is not a valid number.")
        if (amount.signum() < 0) reject("pricing field $name is negative.")
        if (amount.signum() != 0) isFree = false
        if ((modelId.endsWith(":free") || modelId == "openrouter/free") && amount.signum() != 0) {
            reject("pricing field $name is not zero.")
        }
    }

    val topProvider = model["top_provider"] as? JsonObject
    val context =
        topProvider.long("context_length") ?: model.long("context_length")
            ?: reject("context length unavailable.")
    return OpenRouterModel(
        id = modelId,
        name = (model["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: modelId,
        isFree = isFree,
        promptPrice = pricing.getValue("prompt").jsonPrimitive.content,
        completionPrice = pricing.getValue("completion").jsonPrimitive.content,
        limits = ModelLimits(context, topProvider.long("max_completion_tokens")),
    )
}

private fun JsonObject?.long(name: String): Long? = (this?.get(name) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

/** Runs the two-stage car workflow against any [ChatBackend] (OpenRouter or a local server). */
class OpenRouterCarWorkflow(
    private val client: ChatBackend,
    private val catalogJson: String,
    private val logs: WorkspaceLogs? = null,
    private val requestedModel: String? = null,
    private val api: String? = null,
) {
    constructor(config: OpenRouterConfig, catalogJson: String) : this(OpenRouterClient(config), catalogJson)

    constructor(config: LocalLlmConfig, catalogJson: String) : this(LocalLlmClient(config), catalogJson)

    /** Two bounded calls: translate without catalog, then recommend with the supplied catalog. */
    suspend fun answer(
        userPrompt: String,
        cachedRequest: LlmRequest? = null,
        onTranslated: suspend (LlmRequest) -> Unit = {},
    ): CarAnswer {
        require(userPrompt.isNotBlank()) { "Prompt must not be blank." }
        val started = System.nanoTime()
        val workflowId =
            java.util.UUID
                .randomUUID()
                .toString()
        val stages = mutableListOf<StageMetrics>()
        var activeRequest: RequestMetrics? = null

        fun metadata() = WorkflowMetadata(elapsedMillis = elapsedMillis(started), stages = stages.toList())

        suspend fun <T> stage(
            name: String,
            metricName: String = name,
            action: suspend () -> T,
        ): T {
            val stageStarted = System.nanoTime()
            activeRequest = null
            return try {
                action().also {
                    stages += StageMetrics(metricName, "SUCCEEDED", elapsedMillis(stageStarted), request = activeRequest)
                }
            } catch (e: OpenRouterWorkflowException) {
                val request = e.requestMetrics ?: activeRequest
                stages +=
                    StageMetrics(
                        metricName,
                        "FAILED",
                        elapsedMillis(stageStarted),
                        e.kind.name,
                        request,
                        e.diagnostics?.httpStatus ?: request?.httpStatus,
                    )
                throw OpenRouterWorkflowException(
                    e.kind,
                    e.message.orEmpty(),
                    e,
                    e.diagnostics?.let { it.copy(stage = "$name / ${it.stage}") } ?: OpenRouterErrorDiagnostics(name),
                    rawOutput = e.rawOutput,
                    requestMetrics = request,
                    metadata = metadata(),
                )
            }
        }
        val validatedCache = cachedRequest?.let { LlmRequestCodec.decode(LlmRequestCodec.encode(it), userPrompt) }
        var limits: ModelLimits? = null
        var limitsLoaded = false

        suspend fun <T> inference(
            name: String,
            user: String,
            system: String,
            context: String?,
            outputTokens: Long,
            decode: (Completion) -> T,
        ): T {
            suspend fun run(operation: WorkspaceLogs.Operation?): T {
                if (!limitsLoaded) {
                    limits = stage(name, "Model metadata") { client.fetchLimits() }
                    limitsLoaded = true
                }
                return stage(name) {
                    val completion = client.complete(user, system, context, limits, outputTokens)
                    activeRequest = completion.metrics
                    operation?.response(completion.text)
                    operation?.event(
                        "METADATA",
                        buildJsonObject {
                            put("requestedModel", completion.requestedModelId)
                            put("responseModel", completion.responseModelId)
                            put("requestMetrics", completion.metrics?.let { WorkspaceLogs.detailJson.encodeToJsonElement(it) } ?: JsonNull)
                        },
                    )
                    val result = decode(completion)
                    operation?.event(
                        "VALIDATED",
                        buildJsonObject {
                            put(
                                "performanceMetadata",
                                WorkspaceLogs.detailJson.encodeToJsonElement(
                                    metadata().copy(
                                        stages =
                                            stages +
                                                StageMetrics(
                                                    name,
                                                    "SUCCEEDED",
                                                    completion.metrics?.elapsedMillis ?: 0,
                                                    request = completion.metrics,
                                                ),
                                    ),
                                ),
                            )
                        },
                    )
                    result
                }
            }
            return if (logs == null) {
                run(null)
            } else {
                logs.record(
                    "assistant",
                    name,
                    buildJsonObject {
                        put("api", api)
                        put("workflowId", workflowId)
                        put("requestedModel", requestedModel)
                        put("systemPrompt", system)
                        put("userMessage", user)
                        put("requestJson", WorkspaceLogs.parseJson(user))
                        put("catalogJson", context?.let(WorkspaceLogs::parseJson) ?: JsonNull)
                        put("catalogText", context)
                        put("requestedOutputTokens", outputTokens)
                        put("translationReused", validatedCache != null)
                    },
                ) { run(it) }
            }
        }
        if (validatedCache != null) stages += StageMetrics("Request translation", "REUSED", 0)
        val translated =
            validatedCache ?: inference(
                "Request translation",
                userPrompt,
                REQUEST_TRANSLATION_INSTRUCTIONS,
                null,
                minOf(client.maxCompletionTokens, 2048),
            ) { completion ->
                LlmRequestCodec.decode(completion.text, userPrompt)
            }
        onTranslated(translated)
        val answer =
            inference(
                "Car recommendation",
                LlmRequestCodec.encode(translated),
                SYSTEM_INSTRUCTIONS,
                catalogJson,
                client.maxCompletionTokens,
            ) { completion ->
                CarAnswer(completion.text, completion.requestedModelId, completion.responseModelId)
            }
        return answer.copy(metadata = metadata())
    }
}
