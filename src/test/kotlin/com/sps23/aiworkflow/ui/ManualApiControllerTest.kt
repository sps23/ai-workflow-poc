package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.ManualApiConfig
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

class ManualApiControllerTest {
    @Test
    fun `manual setup forwarded independently while translation survives URL key and provider changes`() =
        runBlocking {
            val configs = mutableListOf<ManualApiConfig>()
            val caches = mutableListOf<LlmRequest?>()
            lateinit var controller: CarPromptController
            controller =
                CarPromptController(
                    manualAnswer = { prompt, config, cached, translated ->
                        configs += config
                        caches += cached
                        controller.updateManualBaseUrl("https://blocked.example/v1")
                        controller.updateManualApiKey("blocked")
                        translated(cached ?: LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                        CarAnswer(FakeOpenRouterServer.CAR_OUTPUT, config.modelId, null)
                    },
                ) { _, _, model, _, _, _ -> CarAnswer(FakeOpenRouterServer.CAR_OUTPUT, model, null) }
            controller.selectApi(LlmApi.MANUAL)
            assertEquals("", controller.selectedModel)
            controller.updateManualBaseUrl("https://manual.example/custom")
            controller.selectModel("custom/model")
            controller.submit("x")
            assertEquals("https://manual.example/custom", configs[0].baseUrl)
            assertNull(configs[0].apiKey)
            assertNull(caches[0])
            controller.updateManualApiKey("manual-key")
            controller.updateManualBaseUrl("http://localhost:8080/v1")
            controller.submit("x")
            assertEquals("manual-key", configs[1].apiKey)
            assertEquals("x", caches[1]?.originalRequest)
            assertEquals("http://localhost:8080/v1", controller.manualBaseUrl)
            assertEquals("manual-key", controller.manualApiKey)
            controller.selectApi(LlmApi.OPENROUTER)
            assertEquals("openrouter/free", controller.selectedModel)
            controller.selectApi(LlmApi.MANUAL)
            assertEquals("custom/model", controller.selectedModel)
        }

    @Test
    fun `discovery scoped to URL and key with search and stale results discarded`() =
        runBlocking {
            val oldStarted = CompletableDeferred<Unit>()
            val oldFinish = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<String, String?>>()
            val controller =
                CarPromptController(
                    discoverManualModels = { url, key ->
                        calls += url to key
                        if (url.contains("old.example")) {
                            oldStarted.complete(Unit)
                            oldFinish.await()
                            listOf("old/model")
                        } else {
                            listOf("new/model")
                        }
                    },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.MANUAL)
            controller.updateManualBaseUrl("https://old.example/v1")
            val old = launch { controller.loadManualModels() }
            oldStarted.await()
            controller.updateManualBaseUrl("https://new.example/api")
            controller.updateManualApiKey("key")
            controller.loadManualModels()
            controller.loadManualModels()
            oldFinish.complete(Unit)
            old.join()
            assertEquals(2, calls.size)
            assertEquals("https://new.example/api" to "key", calls.last())
            assertEquals(listOf("new/model"), controller.manualModels)
            controller.selectModel("NEW")
            assertEquals(listOf("new/model"), controller.matchingManualModels)
            controller.loadManualModels(refresh = true)
            assertEquals(3, calls.size)
            assertEquals("NEW", controller.selectedModel)
        }

    @Test
    fun `discovery errors stay explicit while typed IDs remain available invalid URL prevents answer`() =
        runBlocking {
            var answered = false
            val controller =
                CarPromptController(
                    discoverManualModels = { _, _ -> throw OpenRouterWorkflowException(FailureKind.MODEL_UNAVAILABLE, "private") },
                    manualAnswer = { _, config, _, _ ->
                        answered = true
                        CarAnswer(FakeOpenRouterServer.CAR_OUTPUT, config.modelId, null)
                    },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.MANUAL)
            controller.updateManualBaseUrl("https://example.com/v1")
            controller.loadManualModels()
            assertTrue("MODEL_UNAVAILABLE" in controller.manualModelsError.orEmpty())
            controller.selectModel("typed/model")
            controller.submit("x")
            assertTrue(answered)
            assertIs<CarPromptState.Answered>(controller.state)
            answered = false
            controller.updateManualBaseUrl("http://remote.example/v1")
            controller.submit("x")
            assertEquals(false, answered)
            assertTrue("CONFIGURATION" in assertIs<CarPromptState.Failed>(controller.state).message)
        }
}
