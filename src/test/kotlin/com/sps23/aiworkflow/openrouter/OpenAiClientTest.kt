package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiClientTest {
    private val key = "openai-test-key"

    private fun workflow(server: FakeOpenRouterServer) =
        OpenRouterCarWorkflow(OpenAiClient(OpenAiConfig(key, "gpt-test", server.baseUrl, 10_000, 512)), """{"cars":[]}""")

    @Test
    fun `authenticated discovery returns all unique sorted IDs without inventing capabilities or prices`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.openAiModelsBody = """{"data":[{"id":"gpt-test"},{"id":"embedding-test"},{"id":"gpt-test"}]}"""
                assertEquals(listOf("embedding-test", "gpt-test"), fetchOpenAiModels(key, server.baseUrl))
                val request = server.requests.single()
                assertEquals("/v1/models", request.path)
                assertEquals("GET", request.method)
                assertEquals("Bearer $key", request.authorization)
                assertTrue(server.chatRequests.isEmpty())
            }
        }

    @Test
    fun `Koog sends exact selected ID two stages modern token parameter and preserves usage`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                val result = workflow(server).answer("family car")
                assertEquals(2, server.requests.size)
                assertEquals("gpt-test", result.requestedModelId)
                assertEquals(FakeOpenRouterServer.FREE_MODEL, result.responseModelId)
                server.chatRequests.forEach { request ->
                    val body = Json.parseToJsonElement(request.body).jsonObject
                    assertEquals("/v1/chat/completions", request.path)
                    assertEquals("Bearer $key", request.authorization)
                    assertEquals("gpt-test", body.getValue("model").jsonPrimitive.content)
                    assertEquals("512", body.getValue("max_completion_tokens").jsonPrimitive.content)
                    assertEquals("false", body.getValue("store").jsonPrimitive.content)
                    assertFalse("max_tokens" in body)
                    assertFalse("temperature" in body)
                    assertFalse("tools" in body)
                    assertFalse("models" in body)
                    assertFalse("provider" in body)
                }
                assertFalse("<catalog_json>" in server.chatRequests.first().body)
                assertTrue("<catalog_json>" in server.chatRequests.last().body)
                result.metadata!!.stages.forEach { stage ->
                    val request = stage.request ?: return@forEach
                    assertEquals("OpenAI", request.api)
                    assertEquals(10L, request.usage?.promptTokens)
                    assertNull(request.contextLength)
                    assertNull(request.modelMaxOutputTokens)
                }
                assertFalse(key in WorkflowMetadataCodec.encode(result.metadata))
            }
        }

    @Test
    fun `translation capped at 2048 recommendation budget configurable and cached translation reused`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                val workflow =
                    OpenRouterCarWorkflow(OpenAiClient(OpenAiConfig(key, "gpt-test", server.baseUrl, maxCompletionTokens = 32768)), "{}")
                workflow.answer("x")
                assertEquals(
                    listOf("2048", "32768"),
                    server.chatRequests.map {
                        Json
                            .parseToJsonElement(it.body)
                            .jsonObject
                            .getValue("max_completion_tokens")
                            .jsonPrimitive.content
                    },
                )
                val cached = LlmRequest("x", emptyList(), emptyList(), emptyList(), listOf("x"))
                workflow.answer("x", cached)
                assertEquals(3, server.chatRequests.size)
            }
        }

    @Test
    fun `HTTP failures are typed redacted and never retried`() =
        runBlocking {
            for ((status, kind) in listOf(
                401 to FailureKind.AUTHENTICATION,
                403 to FailureKind.AUTHENTICATION,
                429 to FailureKind.RATE_LIMIT,
                404 to FailureKind.MODEL_UNAVAILABLE,
                503 to FailureKind.PROVIDER,
                400 to FailureKind.CONTEXT,
                504 to FailureKind.TIMEOUT,
            )) {
                FakeOpenRouterServer().use { server ->
                    server.chatStatus = status
                    server.chatBody = """{"error":{"code":"test-error","message":"context failure for $key"}}"""
                    val error = assertFailsWith<OpenRouterWorkflowException> { workflow(server).answer("x") }
                    assertEquals(kind, error.kind)
                    assertEquals(status, error.diagnostics?.httpStatus)
                    assertEquals(1, server.chatRequests.size)
                    assertFalse(key in error.diagnostics?.providerMessage.orEmpty())
                    assertEquals("Request translation / Chat completion", error.diagnostics?.stage)
                }
            }
        }

    @Test
    fun `truncation invalid JSON and empty response retain metrics and stop second stage`() =
        runBlocking {
            for ((body, kind) in listOf(
                FakeOpenRouterServer.chatResponse("partial", "length") to FailureKind.TRUNCATED_RESPONSE,
                FakeOpenRouterServer.chatResponse("not JSON") to FailureKind.INVALID_RESPONSE,
                FakeOpenRouterServer.chatResponse("") to FailureKind.EMPTY_RESPONSE,
                """{"id":"gen-1","object":"chat.completion","model":"gpt-test","created":1,"choices":[]}""" to FailureKind.EMPTY_RESPONSE,
            )) {
                FakeOpenRouterServer().use { server ->
                    server.translationBody = body
                    val error = assertFailsWith<OpenRouterWorkflowException> { workflow(server).answer("x") }
                    assertEquals(kind, error.kind)
                    assertEquals(1, server.chatRequests.size)
                    assertTrue(error.requestMetrics?.dispatched == true)
                    assertTrue(error.metadata != null)
                    if (kind == FailureKind.TRUNCATED_RESPONSE) assertEquals("partial", error.rawOutput)
                }
            }
        }

    @Test
    fun `model discovery failures and invalid IDs surface explicitly`() =
        runBlocking {
            for (json in listOf("bad JSON", """{}""", """{"data":[]}""", """{"data":[{"id":12}]}""")) {
                assertEquals(FailureKind.MODEL_UNAVAILABLE, assertFailsWith<OpenRouterWorkflowException> { parseOpenAiModels(json) }.kind)
            }
            FakeOpenRouterServer().use { server ->
                server.modelsStatus = 401
                server.openAiModelsBody = """{"error":{"message":"invalid $key"}}"""
                val error = assertFailsWith<OpenRouterWorkflowException> { fetchOpenAiModels(key, server.baseUrl) }
                assertEquals(FailureKind.AUTHENTICATION, error.kind)
                assertFalse(key in error.diagnostics?.providerMessage.orEmpty())
            }
        }

    @Test
    fun `environment key fallback and budgets are validated without leaking key`() {
        val env = mapOf(OPENAI_API_KEY_VARIABLE to "env-key")
        assertEquals("env-key", OpenAiConfig.fromEnvironment(env, "gpt-test", " ").apiKey)
        val config = OpenAiConfig.fromEnvironment(env, " gpt-test ", " typed-key ")
        assertEquals("typed-key", config.apiKey)
        assertEquals("gpt-test", config.modelId)
        assertFalse("typed-key" in config.toString())
        assertEquals(32768L, config.maxCompletionTokens)
        assertFailsWith<OpenRouterWorkflowException> { OpenAiConfig.fromEnvironment(emptyMap(), "gpt-test") }
        assertFailsWith<OpenRouterWorkflowException> { OpenAiConfig.fromEnvironment(env, "") }
        for (raw in listOf("", "0", "-1", "2147483648", "invalid")) {
            assertEquals(
                FailureKind.CONFIGURATION,
                assertFailsWith<OpenRouterWorkflowException> {
                    OpenAiConfig.fromEnvironment(env + (OPENAI_MAX_COMPLETION_TOKENS_VARIABLE to raw), "gpt-test")
                }.kind,
            )
        }
    }

    @Test
    fun `unreachable OpenAI endpoint is a visible connection error`() =
        runBlocking {
            val port = ServerSocket(0).use { it.localPort }
            val client = OpenAiClient(OpenAiConfig(key, "gpt-test", "http://localhost:$port", 10_000, 512))
            val error = assertFailsWith<OpenRouterWorkflowException> { OpenRouterCarWorkflow(client, "{}").answer("x") }
            assertEquals(FailureKind.CONNECTION, error.kind)
        }

    @Test
    fun `OpenAI request timeout stays visible and is not retried`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.chatDelayMillis = 3_000
                val client = OpenAiClient(OpenAiConfig(key, "gpt-test", server.baseUrl, 500, 512))
                val error = assertFailsWith<OpenRouterWorkflowException> { OpenRouterCarWorkflow(client, "{}").answer("x") }
                assertEquals(FailureKind.TIMEOUT, error.kind)
                assertEquals(1, server.chatRequests.size)
            }
        }

    @Test
    fun `cancellation after OpenAI translation propagates and prevents recommendation`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                assertFailsWith<CancellationException> {
                    workflow(server).answer("x", onTranslated = { throw CancellationException("closed") })
                }
                assertEquals(1, server.chatRequests.size)
            }
        }
}
