package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import com.sps23.aiworkflow.recommendation.LlmRequest
import com.sps23.aiworkflow.recommendation.LlmRequestCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarPromptControllerTest {
    private val raw = FakeOpenRouterServer.CAR_OUTPUT

    @Test
    fun `debug metadata is exportable after success and failure and clears for edits and new submissions`() =
        runBlocking {
            val success = WorkflowMetadata(elapsedMillis = 50, stages = listOf(StageMetrics("Car recommendation", "SUCCEEDED", 50)))
            val failure = WorkflowMetadata(elapsedMillis = 20, stages = listOf(StageMetrics("Car recommendation", "FAILED", 20, "TIMEOUT")))
            var calls = 0
            lateinit var controller: CarPromptController
            controller =
                CarPromptController { _, _, _, _, _ ->
                    assertNull(controller.metadata)
                    calls++
                    if (calls == 1) {
                        CarAnswer(raw, "model", null, metadata = success)
                    } else {
                        throw OpenRouterWorkflowException(FailureKind.TIMEOUT, "private failure text", metadata = failure)
                    }
                }
            controller.submit("x")
            assertEquals(success, controller.metadata)
            assertEquals(WorkflowMetadataCodec.encode(success), controller.metadataJson)
            controller.selectApi(LlmApi.LM_STUDIO)
            controller.submit("x")
            assertIs<CarPromptState.Failed>(controller.state)
            assertEquals(failure, controller.metadata)
            assertEquals(WorkflowMetadataCodec.encode(failure), controller.metadataJson)
            controller.updatePrompt("new request")
            assertNull(controller.metadata)
            assertNull(controller.metadataJson)
        }

    @Test
    fun `configuration failures expose unavailable metrics without including exception text in JSON`() =
        runBlocking {
            val controller =
                CarPromptController { _, _, _, _, _ ->
                    throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "secret failure text")
                }
            controller.submit("x")
            assertIs<CarPromptState.Failed>(controller.state)
            assertEquals(
                "FAILED",
                controller.metadata
                    ?.stages
                    ?.single()
                    ?.outcome,
            )
            assertNull(
                controller.metadata
                    ?.stages
                    ?.single()
                    ?.request,
            )
            assertTrue("CONFIGURATION" in controller.metadataJson.orEmpty())
            assertTrue("secret failure text" !in controller.metadataJson.orEmpty())
        }

    @Test
    fun `answer becomes structured state with raw and readable diagnostic copies`() =
        runBlocking {
            var received: String? = null
            val controller =
                CarPromptController { prompt, _, _, _, _ ->
                    received = prompt
                    CarAnswer(raw, "vendor/model:free", "vendor/model")
                }

            controller.submit("seven seats")

            assertEquals("seven seats", received)
            val state = assertIs<CarPromptState.Answered>(controller.state)
            assertEquals(raw, state.rawOutput)
            assertEquals(
                "Comfort",
                state.selection.cars
                    .single()
                    .trim,
            )
            assertEquals("vendor/model", state.modelLabel)
            assertTrue("Electric seats\tOptional\tRequires comfort pack" in state.readableOutput)
            assertTrue("Model: vendor/model" in state.readableOutput)
            assertTrue("AI-generated prototype advice." in state.readableOutput)
        }

    @Test
    fun `missing response model id falls back to requested id`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _, _ -> CarAnswer(raw, "vendor/model:free", null) }

            controller.submit("x")

            assertEquals(
                "vendor/model:free (requested; response model ID not reported)",
                assertIs<CarPromptState.Answered>(controller.state).modelLabel,
            )
        }

    @Test
    fun `workflow failure is shown with its kind`() =
        runBlocking {
            val controller =
                CarPromptController { _, _, _, _, _ ->
                    throw OpenRouterWorkflowException(FailureKind.RATE_LIMIT, "Slow down.")
                }

            controller.submit("x")

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue(details.startsWith("Error [RATE_LIMIT]: Slow down."))
            assertTrue("Model: ${OPENROUTER_MODELS.first()}" in details)
            assertTrue("Suggested action: Check OpenRouter's quota/rate limits" in details)
        }

    @Test
    fun `unexpected failure is shown with its type`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _, _ -> throw IllegalArgumentException("Prompt must not be blank.") }

            controller.submit("")

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue(details.startsWith("Error: IllegalArgumentException: Prompt must not be blank."))
            assertTrue("Stage: Application" in details)
        }

    @Test
    fun `failure report includes provider diagnostics and the submitted model`() =
        runBlocking {
            val controller =
                CarPromptController { _, _, _, _, _ ->
                    throw OpenRouterWorkflowException(
                        FailureKind.PROVIDER,
                        "Provider error (HTTP 503).",
                        diagnostics =
                            OpenRouterErrorDiagnostics(
                                stage = "Chat completion",
                                httpStatus = 503,
                                providerCode = "unavailable",
                                providerMessage = "No endpoints available.",
                                providerName = "Test provider",
                                requestId = "req-123",
                                upstreamCode = "overloaded",
                                upstreamMessage = "Provider capacity exhausted.",
                            ),
                    )
                }
            controller.selectModel(OPENROUTER_MODELS.last())
            controller.submit("x")
            controller.selectModel(OPENROUTER_MODELS.first())

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            listOf(
                "Model: ${OPENROUTER_MODELS.last()}",
                "Stage: Chat completion",
                "HTTP status: 503",
                "Provider code: unavailable",
                "Provider message: No endpoints available.",
                "Provider: Test provider",
                "Request ID: req-123",
                "Upstream code: overloaded",
                "Upstream message: Provider capacity exhausted.",
                "Suggested action:",
            ).forEach { assertTrue(it in details, "Missing $it") }
        }

    @Test
    fun `cancellation restores idle instead of showing an error`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _, _ -> throw CancellationException("closed") }

            assertFailsWith<CancellationException> { controller.submit("x") }

            assertEquals(CarPromptState.Idle, controller.state)
        }

    @Test
    fun `first free model is selected by default and sent with the prompt`() =
        runBlocking {
            var sentModel: String? = null
            val controller =
                CarPromptController { _, _, model, _, _ ->
                    sentModel = model
                    CarAnswer(raw, model, null)
                }

            controller.submit("x")

            assertEquals(OPENROUTER_MODELS.first(), controller.selectedModel)
            assertEquals("openrouter/free", sentModel)
        }

    @Test
    fun `selected model is sent with the prompt`() =
        runBlocking {
            var sentModel: String? = null
            val controller =
                CarPromptController { _, _, model, _, _ ->
                    sentModel = model
                    CarAnswer(raw, model, null)
                }

            controller.selectModel("qwen/qwen3.5-flash-02-23")
            controller.submit("x")

            assertEquals("qwen/qwen3.5-flash-02-23", sentModel)
        }

    @Test
    fun `model list keeps NVIDIA free models and free router plus approved paid Qwen`() {
        assertEquals(
            listOf(
                "openrouter/free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "nvidia/nemotron-3.5-super-vl:free",
                "nvidia/nemotron-3.5-lightning:free",
                "qwen/qwen3.5-flash-02-23",
            ),
            OPENROUTER_MODELS,
        )
    }

    @Test
    fun `unknown model is rejected`() {
        val controller = CarPromptController { _, _, _, _, _ -> error("unused") }

        assertFailsWith<IllegalArgumentException> { controller.selectModel("vendor/paid-model") }
    }

    @Test
    fun `invalid output has error details and copyable raw content`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _, _ -> CarAnswer("not JSON", "vendor/model:free", null) }
            controller.submit("x")
            val state = assertIs<CarPromptState.Failed>(controller.state)
            assertTrue("INVALID_RESPONSE" in state.message)
            assertTrue("Response JSON validation" in state.message)
            assertEquals("not JSON", state.rawOutput)
        }

    @Test
    fun `download is available during recommendation retained on failure and cleared by new submission`() =
        runBlocking {
            val ready = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val controller =
                CarPromptController { prompt, _, _, _, translated ->
                    calls++
                    if (calls == 1) {
                        translated(LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                        ready.complete(Unit)
                        finish.await()
                    }
                    throw OpenRouterWorkflowException(FailureKind.PROVIDER, "Failed")
                }
            val job = launch { controller.submit("seven seats") }
            ready.await()
            assertEquals(CarPromptState.Loading, controller.state)
            assertEquals("2/2 Finding cars", controller.progressLabel)
            val json = checkNotNull(controller.translatedRequestJson)
            assertEquals("seven seats", LlmRequestCodec.decode(json, "seven seats").originalRequest)
            controller.submit("ignored while loading")
            controller.updatePrompt("blocked edit")
            assertEquals("seven seats", controller.prompt)
            assertEquals(json, controller.translatedRequestJson)
            assertEquals(1, calls)
            finish.complete(Unit)
            job.join()
            assertIs<CarPromptState.Failed>(controller.state)
            assertEquals(json, controller.translatedRequestJson)
            controller.submit("new request")
            assertEquals(null, controller.translatedRequestJson)
            assertEquals(2, calls)
        }

    @Test
    fun `unchanged input reuses translation across models but any edit clears results and cache immediately`() =
        runBlocking {
            val caches = mutableListOf<LlmRequest?>()
            val models = mutableListOf<String>()
            val controller =
                CarPromptController { prompt, _, model, cached, translated ->
                    caches += cached
                    models += model
                    translated(cached ?: LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                    CarAnswer(raw, model, null)
                }
            controller.submit("seven seats")
            val json = controller.translatedRequestJson
            controller.updatePrompt("seven seats")
            assertIs<CarPromptState.Answered>(controller.state)
            assertEquals(json, controller.translatedRequestJson)
            controller.selectModel(OPENROUTER_MODELS.last())
            controller.submit("seven seats")
            assertEquals(null, caches[0])
            assertEquals("seven seats", caches[1]?.originalRequest)
            assertEquals(OPENROUTER_MODELS.last(), models[1])
            controller.updatePrompt("seven seats ")
            assertEquals(CarPromptState.Idle, controller.state)
            assertEquals(null, controller.translatedRequestJson)
            controller.updatePrompt("seven seats")
            controller.submit("seven seats")
            assertEquals(null, caches[2])
        }

    @Test
    fun `recommendation failure does not invalidate cached translation`() =
        runBlocking {
            var calls = 0
            val controller =
                CarPromptController { prompt, _, _, cached, translated ->
                    calls++
                    if (calls == 1) {
                        assertEquals(null, cached)
                        translated(LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                    } else {
                        assertEquals(prompt, cached?.originalRequest)
                    }
                    throw OpenRouterWorkflowException(FailureKind.PROVIDER, "Failed")
                }
            controller.submit("x")
            controller.selectModel(OPENROUTER_MODELS.last())
            controller.submit("x")
            assertEquals(2, calls)
            assertTrue(controller.translatedRequestJson != null)
            controller.updatePrompt("y")
            assertEquals(CarPromptState.Idle, controller.state)
            assertEquals(null, controller.translatedRequestJson)
        }

    @Test
    fun `openrouter is the default api and local presets use common local urls`() {
        val controller = CarPromptController { _, _, _, _, _ -> error("unused") }

        assertEquals(LlmApi.OPENROUTER, controller.selectedApi)
        assertEquals(
            listOf("http://localhost:1234/v1", "http://localhost:8080/v1", "http://localhost:11434/v1"),
            listOf(LlmApi.LM_STUDIO, LlmApi.LLAMA_CPP, LlmApi.OLLAMA).map { it.baseUrl },
        )
        assertEquals(LlmApi.entries, controller.apis)
    }

    @Test
    fun `api switch with unchanged prompt reuses translation and reports local target`() =
        runBlocking {
            val calls = mutableListOf<Pair<LlmApi, LlmRequest?>>()
            val controller =
                CarPromptController { prompt, api, _, cached, translated ->
                    calls += api to cached
                    translated(cached ?: LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                    if (api.isLocal) throw OpenRouterWorkflowException(FailureKind.CONNECTION, "Could not connect.")
                    CarAnswer(raw, "vendor/model:free", null)
                }
            controller.submit("seven seats")
            controller.selectApi(LlmApi.LM_STUDIO)
            controller.submit("seven seats")

            assertEquals(LlmApi.OPENROUTER to null, calls[0])
            assertEquals(LlmApi.LM_STUDIO, calls[1].first)
            assertEquals("seven seats", calls[1].second?.originalRequest)
            val details = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue("API: LM Studio (local)" in details)
            assertTrue("Model: LM Studio (local) loaded model (http://localhost:1234/v1)" in details)
            assertTrue("Start LM Studio (local) at http://localhost:1234/v1" in details)
            assertTrue(controller.translatedRequestJson != null)
        }

    @Test
    fun `local answer label names the reported model and api`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _, _ -> CarAnswer(raw, "LM Studio (local) loaded model", "qwen3-8b") }
            controller.selectApi(LlmApi.LM_STUDIO)
            controller.submit("x")
            assertEquals("qwen3-8b via LM Studio (local)", assertIs<CarPromptState.Answered>(controller.state).modelLabel)
        }
}
