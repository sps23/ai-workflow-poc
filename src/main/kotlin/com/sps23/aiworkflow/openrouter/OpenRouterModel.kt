package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class OpenRouterModel(
    val id: String,
    val name: String,
    val isFree: Boolean,
    /** USD per token, as reported by OpenRouter; other pricing components may apply. */
    val promptPrice: String,
    val completionPrice: String,
    val limits: ModelLimits,
)

/** Only verifiable text models are selectable; inference repeats these checks against live metadata. */
fun parseOpenRouterModels(json: String): List<OpenRouterModel> {
    val models =
        openRouterModelData(json)
            .mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                try {
                    verifiedOpenRouterModel(model)
                } catch (_: OpenRouterWorkflowException) {
                    // Ineligible entries are omitted; the picker explicitly describes this filter.
                    null
                }
            }.distinctBy { it.id }
            .sortedWith(compareBy<OpenRouterModel> { !it.isFree }.thenBy { it.id })
    if (models.isEmpty()) {
        throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "OpenRouter returned no verifiable text models.")
    }
    return models
}

/** Public metadata only: no credentials, prompt, catalog or inference request. */
suspend fun fetchOpenRouterModels(baseUrl: String = OPENROUTER_BASE_URL): List<OpenRouterModel> =
    withContext(Dispatchers.IO) {
        parseOpenRouterModels(
            OpenRouterClient(OpenRouterConfig(apiKey = "", modelId = "", baseUrl = baseUrl, requestTimeoutMillis = 30_000))
                .fetchModelsJson(),
        )
    }
