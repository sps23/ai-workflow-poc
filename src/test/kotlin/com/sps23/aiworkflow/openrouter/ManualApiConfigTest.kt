package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualApiConfigTest {
    @Test
    fun `manual is last and URLs require HTTPS except loopback HTTP`() {
        assertEquals(LlmApi.MANUAL, LlmApi.entries.last())
        for (url in listOf(
            "https://example.com/v1",
            "https://example.com/custom/api",
            "http://localhost:1234/v1",
            "http://127.0.0.1:8080/v1",
            "http://127.1.2.3/v1",
            "http://[::1]:8080/v1",
        )) {
            assertEquals(url, validateManualBaseUrl(" $url/ "))
        }
        for (url in listOf(
            "",
            "/v1",
            "example.com",
            "http://example.com/v1",
            "http://192.168.1.2/v1",
            "ftp://example.com",
            "https://user:password@example.com/v1",
            "https://example.com/v1?key=secret",
            "https://example.com/v1#secret",
            "https://example.com:70000/v1",
            "https://example.com:0/v1",
            "https://example.com/v1/chat/completions/",
            "https://example.com/v1/models",
            "http://127.0.0.999/v1",
        )) {
            val error = assertFailsWith<OpenRouterWorkflowException> { validateManualBaseUrl(url) }
            assertEquals(FailureKind.CONFIGURATION, error.kind)
            assertFalse("password" in error.message.orEmpty())
            assertFalse("secret" in error.message.orEmpty())
        }
    }

    @Test
    fun `manual discovery optional auth and chat share exact supplied base path and model`() =
        runBlocking {
            for (key in listOf(null, " ", " test-key ")) {
                FakeOpenRouterServer().use { server ->
                    server.openAiModelsBody = """{"data":[{"id":"manual/model"}]}"""
                    // /api/v1/models also has OpenAI-shaped IDs, allowing both common base paths offline.
                    val url = "${server.baseUrl}/custom/api"
                    assertTrue(fetchManualModels(url, key).contains(FakeOpenRouterServer.FREE_MODEL))
                    server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                    val config = ManualApiConfig("$url/", key, " manual/model ", 512)
                    val answer = OpenRouterCarWorkflow(config.backend(), "{}").answer("x")
                    assertEquals("manual/model", answer.requestedModelId)
                    assertEquals(3, server.requests.size)
                    server.requests.forEach {
                        if (key.isNullOrBlank()) assertNull(it.authorization) else assertEquals("Bearer test-key", it.authorization)
                    }
                    server.chatRequests.forEach {
                        assertEquals("/custom/api/chat/completions", it.path)
                        val body = Json.parseToJsonElement(it.body).jsonObject
                        assertEquals("manual/model", body.getValue("model").jsonPrimitive.content)
                        assertEquals("512", body.getValue("max_tokens").jsonPrimitive.content)
                        assertFalse("temperature" in body)
                        assertFalse("models" in body)
                    }
                    assertFalse("test-key" in config.toString())
                    assertEquals(
                        LlmApi.MANUAL.label,
                        answer.metadata
                            ?.stages
                            ?.last()
                            ?.request
                            ?.api,
                    )
                    assertNull(
                        answer.metadata
                            ?.stages
                            ?.last()
                            ?.request
                            ?.contextLength,
                    )
                }
            }
        }

    @Test
    fun `missing model discovery does not block typed inference and provider auth errors are redacted`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.modelsStatus = 404
                assertFailsWith<OpenRouterWorkflowException> { fetchManualModels("${server.baseUrl}/v1", null) }
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                val workflow =
                    OpenRouterCarWorkflow(ManualApiConfig("${server.baseUrl}/v1", "manual-secret", "manual/model", 512).backend(), "{}")
                workflow.answer("x")
                assertEquals(2, server.chatRequests.size)
                server.chatStatus = 401
                server.chatBody = """{"error":{"message":"invalid manual-secret"}}"""
                val error = assertFailsWith<OpenRouterWorkflowException> { workflow.answer("y") }
                assertEquals(FailureKind.AUTHENTICATION, error.kind)
                assertFalse("manual-secret" in error.diagnostics?.providerMessage.orEmpty())
                assertTrue("manual API key" in error.message.orEmpty())
                assertEquals(3, server.chatRequests.size)
            }
        }

    @Test
    fun `manual blank model is rejected before dispatch`() {
        assertFailsWith<OpenRouterWorkflowException> { ManualApiConfig("https://example.com/v1", null, "") }
    }

    @Test
    fun `manual metadata and chat redirects never forward credentials or catalog`() =
        runBlocking {
            FakeOpenRouterServer().use { destination ->
                FakeOpenRouterServer().use { server ->
                    server.redirectLocation = "${destination.baseUrl}/v1/models"
                    server.modelsStatus = 307
                    assertFailsWith<OpenRouterWorkflowException> { fetchManualModels("${server.baseUrl}/v1", "manual-key") }
                    server.redirectLocation = "${destination.baseUrl}/v1/chat/completions"
                    server.chatStatus = 307
                    val config = ManualApiConfig("${server.baseUrl}/v1", "manual-key", "model", 512)
                    val workflow = OpenRouterCarWorkflow(config.backend(), "{}")
                    assertFailsWith<OpenRouterWorkflowException> { workflow.answer("x") }
                    assertEquals(2, server.requests.size)
                    assertTrue(destination.requests.isEmpty())
                }
            }
        }
}
