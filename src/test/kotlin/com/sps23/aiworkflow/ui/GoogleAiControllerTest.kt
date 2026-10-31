package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.GoogleAiConfig
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAiControllerTest {
    private val model = GoogleAiModel("gemini-test", "Friendly Gemini", 100_000, 8192)

    @Test
    fun `Google discovery uses key override or env searches names and retains separate selections`() =
        runBlocking {
            val keys = mutableListOf<String>()
            val controller =
                CarPromptController(
                    discoverGoogleModels = {
                        keys += it
                        listOf(model)
                    },
                    resolveGoogleKey = { GoogleAiConfig.apiKey(mapOf("GOOGLE_AI_API_KEY" to "env-key"), it) },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.GOOGLE_AI)
            assertEquals("", controller.selectedModel)
            controller.loadGoogleModels()
            controller.loadGoogleModels()
            assertEquals(listOf("env-key"), keys)
            controller.selectModel("FRIENDLY")
            assertEquals(listOf(model), controller.matchingGoogleModels)
            controller.selectModel("gemini-test")
            assertEquals(model, controller.selectedGoogleModel)
            controller.updateGoogleApiKey("typed-key")
            assertTrue(controller.googleModels.isEmpty())
            controller.loadGoogleModels()
            controller.loadGoogleModels(refresh = true)
            assertEquals(listOf("env-key", "typed-key", "typed-key"), keys)
            controller.selectApi(LlmApi.OPENAI)
            assertEquals("", controller.selectedModel)
            controller.selectApi(LlmApi.OPENROUTER)
            assertEquals("openrouter/free", controller.selectedModel)
            controller.selectApi(LlmApi.GOOGLE_AI)
            assertEquals("gemini-test", controller.selectedModel)
            assertEquals(LlmApi.MANUAL, controller.apis.last())
        }

    @Test
    fun `Google old key results cannot overwrite new key catalog`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val controller =
                CarPromptController(
                    resolveGoogleKey = { it },
                    discoverGoogleModels = {
                        if (it == "old") {
                            started.complete(Unit)
                            finish.await()
                            listOf(model.copy(id = "old"))
                        } else {
                            listOf(model)
                        }
                    },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.GOOGLE_AI)
            controller.updateGoogleApiKey("old")
            val old = launch { controller.loadGoogleModels() }
            started.await()
            controller.updateGoogleApiKey("new")
            controller.loadGoogleModels()
            finish.complete(Unit)
            old.join()
            assertEquals(listOf(model), controller.googleModels)
            assertNull(controller.googleModelsError)
            assertEquals(false, controller.googleModelsLoading)
        }

    @Test
    fun `Google forwards only its key and preserves cached translation across model and provider switches`() =
        runBlocking {
            val keys = mutableListOf<String?>()
            val cachedInputs = mutableListOf<LlmRequest?>()
            lateinit var controller: CarPromptController
            controller =
                CarPromptController { prompt, _, id, key, cached, translated ->
                    keys += key
                    cachedInputs += cached
                    controller.updateGoogleApiKey("blocked")
                    translated(cached ?: LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                    CarAnswer(FakeOpenRouterServer.CAR_OUTPUT, id, null)
                }
            controller.updateGoogleApiKey("google-key")
            controller.submit("x")
            controller.selectApi(LlmApi.GOOGLE_AI)
            controller.selectModel("gemini-test")
            controller.submit("x")
            assertEquals("google-key", controller.googleApiKey)
            controller.updateGoogleApiKey("new-key")
            controller.submit("x")
            assertEquals(listOf(null, "google-key", "new-key"), keys)
            assertNull(cachedInputs.first())
            assertTrue(cachedInputs.drop(1).all { it?.originalRequest == "x" })
        }

    @Test
    fun `Google discovery and inference failures show Google specific configuration actions`() =
        runBlocking {
            val controller =
                CarPromptController(
                    resolveGoogleKey = { GoogleAiConfig.apiKey(emptyMap(), it) },
                    discoverGoogleModels = { throw OpenRouterWorkflowException(FailureKind.AUTHENTICATION, "private-key") },
                ) { _, _, _, _, _, _ -> throw OpenRouterWorkflowException(FailureKind.TRUNCATED_RESPONSE, "failed") }
            controller.selectApi(LlmApi.GOOGLE_AI)
            controller.loadGoogleModels()
            assertTrue("GOOGLE_AI_API_KEY" in controller.googleModelsError.orEmpty())
            controller.updateGoogleApiKey("key")
            controller.loadGoogleModels()
            assertTrue("authentication" in controller.googleModelsError.orEmpty())
            assertTrue("private-key" !in controller.googleModelsError.orEmpty())
            controller.selectModel("gemini-test")
            controller.submit("x")
            val report = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue("Google AI Studio" in report)
            assertTrue("GOOGLE_AI_MAX_COMPLETION_TOKENS" in report)
            assertTrue("OPENROUTER" !in report)
        }
}
