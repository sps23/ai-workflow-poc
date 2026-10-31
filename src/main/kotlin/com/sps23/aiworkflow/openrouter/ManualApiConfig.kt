package com.sps23.aiworkflow.openrouter

import java.net.URI

const val MANUAL_MAX_COMPLETION_TOKENS_VARIABLE = "MANUAL_AI_MAX_COMPLETION_TOKENS"

class ManualApiConfig(
    baseUrl: String,
    apiKey: String?,
    modelId: String,
    val maxCompletionTokens: Long = 32_768,
) {
    val baseUrl = validateManualBaseUrl(baseUrl)
    val apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
    val modelId = modelId.trim()

    init {
        if (this.modelId.isBlank()) throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Select or type a manual API model ID.")
        require(maxCompletionTokens in 1..Int.MAX_VALUE.toLong()) { "Manual completion budget must be a positive Int." }
    }

    fun backend(): ChatBackend =
        LocalLlmClient(
            LocalLlmConfig(
                LlmApi.MANUAL,
                apiKey,
                baseUrl,
                maxCompletionTokens = maxCompletionTokens,
                modelId = modelId,
            ),
        )

    override fun toString(): String = "ManualApiConfig(baseUrl=$baseUrl, modelId=$modelId)"
}

fun validateManualBaseUrl(value: String): String {
    fun reject(): Nothing =
        throw OpenRouterWorkflowException(
            FailureKind.CONFIGURATION,
            "Enter an absolute API base URL (for example https://provider.example/v1). " +
                "HTTPS is required except HTTP on localhost/loopback. Credentials, query strings and fragments are not allowed.",
        )
    val uri =
        try {
            URI(value.trim())
        } catch (_: java.net.URISyntaxException) {
            reject()
        }
    val host = uri.host?.lowercase() ?: reject()
    val scheme = uri.scheme?.lowercase()
    val loopback =
        host == "localhost" || host == "[::1]" || host == "::1" ||
            (Regex("""127(?:\.\d{1,3}){3}""").matches(host) && host.split('.').all { it.toInt() in 0..255 })
    if ((scheme != "https" && !(scheme == "http" && loopback)) ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        uri.port !in -1..65535 || uri.port == 0
    ) {
        reject()
    }
    if (uri.path.trimEnd('/').endsWith("/chat/completions") || uri.path.trimEnd('/').endsWith("/models")) reject()
    return uri.toASCIIString().trimEnd('/')
}

suspend fun fetchManualModels(
    baseUrl: String,
    apiKey: String?,
): List<String> =
    fetchOpenAiModels(
        apiKey.orEmpty().trim(),
        validateManualBaseUrl(baseUrl),
        manual = true,
    )
