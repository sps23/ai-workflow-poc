package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLlmClientTest {
    private val catalog = """{"configurations":[{"id":"car-a"}],"features":[]}"""

    private fun workflow(
        server: FakeOpenRouterServer,
        key: String? = null,
    ) = OpenRouterCarWorkflow(LocalLlmConfig(LlmApi.LM_STUDIO, key, "${server.baseUrl}/v1", 10_000, 512), catalog)

    @Test
    fun `LM Studio workflow discovers loaded context and pins the same model for both stages`() {
        FakeOpenRouterServer().use { server ->
            server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
            val answer = runBlocking { workflow(server).answer("Need a family car") }

            assertEquals(FakeOpenRouterServer.CAR_OUTPUT, answer.text)
            assertEquals(FakeOpenRouterServer.LOCAL_MODEL, answer.requestedModelId)
            assertEquals(FakeOpenRouterServer.FREE_MODEL, answer.responseModelId)
            assertEquals(listOf("/api/v0/models", "/v1/chat/completions", "/v1/chat/completions"), server.requests.map { it.path })
            server.chatRequests.forEach { request ->
                val body = Json.parseToJsonElement(request.body).jsonObject
                assertEquals(FakeOpenRouterServer.LOCAL_MODEL, body.getValue("model").jsonPrimitive.content)
                assertNull(request.authorization)
            }
            assertTrue("<catalog_json>" in server.chatRequests.last().body)
        }
    }

    @Test
    fun `optional local key is sent as bearer token and reused cache skips translation`() {
        FakeOpenRouterServer().use { server ->
            server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
            val cached = LlmRequest("x", emptyList(), emptyList(), emptyList(), listOf("x"))
            runBlocking { workflow(server, key = "local-key").answer("x", cached) }

            assertEquals(1, server.chatRequests.size)
            assertEquals(server.chatRequests.single().authorization, server.requests.first().authorization)
            assertEquals("Bearer local-key", server.chatRequests.single().authorization)
        }
    }

    @Test
    fun `environment key is optional`() {
        assertNull(LocalLlmConfig.fromEnvironment(emptyMap(), LlmApi.OLLAMA).apiKey)
        assertEquals("k", LocalLlmConfig.fromEnvironment(mapOf(LOCAL_API_KEY_VARIABLE to " k "), LlmApi.OLLAMA).apiKey)
        assertFailsWith<IllegalArgumentException> { LocalLlmConfig(LlmApi.OPENROUTER, null) }
    }

    @Test
    fun `context overflow and auth errors are classified with redacted diagnostics`() {
        FakeOpenRouterServer().use { server ->
            server.chatStatus = 400
            server.chatBody = """{"error":{"message":"request exceeds the available context size for local-key"}}"""
            val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { workflow(server, "local-key").answer("x") } }
            assertEquals(FailureKind.CONTEXT, error.kind)
            assertEquals("Request translation / Chat completion", error.diagnostics?.stage)
            assertFalse("local-key" in error.diagnostics?.providerMessage.orEmpty())

            server.chatStatus = 401
            server.chatBody = "{}"
            val auth = assertFailsWith<OpenRouterWorkflowException> { runBlocking { workflow(server).answer("x") } }
            assertEquals(FailureKind.AUTHENTICATION, auth.kind)
            assertTrue(LOCAL_API_KEY_VARIABLE in auth.message.orEmpty())
        }
    }

    @Test
    fun `unreachable local server is a connection failure`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = LocalLlmConfig(LlmApi.LLAMA_CPP, null, "http://localhost:$port/v1", 10_000, 512)
        val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { OpenRouterCarWorkflow(config, catalog).answer("x") } }
        assertEquals(FailureKind.CONNECTION, error.kind)
        assertTrue("llama.cpp (local)" in error.message.orEmpty())
    }

    @Test
    fun `truncated local output keeps raw text for diagnostics`() {
        FakeOpenRouterServer().use { server ->
            server.translationBody = FakeOpenRouterServer.chatResponse("{\"partial\"", finishReason = "length")
            val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { workflow(server).answer("x") } }
            assertEquals(FailureKind.TRUNCATED_RESPONSE, error.kind)
            assertEquals("{\"partial\"", error.rawOutput)
        }
    }

    @Test
    fun `other local presets retain model omission without LM Studio metadata`() {
        FakeOpenRouterServer().use { server ->
            server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
            val config = LocalLlmConfig(LlmApi.LLAMA_CPP, null, "${server.baseUrl}/v1", 10_000, 512)
            runBlocking { OpenRouterCarWorkflow(config, catalog).answer("x") }
            assertEquals(2, server.requests.size)
            assertTrue(server.requests.all { it.path == "/v1/chat/completions" })
            assertTrue(server.chatRequests.all { "model" !in Json.parseToJsonElement(it.body).jsonObject })
        }
    }

    @Test
    fun `recommendation preflight uses loaded context not advertised maximum and does not send oversized catalog`() {
        FakeOpenRouterServer().use { server ->
            server.localModelsBody = FakeOpenRouterServer.localModelsJson(context = 8_192)
            val config = LocalLlmConfig(LlmApi.LM_STUDIO, null, "${server.baseUrl}/v1", 10_000, 512)
            val error =
                assertFailsWith<OpenRouterWorkflowException> {
                    runBlocking { OpenRouterCarWorkflow(config, "x".repeat(20_000)).answer("x") }
                }
            assertEquals(FailureKind.REQUEST_TOO_LARGE, error.kind)
            assertEquals("Car recommendation / Request size validation", error.diagnostics?.stage)
            assertTrue("8192" in error.message.orEmpty())
            assertTrue("512 completion tokens" in error.message.orEmpty())
            assertTrue("UTF-8 input bytes" in error.message.orEmpty())
            assertTrue("not a tokenizer count" in error.message.orEmpty())
            assertEquals(1, server.chatRequests.size)
            assertFalse("<catalog_json>" in server.chatRequests.single().body)
        }
    }

    @Test
    fun `missing unloaded ambiguous and malformed metadata stop before inference`() {
        val metadata =
            listOf(
                """{"data":[]}""",
                """{"data":[{"id":"x","type":"llm","state":"not-loaded","max_context_length":262144}]}""",
                """{"data":[{"id":"x","type":"llm","state":"loaded","max_context_length":262144}]}""",
                """{"data":[{"id":"x","type":"llm","state":"loaded","loaded_context_length":0}]}""",
                """{"data":[{"id":"x","type":"llm","state":"loaded","loaded_context_length":262144},
                       {"id":"y","type":"vlm","state":"loaded","loaded_context_length":262144}]}""",
                "not JSON",
            )
        metadata.forEach { body ->
            FakeOpenRouterServer().use { server ->
                server.localModelsBody = body
                val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { workflow(server).answer("x") } }
                assertEquals(FailureKind.MODEL_UNAVAILABLE, error.kind, body)
                assertEquals("Request translation / Model metadata", error.diagnostics?.stage)
                assertTrue(server.chatRequests.isEmpty())
            }
        }
    }

    @Test
    fun `metadata HTTP failure is visible without falling back to unchecked inference`() {
        FakeOpenRouterServer().use { server ->
            server.localModelsStatus = 404
            server.localModelsBody = "{}"
            val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { workflow(server).answer("x") } }
            assertEquals("Request translation / Model metadata", error.diagnostics?.stage)
            assertEquals(404, error.diagnostics?.httpStatus)
            assertTrue(server.chatRequests.isEmpty())
        }
    }

    @Test
    fun `local output target defaults and environment override are independent of OpenRouter`() {
        assertEquals(32_768L, LocalLlmConfig(LlmApi.LM_STUDIO, null).maxCompletionTokens)
        assertEquals(32_768L, LocalLlmConfig.fromEnvironment(emptyMap(), LlmApi.LM_STUDIO).maxCompletionTokens)
        val environment =
            mapOf(
                LOCAL_MAX_COMPLETION_TOKENS_VARIABLE to " 65536 ",
                MAX_COMPLETION_TOKENS_VARIABLE to "1024",
            )
        LlmApi.entries.filter { it.isLocal }.forEach { api ->
            assertEquals(65_536L, LocalLlmConfig.fromEnvironment(environment, api).maxCompletionTokens)
        }
        listOf("", "abc", "0", "-1", "1.5", "2147483648", "999999999999999999999").forEach { value ->
            val error =
                assertFailsWith<OpenRouterWorkflowException> {
                    LocalLlmConfig.fromEnvironment(mapOf(LOCAL_MAX_COMPLETION_TOKENS_VARIABLE to value), LlmApi.LM_STUDIO)
                }
            assertEquals(FailureKind.CONFIGURATION, error.kind)
            assertTrue(LOCAL_MAX_COMPLETION_TOKENS_VARIABLE in error.message.orEmpty())
        }
        assertFailsWith<IllegalArgumentException> { LocalLlmConfig(LlmApi.LM_STUDIO, null, maxCompletionTokens = 0) }
    }

    @Test
    fun `local completion budget follows each full request and reported output maximum`() {
        FakeOpenRouterServer().use { server ->
            val client = LocalLlmClient(LocalLlmConfig(LlmApi.LM_STUDIO, null, "${server.baseUrl}/v1", 10_000))
            val system = "system"
            val user = "é".repeat(200)
            val catalog = """{"data":"${"x".repeat(1000)}"}"""
            val input = estimateInputTokens(system, buildUserMessage(user, catalog))
            val cases =
                listOf(
                    ModelLimits(input + 50_000, null) to 32_768L,
                    ModelLimits(input + 500, null) to 500L,
                    ModelLimits(input + 1, null) to 1L,
                    ModelLimits(input + 500, 100) to 100L,
                )
            cases.forEach { (limits, expected) ->
                runBlocking { client.complete(user, systemPrompt = system, context = catalog, limits = limits) }
                assertEquals(
                    expected.toString(),
                    Json
                        .parseToJsonElement(server.chatRequests.last().body)
                        .jsonObject
                        .getValue("max_tokens")
                        .jsonPrimitive.content,
                )
            }
            listOf(input, input - 1).forEach { context ->
                val before = server.chatRequests.size
                val error =
                    assertFailsWith<OpenRouterWorkflowException> {
                        runBlocking { client.complete(user, systemPrompt = system, context = catalog, limits = ModelLimits(context, null)) }
                    }
                assertEquals(FailureKind.REQUEST_TOO_LARGE, error.kind)
                assertEquals(before, server.chatRequests.size)
            }
        }
    }

    @Test
    fun `workflow sizes translation and recommendation independently with unchanged translation cap`() {
        FakeOpenRouterServer().use { server ->
            server.localModelsBody = FakeOpenRouterServer.localModelsJson(context = 10_000)
            server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
            val config = LocalLlmConfig(LlmApi.LM_STUDIO, null, "${server.baseUrl}/v1", 10_000)
            runBlocking { OpenRouterCarWorkflow(config, catalog).answer("x") }
            assertEquals(
                "2048",
                Json
                    .parseToJsonElement(server.chatRequests.first().body)
                    .jsonObject
                    .getValue("max_tokens")
                    .jsonPrimitive.content,
            )
            val body = Json.parseToJsonElement(server.chatRequests.last().body).jsonObject
            val messages = body.getValue("messages").jsonArray
            val estimated =
                estimateInputTokens(
                    messages
                        .first()
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content,
                    messages
                        .last()
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content,
                )
            assertEquals((10_000 - estimated).toString(), body.getValue("max_tokens").jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown local context uses configured target and reports dynamic limits on truncation`() {
        FakeOpenRouterServer().use { server ->
            val config =
                LocalLlmConfig.fromEnvironment(
                    mapOf(LOCAL_MAX_COMPLETION_TOKENS_VARIABLE to "65536"),
                    LlmApi.LLAMA_CPP,
                    "${server.baseUrl}/v1",
                )
            val client = LocalLlmClient(config)
            runBlocking { client.complete("x") }
            assertEquals(
                "65536",
                Json
                    .parseToJsonElement(server.chatRequests.last().body)
                    .jsonObject
                    .getValue("max_tokens")
                    .jsonPrimitive.content,
            )
            server.chatBody = FakeOpenRouterServer.chatResponse("partial", finishReason = "length")
            val input = estimateInputTokens("", "x")
            val error =
                assertFailsWith<OpenRouterWorkflowException> {
                    runBlocking { client.complete("x", limits = ModelLimits(input + 10, 100)) }
                }
            assertTrue("10-token output limit" in error.message.orEmpty())
            assertTrue("requested target: 65536" in error.message.orEmpty())
            assertTrue("estimated input: $input" in error.message.orEmpty())
            assertTrue("loaded context: ${input + 10}" in error.message.orEmpty())
            assertTrue("model output maximum: 100" in error.message.orEmpty())
            assertEquals("partial", error.rawOutput)
        }
    }

    @Test
    fun `context boundary includes output and UTF-8 bytes`() {
        val system = "rules"
        val user = "é".repeat(100)
        val required = estimateInputTokens(system, user) + 512
        validateRequestSize(system, user, ModelLimits(required, null), 512)
        assertFailsWith<OpenRouterWorkflowException> {
            validateRequestSize(system, user, ModelLimits(required - 1, null), 512)
        }
    }
}
