package com.sps23.aiworkflow.openrouter

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Removes the configured key, Bearer tokens and OpenRouter keys; caps length. */
internal fun redactSecrets(
    value: String,
    secret: String?,
): String {
    val redacted = if (!secret.isNullOrEmpty()) value.replace(secret, "[REDACTED]") else value
    return redacted
        .replace(Regex("(?i)Bearer\\s+\\S+"), "Bearer [REDACTED]")
        .replace(Regex("sk-or-[A-Za-z0-9_-]+"), "[REDACTED]")
        .let { if (it.length > 4_000) it.take(4_000) + "\n[truncated]" else it }
}

/** Parses an OpenAI/OpenRouter-style `{"error":{...}}` body into redacted diagnostics. */
internal fun providerDiagnostics(
    stage: String,
    status: Int?,
    body: String,
    secret: String?,
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

    fun JsonObject?.field(name: String): String? = (this?.get(name) as? JsonPrimitive)?.contentOrNull?.let { redactSecrets(it, secret) }
    return OpenRouterErrorDiagnostics(
        stage = stage,
        httpStatus = status,
        providerCode = providerError.field("code"),
        providerMessage =
            providerError.field("message") ?: (root?.get("error") as? JsonPrimitive)?.contentOrNull?.let { redactSecrets(it, secret) },
        providerName = metadata.field("provider_name"),
        requestId = root.field("request_id") ?: metadata.field("request_id"),
        upstreamCode = upstreamError.field("code"),
        upstreamMessage = upstreamError.field("message"),
    )
}
