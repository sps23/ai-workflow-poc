package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in live smoke: remove @Ignore locally, set GOOGLE_AI_API_KEY and GOOGLE_AI_MODEL. */
@Ignore
class GoogleAiClientIntegrationTest {
    @Test
    fun `hello prompt returns a non-empty Google answer`() =
        runBlocking {
            val config = GoogleAiConfig.fromEnvironment(System.getenv())
            val completion =
                GoogleAiClient(config).complete(
                    userMessage = "Hello! Reply with one short greeting.",
                    maxCompletionTokens = 256,
                )
            assertTrue(completion.text.isNotBlank(), "answer should not be empty")
            assertEquals(config.modelId, completion.requestedModelId)
        }
}
