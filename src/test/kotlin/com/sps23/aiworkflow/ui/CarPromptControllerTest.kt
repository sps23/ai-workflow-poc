package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
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
import kotlin.test.assertTrue

class CarPromptControllerTest {
    private val raw = FakeOpenRouterServer.CAR_OUTPUT

    @Test
    fun `answer becomes structured state with raw and readable diagnostic copies`() =
        runBlocking {
            var received: String? = null
            val controller =
                CarPromptController { prompt, _, _, _ ->
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
            val controller = CarPromptController { _, _, _, _ -> CarAnswer(raw, "vendor/model:free", null) }

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
                CarPromptController { _, _, _, _ ->
                    throw OpenRouterWorkflowException(FailureKind.RATE_LIMIT, "Slow down.")
                }

            controller.submit("x")

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue(details.startsWith("Error [RATE_LIMIT]: Slow down."))
            assertTrue("Model: ${FREE_MODELS.first()}" in details)
            assertTrue("Suggested action: Check OpenRouter's quota/rate limits" in details)
        }

    @Test
    fun `unexpected failure is shown with its type`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _ -> throw IllegalArgumentException("Prompt must not be blank.") }

            controller.submit("")

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            assertTrue(details.startsWith("Error: IllegalArgumentException: Prompt must not be blank."))
            assertTrue("Stage: Application" in details)
        }

    @Test
    fun `failure report includes provider diagnostics and the submitted model`() =
        runBlocking {
            val controller =
                CarPromptController { _, _, _, _ ->
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
            controller.selectModel(FREE_MODELS.last())
            controller.submit("x")
            controller.selectModel(FREE_MODELS.first())

            val details = assertIs<CarPromptState.Failed>(controller.state).message
            listOf(
                "Model: ${FREE_MODELS.last()}",
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
            val controller = CarPromptController { _, _, _, _ -> throw CancellationException("closed") }

            assertFailsWith<CancellationException> { controller.submit("x") }

            assertEquals(CarPromptState.Idle, controller.state)
        }

    @Test
    fun `first free model is selected by default and sent with the prompt`() =
        runBlocking {
            var sentModel: String? = null
            val controller =
                CarPromptController { _, model, _, _ ->
                    sentModel = model
                    CarAnswer(raw, model, null)
                }

            controller.submit("x")

            assertEquals(FREE_MODELS.first(), controller.selectedModel)
            assertEquals("openrouter/free", sentModel)
        }

    @Test
    fun `selected model is sent with the prompt`() =
        runBlocking {
            var sentModel: String? = null
            val controller =
                CarPromptController { _, model, _, _ ->
                    sentModel = model
                    CarAnswer(raw, model, null)
                }

            controller.selectModel("recursive/flint-v1.0:free")
            controller.submit("x")

            assertEquals("recursive/flint-v1.0:free", sentModel)
        }

    @Test
    fun `free model list matches the agreed choices`() {
        assertEquals(
            listOf(
                "openrouter/free",
                "nvidia/nemotron-3-ultra-550b-a55b:free",
                "nvidia/nemotron-3.5-super-vl:free",
                "nvidia/nemotron-3.5-lightning:free",
                "cohere/north-mini-code:free",
                "recursive/flint-v1.0:free",
            ),
            FREE_MODELS,
        )
    }

    @Test
    fun `unknown model is rejected`() {
        val controller = CarPromptController { _, _, _, _ -> error("unused") }

        assertFailsWith<IllegalArgumentException> { controller.selectModel("vendor/paid-model") }
    }

    @Test
    fun `invalid output has error details and copyable raw content`() =
        runBlocking {
            val controller = CarPromptController { _, _, _, _ -> CarAnswer("not JSON", "vendor/model:free", null) }
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
                CarPromptController { prompt, _, _, translated ->
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
                CarPromptController { prompt, model, cached, translated ->
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
            controller.selectModel(FREE_MODELS.last())
            controller.submit("seven seats")
            assertEquals(null, caches[0])
            assertEquals("seven seats", caches[1]?.originalRequest)
            assertEquals(FREE_MODELS.last(), models[1])
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
                CarPromptController { prompt, _, cached, translated ->
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
            controller.selectModel(FREE_MODELS.last())
            controller.submit("x")
            assertEquals(2, calls)
            assertTrue(controller.translatedRequestJson != null)
            controller.updatePrompt("y")
            assertEquals(CarPromptState.Idle, controller.state)
            assertEquals(null, controller.translatedRequestJson)
        }
}
