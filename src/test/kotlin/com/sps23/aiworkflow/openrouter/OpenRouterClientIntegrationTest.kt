package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live OpenRouter smoke test. Requires OPENROUTER_API_KEY and a verified free OPENROUTER_MODEL. */
@Ignore
class OpenRouterClientIntegrationTest {
    private val config = OpenRouterConfig.fromEnvironment(System.getenv())

    @Test
    fun `configured free model is listed with a positive context length`() {
        val limits = runBlocking { OpenRouterClient(config).fetchLimits() }

        assertTrue(limits.contextLength > 0, "context length should be positive")
    }

    @Test
    fun `simple prompt returns a non-empty answer`() {
        val completion =
            runBlocking {
                OpenRouterClient(config).complete(
                    userMessage = "Reply with one short sentence: what colour is a clear daytime sky?",
                    maxCompletionTokens = 256,
                )
            }

        assertTrue(completion.text.isNotBlank(), "answer should not be empty")
        assertEquals(config.modelId, completion.requestedModelId)
    }

    @Test
    fun `catalog question accepts the bundled data-model catalog`() {
        val catalogJson = DataModelCatalogContext.loadBundled().promptJson
        val completion =
            runBlocking {
                OpenRouterClient(config).complete(
                    userMessage = "Suggest a car from the supplied catalog.",
                    systemPrompt = SYSTEM_INSTRUCTIONS,
                    context = catalogJson,
                    maxCompletionTokens = 8192,
                )
            }

        assertTrue(completion.text.isNotBlank(), "answer should not be empty")
        assertEquals(config.modelId, completion.requestedModelId)
    }
}
