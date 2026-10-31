package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAiClientTest {
    private val key = "google-test-key"

    private fun config(
        server: FakeOpenRouterServer,
        budget: Long = 32768,
    ) = GoogleAiConfig(key, FakeOpenRouterServer.GOOGLE_MODEL, server.baseUrl, budget)

    @Test
    fun `native discovery paginates supports generation only and never sends keys in URL`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.googleModelsBody = FakeOpenRouterServer.googleModelsJson().dropLast(1) + ""","nextPageToken":"page-2"}"""
                server.googleNextPageBody = FakeOpenRouterServer.googleModelsJson(id = "gemini-second", input = 200_000, output = 16384)
                val models = fetchGoogleAiModels(key, server.baseUrl)
                assertEquals(listOf("gemini-second", "gemini-test"), models.map { it.id })
                assertEquals(200_000L, models.first().inputTokenLimit)
                assertEquals(16384L, models.first().outputTokenLimit)
                assertEquals(2, server.requests.size)
                server.requests.forEach {
                    assertEquals("/v1beta/models", it.path)
                    assertEquals(key, it.googleApiKey)
                    assertNull(it.authorization)
                    assertFalse(key in it.query.orEmpty())
                }
                assertTrue(
                    "pageToken=page-2" in
                        server.requests
                            .last()
                            .query
                            .orEmpty(),
                )
            }
        }

    @Test
    fun `workflow verifies once pins model two stages caps output and preserves metrics`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                val workflow = OpenRouterCarWorkflow(GoogleAiClient(config(server)), "{}")
                val result = workflow.answer("family car")
                assertEquals(FakeOpenRouterServer.GOOGLE_MODEL, result.requestedModelId)
                assertEquals(3, server.requests.size)
                assertEquals(
                    listOf("2048", "8192"),
                    server.chatRequests.map {
                        Json
                            .parseToJsonElement(it.body)
                            .jsonObject
                            .getValue("max_tokens")
                            .jsonPrimitive.content
                    },
                )
                server.chatRequests.forEach {
                    val body = Json.parseToJsonElement(it.body).jsonObject
                    assertEquals("/v1beta/openai/chat/completions", it.path)
                    assertEquals("Bearer $key", it.authorization)
                    assertEquals(FakeOpenRouterServer.GOOGLE_MODEL, body.getValue("model").jsonPrimitive.content)
                    assertFalse("temperature" in body)
                    assertFalse("tools" in body)
                    assertFalse("models" in body)
                }
                assertFalse("<catalog_json>" in server.chatRequests.first().body)
                assertTrue("<catalog_json>" in server.chatRequests.last().body)
                val metrics =
                    result.metadata!!
                        .stages
                        .last()
                        .request!!
                assertEquals(LlmApi.GOOGLE_AI.label, metrics.api)
                assertEquals(100_000L, metrics.contextLength)
                assertEquals(8192L, metrics.modelMaxOutputTokens)
                assertEquals(10L, metrics.usage?.promptTokens)
                assertFalse(key in WorkflowMetadataCodec.encode(result.metadata))
                workflow.answer("family car", LlmRequest("family car", emptyList(), emptyList(), emptyList(), listOf("family car")))
                assertEquals(3, server.chatRequests.size)
            }
        }

    @Test
    fun `Google input gate is independent of output reservation and rejects oversized input before chat`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                val client = GoogleAiClient(config(server, budget = 8192))
                server.chatBody = FakeOpenRouterServer.chatResponse("Hello")
                val limits = ModelLimits(1024, 8192, FakeOpenRouterServer.GOOGLE_MODEL)
                val result = client.complete("hello", limits = limits, maxCompletionTokens = 8192)
                assertEquals("Hello", result.text)
                assertEquals(8192L, result.metrics?.effectiveOutputTokens)
                val error =
                    assertFailsWith<OpenRouterWorkflowException> {
                        client.complete("x".repeat(3000), limits = limits, maxCompletionTokens = 8192)
                    }
                assertEquals(FailureKind.REQUEST_TOO_LARGE, error.kind)
                assertEquals(false, error.requestMetrics?.dispatched)
                assertEquals(1, server.chatRequests.size)
            }
        }

    @Test
    fun `missing model invalid metadata repeated page token and auth failures stop inference`() =
        runBlocking {
            for (body in listOf(
                "not JSON",
                """{"models":[]}""",
                FakeOpenRouterServer.googleModelsJson(input = 0),
                FakeOpenRouterServer.googleModelsJson(output = 0),
                FakeOpenRouterServer.googleModelsJson(id = "other"),
            )) {
                FakeOpenRouterServer().use { server ->
                    server.googleModelsBody = body
                    val error =
                        assertFailsWith<OpenRouterWorkflowException> {
                            OpenRouterCarWorkflow(GoogleAiClient(config(server)), "{}").answer("x")
                        }
                    assertEquals(FailureKind.MODEL_UNAVAILABLE, error.kind)
                    assertTrue(server.chatRequests.isEmpty())
                }
            }
            FakeOpenRouterServer().use { server ->
                server.googleModelsBody = FakeOpenRouterServer.googleModelsJson().dropLast(1) + ""","nextPageToken":"same"}"""
                assertFailsWith<OpenRouterWorkflowException> { fetchGoogleAiModels(key, server.baseUrl) }
                assertEquals(2, server.requests.size)
                server.modelsStatus = 403
                server.googleModelsBody = """{"error":{"message":"invalid $key","code":403}}"""
                val error = assertFailsWith<OpenRouterWorkflowException> { fetchGoogleAiModels(key, server.baseUrl) }
                assertEquals(FailureKind.AUTHENTICATION, error.kind)
                assertFalse(key in error.diagnostics?.providerMessage.orEmpty())
                server.modelsStatus = 400
                server.googleModelsBody = """{"error":{"message":"API key not valid $key","details":[{"reason":"API_KEY_INVALID"}]}}"""
                assertEquals(
                    FailureKind.AUTHENTICATION,
                    assertFailsWith<OpenRouterWorkflowException> { fetchGoogleAiModels(key, server.baseUrl) }.kind,
                )
                server.googleModelsBody = """{"error":{"message":"invalid page token"}}"""
                assertEquals(
                    FailureKind.MODEL_UNAVAILABLE,
                    assertFailsWith<OpenRouterWorkflowException> { fetchGoogleAiModels(key, server.baseUrl) }.kind,
                )
            }
        }

    @Test
    fun `Google chat failures are typed retain raw truncation and never retry`() =
        runBlocking {
            for ((status, kind) in listOf(401 to FailureKind.AUTHENTICATION, 429 to FailureKind.RATE_LIMIT, 503 to FailureKind.PROVIDER)) {
                FakeOpenRouterServer().use { server ->
                    server.chatStatus = status
                    server.chatBody = """{"error":{"message":"failed $key"}}"""
                    val error =
                        assertFailsWith<OpenRouterWorkflowException> {
                            OpenRouterCarWorkflow(GoogleAiClient(config(server)), "{}").answer("x")
                        }
                    assertEquals(kind, error.kind)
                    assertEquals(1, server.chatRequests.size)
                    assertFalse(key in error.diagnostics?.providerMessage.orEmpty())
                }
            }
            FakeOpenRouterServer().use { server ->
                server.translationBody = FakeOpenRouterServer.chatResponse("partial", "length")
                val error =
                    assertFailsWith<OpenRouterWorkflowException> {
                        OpenRouterCarWorkflow(GoogleAiClient(config(server)), "{}").answer("x")
                    }
                assertEquals(FailureKind.TRUNCATED_RESPONSE, error.kind)
                assertEquals("partial", error.rawOutput)
                assertEquals(1, server.chatRequests.size)
                assertEquals(10L, error.requestMetrics?.usage?.promptTokens)
            }
        }

    @Test
    fun `Google env configuration validates key model budget and normalizes native prefix`() {
        val env = mapOf(GOOGLE_AI_API_KEY_VARIABLE to key, GOOGLE_AI_MODEL_VARIABLE to "models/gemini-test")
        val config = GoogleAiConfig.fromEnvironment(env)
        assertEquals("gemini-test", config.modelId)
        assertEquals(key, config.apiKey)
        assertEquals(32768L, config.maxCompletionTokens)
        assertEquals("override", GoogleAiConfig.fromEnvironment(env, apiKeyOverride = " override ").apiKey)
        assertFalse(key in config.toString())
        assertFailsWith<OpenRouterWorkflowException> { GoogleAiConfig.fromEnvironment(emptyMap()) }
        assertFailsWith<OpenRouterWorkflowException> { GoogleAiConfig.fromEnvironment(mapOf(GOOGLE_AI_API_KEY_VARIABLE to key)) }
        for (value in listOf("", "0", "-1", "2147483648", "bad")) {
            assertFailsWith<OpenRouterWorkflowException> {
                GoogleAiConfig.fromEnvironment(env + (GOOGLE_AI_MAX_COMPLETION_TOKENS_VARIABLE to value))
            }
        }
    }

    @Test
    fun `standalone Google CLI sends one hello chat without catalog and uses env credentials`() {
        FakeOpenRouterServer().use { server ->
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val env = mapOf(GOOGLE_AI_API_KEY_VARIABLE to key, GOOGLE_AI_MODEL_VARIABLE to FakeOpenRouterServer.GOOGLE_MODEL)
            server.chatBody = FakeOpenRouterServer.chatResponse("Hello!")
            assertEquals(0, runGoogleAiPrompt(listOf("Hello!"), env, PrintStream(out), PrintStream(err), server.baseUrl))
            assertTrue("Hello!" in out.toString())
            assertEquals("", err.toString())
            assertEquals(1, server.chatRequests.size)
            assertFalse("<catalog_json>" in server.chatRequests.single().body)
            assertEquals(1, runGoogleAiPrompt(listOf("Hello"), emptyMap(), PrintStream(out), PrintStream(err), server.baseUrl))
            assertEquals(2, runGoogleAiPrompt(emptyList(), env, PrintStream(out), PrintStream(err), server.baseUrl))
            assertFalse(key in err.toString())
        }
    }

    @Test
    fun `Google cancellation after translation prevents recommendation`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                assertFailsWith<CancellationException> {
                    OpenRouterCarWorkflow(GoogleAiClient(config(server)), "{}").answer("x") { throw CancellationException() }
                }
                assertEquals(1, server.chatRequests.size)
            }
        }
}
