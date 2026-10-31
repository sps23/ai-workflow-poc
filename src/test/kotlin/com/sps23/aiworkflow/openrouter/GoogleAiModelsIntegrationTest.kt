package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt in with -PgoogleAiModelsIntegration; requires only GOOGLE_AI_API_KEY. */
class GoogleAiModelsIntegrationTest {
    @Test
    fun `live Google model discovery returns available generation models`() =
        runBlocking {
            val key =
                System.getenv(GOOGLE_AI_API_KEY_VARIABLE)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("Set GOOGLE_AI_API_KEY in the test process environment to run live Google model discovery.")
            val models = fetchGoogleAiModels(key)
            assertTrue(models.isNotEmpty(), "Google must return at least one available generateContent model.")
            assertTrue(models.all { it.id.isNotBlank() && it.inputTokenLimit > 0 && it.outputTokenLimit > 0 })
            println("Google model discovery returned ${models.size} available generation models.")
            models.forEach { println("${it.id}: input=${it.inputTokenLimit}, output=${it.outputTokenLimit}") }
        }
}
