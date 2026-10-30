package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class TokenUsage(
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val cachedPromptTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val cost: Double? = null,
)

@Serializable
data class RequestMetrics(
    val api: String,
    val requestedModelId: String,
    val responseModelId: String? = null,
    val provider: String? = null,
    val responseId: String? = null,
    val inputUtf8Bytes: Long,
    val estimatedInputTokens: Long,
    val requestedOutputTokens: Long,
    val effectiveOutputTokens: Long? = null,
    val contextLength: Long? = null,
    val modelMaxOutputTokens: Long? = null,
    val dispatched: Boolean = false,
    val httpStatus: Int? = null,
    val finishReason: String? = null,
    val elapsedMillis: Long = 0,
    val usage: TokenUsage? = null,
    val completionTokensPerSecondOverRequest: Double? = null,
    val reportedGenerationTokensPerSecond: Double? = null,
    val reportedTimeToFirstTokenSeconds: Double? = null,
)

@Serializable
data class StageMetrics(
    val stage: String,
    val outcome: String,
    val elapsedMillis: Long,
    val failureKind: String? = null,
    val request: RequestMetrics? = null,
    val httpStatus: Int? = null,
)

@Serializable
data class WorkflowMetadata(
    val schemaVersion: Int = 1,
    val elapsedMillis: Long,
    val stages: List<StageMetrics>,
    val notes: String =
        "Metadata only; null means unavailable. Input estimates use 1 token per 2 UTF-8 bytes plus message overhead, " +
            "not a tokenizer. Request elapsed time includes client/transport/body processing, not just generation. " +
            "Completion tokens per second over request is not generation speed. Reasoning/cached tokens are subsets, " +
            "not additional totals. Cost is provider-reported, not calculated. Reused translation incurs no new inference.",
)

object WorkflowMetadataCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(metadata: WorkflowMetadata): String = json.encodeToString(metadata)
}

internal class RequestMetricsRecorder(
    var metrics: RequestMetrics,
) {
    fun limits(
        limits: ModelLimits?,
        outputTokens: Long? = null,
    ) {
        metrics =
            metrics.copy(
                requestedModelId = limits?.modelId ?: metrics.requestedModelId,
                contextLength = limits?.contextLength,
                modelMaxOutputTokens = limits?.maxCompletionTokens,
                effectiveOutputTokens = outputTokens,
            )
    }

    /** Only explicit metadata fields survive; prompts, messages, headers and reasoning text never do. */
    fun response(
        root: JsonObject?,
        status: Int,
        key: String?,
    ) {
        val usage = root?.get("usage") as? JsonObject
        val completionDetails = usage?.get("completion_tokens_details") as? JsonObject
        val promptDetails = usage?.get("prompt_tokens_details") as? JsonObject
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val stats = root?.get("stats") as? JsonObject

        fun text(value: JsonElement?): String? =
            (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.let { redactSecrets(it, key).take(200) }
                ?.takeIf { it.isNotBlank() }
        metrics =
            metrics.copy(
                httpStatus = status,
                responseModelId = text(root?.get("model")),
                provider = text(root?.get("provider")),
                responseId = text(root?.get("id")),
                finishReason = text(choice?.get("finish_reason")),
                usage =
                    usage?.let {
                        TokenUsage(
                            promptTokens = it.count("prompt_tokens"),
                            completionTokens = it.count("completion_tokens"),
                            totalTokens = it.count("total_tokens"),
                            reasoningTokens = completionDetails.count("reasoning_tokens"),
                            cachedPromptTokens = promptDetails.count("cached_tokens"),
                            cacheWriteTokens = promptDetails.count("cache_write_tokens"),
                            cost = it.number("cost"),
                        )
                    },
                reportedGenerationTokensPerSecond = stats.number("tokens_per_second"),
                reportedTimeToFirstTokenSeconds = stats.number("time_to_first_token"),
            )
    }
}

private fun JsonObject?.count(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject?.number(key: String): Double? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

internal fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000

internal fun metricsEnvelope(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        // Optional telemetry cannot repair invalid provider JSON; normal response validation still handles it.
        null
    }

internal suspend fun measuredCompletion(
    api: String,
    modelId: String,
    system: String,
    user: String,
    target: Long,
    action: suspend (RequestMetricsRecorder) -> Completion,
): Completion {
    val started = System.nanoTime()
    val recorder =
        RequestMetricsRecorder(
            RequestMetrics(
                api = api,
                requestedModelId = modelId,
                inputUtf8Bytes = system.toByteArray().size.toLong() + user.toByteArray().size,
                estimatedInputTokens = estimateInputTokens(system, user),
                requestedOutputTokens = target,
            ),
        )

    fun snapshot(): RequestMetrics {
        val millis = elapsedMillis(started)
        val tokens = recorder.metrics.usage?.completionTokens
        return recorder.metrics.copy(
            elapsedMillis = millis,
            completionTokensPerSecondOverRequest = tokens?.takeIf { millis > 0 }?.let { it * 1000.0 / millis },
        )
    }
    return try {
        action(recorder).copy(metrics = snapshot())
    } catch (e: CancellationException) {
        throw e
    } catch (e: OpenRouterWorkflowException) {
        throw OpenRouterWorkflowException(
            e.kind,
            e.message.orEmpty(),
            e,
            e.diagnostics,
            e.rawOutput,
            requestMetrics = snapshot().let { it.copy(httpStatus = it.httpStatus ?: e.diagnostics?.httpStatus) },
        )
    }
}
