package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenRouterClientTest {
    private fun client(server: FakeOpenRouterServer) =
        OpenRouterClient(OpenRouterConfig("test-secret-key", FakeOpenRouterServer.FREE_MODEL, server.baseUrl, 10_000, 512))

    @Test
    fun `desktop and CLI environment configuration allows ten minutes for catalog inference`() {
        val config =
            OpenRouterConfig.fromEnvironment(
                mapOf(
                    API_KEY_VARIABLE to "test-secret-key",
                    MODEL_VARIABLE to FakeOpenRouterServer.FREE_MODEL,
                ),
            )

        assertEquals(600_000L, config.requestTimeoutMillis)
        assertEquals(
            600_000L,
            OpenRouterConfig("test-secret-key", FakeOpenRouterServer.FREE_MODEL).requestTimeoutMillis,
        )
    }

    @Test
    fun `complete verifies the model then sends only the user message`() {
        FakeOpenRouterServer().use { server ->
            val completion = runBlocking { client(server).complete("Say hi") }

            assertEquals("Fake answer", completion.text)
            assertEquals(FakeOpenRouterServer.FREE_MODEL, completion.requestedModelId)
            assertEquals(listOf("/api/v1/models", "/api/v1/chat/completions"), server.requests.map { it.path })
            val messages =
                Json
                    .parseToJsonElement(server.chatRequests.single().body)
                    .jsonObject
                    .getValue("messages")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("user"), messages.map { it.getValue("role").jsonPrimitive.content })
        }
    }

    @Test
    fun `complete wraps context as data blocks and rejects oversized requests before inference`() {
        FakeOpenRouterServer().use { server ->
            runBlocking { client(server).complete("Hybrids under 50k?", systemPrompt = "sys", context = """{"cars":[]}""") }
            val user =
                Json
                    .parseToJsonElement(server.chatRequests.single().body)
                    .jsonObject
                    .getValue("messages")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("role").jsonPrimitive.content == "user" }
                    .getValue("content")
                    .toString()
            assertTrue("<user_request>" in user && "<catalog_json>" in user && "Hybrids under 50k?" in user)
        }
        FakeOpenRouterServer().use { server ->
            server.modelsBody = FakeOpenRouterServer.modelsJson(contextLength = 500)
            val error =
                assertFailsWith<OpenRouterWorkflowException> {
                    runBlocking { client(server).complete("Hybrids?", context = "x".repeat(2_000)) }
                }
            assertEquals(FailureKind.REQUEST_TOO_LARGE, error.kind)
            assertTrue(server.chatRequests.isEmpty())
        }
    }

    @Test
    fun `complete rejects blank prompts and empty answers`() {
        FakeOpenRouterServer().use { server ->
            assertFailsWith<IllegalArgumentException> { runBlocking { client(server).complete(" ") } }
            assertEquals(0, server.requests.size)

            server.chatBody = FakeOpenRouterServer.chatResponse("")
            val error = assertFailsWith<OpenRouterWorkflowException> { runBlocking { client(server).complete("Say hi") } }
            assertEquals(FailureKind.EMPTY_RESPONSE, error.kind)
        }
    }

    @Test
    fun `workflow default sends larger completion budget capped by model metadata`() {
        listOf(16_384L to 8192L, 4096L to 4096L).forEach { (modelMaximum, expectedBudget) ->
            FakeOpenRouterServer().use { server ->
                server.modelsBody = FakeOpenRouterServer.modelsJson(maxCompletionTokens = modelMaximum)
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                val config = OpenRouterConfig("test-secret-key", FakeOpenRouterServer.FREE_MODEL, server.baseUrl)

                val answer = runBlocking { OpenRouterCarWorkflow(config, "{}").answer("Suggest a hybrid") }

                assertEquals(FakeOpenRouterServer.CAR_OUTPUT, answer.text)
                val body = Json.parseToJsonElement(server.chatRequests.last().body).jsonObject
                assertEquals(
                    expectedBudget,
                    body
                        .getValue("max_tokens")
                        .jsonPrimitive.content
                        .toLong(),
                )
                val system =
                    body
                        .getValue("messages")
                        .jsonArray
                        .first()
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content
                assertTrue("Keep details concise" in system)
                assertTrue("unless the user explicitly requests more" in system)
                assertTrue("necessary caveats" in system)
            }
        }
    }

    @Test
    fun `context gate reserves the larger default completion budget before inference`() {
        FakeOpenRouterServer().use { server ->
            val inputTokens = estimateInputTokens(SYSTEM_INSTRUCTIONS, buildUserMessage("Suggest a hybrid", "{}"))
            server.modelsBody =
                FakeOpenRouterServer.modelsJson(
                    contextLength = inputTokens + 8192 - 1,
                    maxCompletionTokens = 16_384,
                )
            val config = OpenRouterConfig("test-secret-key", FakeOpenRouterServer.FREE_MODEL, server.baseUrl)

            val error =
                assertFailsWith<OpenRouterWorkflowException> {
                    runBlocking { OpenRouterCarWorkflow(config, "{}").answer("Suggest a hybrid") }
                }

            assertEquals(FailureKind.REQUEST_TOO_LARGE, error.kind)
            assertTrue("8192 completion tokens" in error.message.orEmpty())
            assertEquals(1, server.chatRequests.size)
        }
    }

    @Test
    fun `length finish reason is truncation even without visible text`() {
        listOf("", "partial answer").forEach { text ->
            FakeOpenRouterServer().use { server ->
                server.chatBody = FakeOpenRouterServer.chatResponse(text, finishReason = "length")
                val error =
                    assertFailsWith<OpenRouterWorkflowException> {
                        runBlocking { client(server).complete("Say hi") }
                    }

                assertEquals(FailureKind.TRUNCATED_RESPONSE, error.kind)
                assertTrue("512-token output limit" in error.message.orEmpty())
                assertTrue("configured: 512; model maximum: 4096" in error.message.orEmpty())
                assertEquals(1, server.chatRequests.size)
            }
        }
    }
}
