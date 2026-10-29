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
}
