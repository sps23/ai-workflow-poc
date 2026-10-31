package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.OpenAiConfig
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiControllerTest {
    private val raw = FakeOpenRouterServer.CAR_OUTPUT

    @Test
    fun `OpenAI discovery uses typed or environment key searches IDs refreshes and never selects implicitly`() =
        runBlocking {
            val keys = mutableListOf<String>()
            val controller =
                CarPromptController(
                    discoverOpenAiModels = {
                        keys += it
                        listOf("gpt-test", "embedding-test")
                    },
                    resolveOpenAiKey = { OpenAiConfig.apiKey(mapOf("OPENAI_API_KEY" to "env-key"), it) },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.OPENAI)
            assertEquals("", controller.selectedModel)
            controller.loadOpenAiModels()
            controller.loadOpenAiModels()
            assertEquals(listOf("env-key"), keys)
            assertEquals("", controller.selectedModel)
            controller.selectModel("GPT")
            assertEquals(listOf("gpt-test"), controller.matchingOpenAiModels)
            controller.updateOpenAiApiKey("typed-key")
            assertTrue(controller.openAiModels.isEmpty())
            controller.loadOpenAiModels()
            controller.loadOpenAiModels(refresh = true)
            assertEquals(listOf("env-key", "typed-key", "typed-key"), keys)
            assertEquals("GPT", controller.selectedModel)
            controller.selectApi(LlmApi.OPENROUTER)
            assertEquals("openrouter/free", controller.selectedModel)
            controller.selectModel("vendor/custom")
            controller.selectApi(LlmApi.OPENAI)
            assertEquals("GPT", controller.selectedModel)
            controller.selectApi(LlmApi.OPENROUTER)
            assertEquals("vendor/custom", controller.selectedModel)
        }

    @Test
    fun `late discovery for replaced key cannot overwrite current model list or error`() =
        runBlocking {
            val oldStarted = CompletableDeferred<Unit>()
            val oldFinish = CompletableDeferred<Unit>()
            val controller =
                CarPromptController(
                    resolveOpenAiKey = { it },
                    discoverOpenAiModels = { key ->
                        if (key == "old-key") {
                            oldStarted.complete(Unit)
                            oldFinish.await()
                            listOf("old-model")
                        } else {
                            listOf("new-model")
                        }
                    },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.OPENAI)
            controller.updateOpenAiApiKey("old-key")
            val old = launch { controller.loadOpenAiModels() }
            oldStarted.await()
            controller.updateOpenAiApiKey("new-key")
            controller.loadOpenAiModels()
            assertEquals(listOf("new-model"), controller.openAiModels)
            oldFinish.complete(Unit)
            old.join()
            assertEquals(listOf("new-model"), controller.openAiModels)
            assertNull(controller.openAiModelsError)
            assertEquals(false, controller.openAiModelsLoading)
        }

    @Test
    fun `missing key and authentication errors stay explicit typed models remain usable`() =
        runBlocking {
            var authFail = true
            val controller =
                CarPromptController(
                    resolveOpenAiKey = { OpenAiConfig.apiKey(emptyMap(), it) },
                    discoverOpenAiModels = {
                        if (authFail) throw OpenRouterWorkflowException(FailureKind.AUTHENTICATION, "private key")
                        listOf("gpt-test")
                    },
                ) { _, _, model, _, _, _ -> CarAnswer(raw, model, null) }
            controller.selectApi(LlmApi.OPENAI)
            controller.loadOpenAiModels()
            assertTrue("OPENAI_API_KEY" in controller.openAiModelsError.orEmpty())
            controller.updateOpenAiApiKey("typed-key")
            controller.loadOpenAiModels()
            assertTrue("authentication" in controller.openAiModelsError.orEmpty())
            assertTrue("private key" !in controller.openAiModelsError.orEmpty())
            controller.selectModel("custom-model")
            controller.submit("x")
            assertIs<CarPromptState.Answered>(controller.state)
            authFail = false
            controller.loadOpenAiModels(refresh = true)
            assertNull(controller.openAiModelsError)
            assertEquals("custom-model", controller.selectedModel)
        }

    @Test
    fun `OpenAI key and exact ID forwarded only to OpenAI cached translation survives provider and key switches`() =
        runBlocking {
            val calls = mutableListOf<Triple<LlmApi, String, String?>>()
            val cachedInputs = mutableListOf<LlmRequest?>()
            lateinit var controller: CarPromptController
            controller =
                CarPromptController { prompt, api, model, key, cached, translated ->
                    calls += Triple(api, model, key)
                    cachedInputs += cached
                    controller.updateOpenAiApiKey("blocked")
                    translated(cached ?: LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                    CarAnswer(raw, model, null)
                }
            controller.updateLocalApiKey("local-key")
            controller.updateOpenAiApiKey("openai-key")
            controller.submit("x")
            controller.selectApi(LlmApi.OPENAI)
            controller.selectModel(" gpt-custom ")
            controller.submit("x")
            assertEquals("openai-key", controller.openAiApiKey)
            controller.updateOpenAiApiKey("second-key")
            controller.submit("x")
            controller.selectApi(LlmApi.LM_STUDIO)
            controller.submit("x")
            assertEquals(
                listOf(
                    Triple(LlmApi.OPENROUTER, "openrouter/free", null),
                    Triple(LlmApi.OPENAI, "gpt-custom", "openai-key"),
                    Triple(LlmApi.OPENAI, "gpt-custom", "second-key"),
                    Triple(LlmApi.LM_STUDIO, "openrouter/free", "local-key"),
                ),
                calls,
            )
            assertNull(cachedInputs.first())
            assertTrue(cachedInputs.drop(1).all { it?.originalRequest == "x" })
        }

    @Test
    fun `OpenAI failures contain provider specific actions without OpenRouter settings`() =
        runBlocking {
            for (kind in listOf(
                FailureKind.AUTHENTICATION,
                FailureKind.RATE_LIMIT,
                FailureKind.TRUNCATED_RESPONSE,
                FailureKind.MODEL_UNAVAILABLE,
            )) {
                val controller = CarPromptController { _, _, _, _, _, _ -> throw OpenRouterWorkflowException(kind, "failed") }
                controller.selectApi(LlmApi.OPENAI)
                controller.selectModel("gpt-test")
                controller.submit("x")
                val report = assertIs<CarPromptState.Failed>(controller.state).message
                assertTrue("API: OpenAI" in report)
                assertTrue("OpenRouter" !in report)
                assertTrue("OPENROUTER_" !in report)
                if (kind == FailureKind.TRUNCATED_RESPONSE) assertTrue("OPENAI_MAX_COMPLETION_TOKENS" in report)
            }
        }

    @Test
    fun `cancelled discovery clears spinner and allows new automatic attempt`() =
        runBlocking {
            var attempts = 0
            val controller =
                CarPromptController(
                    resolveOpenAiKey = { "fake-key" },
                    discoverOpenAiModels = {
                        attempts++
                        if (attempts == 1) throw CancellationException()
                        listOf("gpt-test")
                    },
                ) { _, _, _, _, _, _ -> error("unused") }
            controller.selectApi(LlmApi.OPENAI)
            val cancelled = launch { controller.loadOpenAiModels() }
            cancelled.join()
            assertEquals(false, controller.openAiModelsLoading)
            controller.loadOpenAiModels()
            assertEquals(listOf("gpt-test"), controller.openAiModels)
        }
}
