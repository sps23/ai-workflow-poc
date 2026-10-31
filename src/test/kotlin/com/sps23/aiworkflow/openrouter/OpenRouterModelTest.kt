package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenRouterModelTest {
    @Test
    fun `catalog groups by all pricing components not ID suffix and omits unverifiable entries`() {
        val models = parseOpenRouterModels(catalogJson())
        assertEquals(listOf("vendor/zero-cost", "vendor/paid", "vendor/request-cost"), models.map { it.id })
        assertEquals(listOf(true, false, false), models.map { it.isFree })
        assertEquals("0.000002", models[1].promptPrice)
        assertEquals("0.000006", models[1].completionPrice)
        models.forEach { assertEquals(it.limits, verifyOpenRouterModel(catalogJson(), it.id)) }
    }

    @Test
    fun `public discovery sends only one unauthenticated metadata request`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                server.modelsBody = catalogJson()
                assertEquals(3, fetchOpenRouterModels(server.baseUrl).size)
                assertEquals("/api/v1/models", server.requests.single().path)
                assertEquals("GET", server.requests.single().method)
                assertNull(server.requests.single().authorization)
                assertTrue(server.chatRequests.isEmpty())
            }
        }

    @Test
    fun `failed or malformed discovery surfaces a typed failure without inference`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                for (body in listOf("invalid", """{"data":[]}""", """{"data":{}}""")) {
                    server.modelsBody = body
                    val error = assertFailsWith<OpenRouterWorkflowException> { fetchOpenRouterModels(server.baseUrl) }
                    assertEquals(FailureKind.MODEL_UNAVAILABLE, error.kind)
                }
                server.modelsStatus = 503
                val error = assertFailsWith<OpenRouterWorkflowException> { fetchOpenRouterModels(server.baseUrl) }
                assertEquals(503, error.diagnostics?.httpStatus)
                assertTrue(server.chatRequests.isEmpty())
            }
        }

    @Test
    fun `arbitrary paid model is pinned for both inference stages without fallback fields`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                val id = "vendor/custom-paid-model"
                server.modelsBody = FakeOpenRouterServer.modelsJson(id = id, promptPrice = "0.000002", completionPrice = "0.000006")
                server.chatBody = FakeOpenRouterServer.chatResponse(FakeOpenRouterServer.CAR_OUTPUT)
                OpenRouterCarWorkflow(OpenRouterConfig("fake-key", id, server.baseUrl, maxCompletionTokens = 512), "{}").answer("x")
                assertEquals(2, server.chatRequests.size)
                server.chatRequests.forEach {
                    val request = Json.parseToJsonElement(it.body).jsonObject
                    assertEquals("\"$id\"", request["model"].toString())
                    assertFalse("models" in request)
                    assertFalse("route" in request)
                    assertFalse("provider" in request)
                }
            }
        }

    companion object {
        fun catalogJson(): String =
            buildJsonObject {
                putJsonArray("data") {
                    listOf(
                        FakeOpenRouterServer.modelsJson(id = "vendor/paid", promptPrice = "0.000002", completionPrice = "0.000006"),
                        FakeOpenRouterServer.modelsJson(id = "vendor/zero-cost"),
                        FakeOpenRouterServer.modelsJson(id = "vendor/request-cost", extraPricing = ""","web_search":"0.01""""),
                        FakeOpenRouterServer.modelsJson(id = "vendor/image", outputs = "\"image\""),
                        FakeOpenRouterServer.modelsJson(id = "vendor/unknown-cost", promptPrice = "unknown"),
                        FakeOpenRouterServer.modelsJson(id = "vendor/invalid:free", promptPrice = "1"),
                        FakeOpenRouterServer.modelsJson(id = "vendor/negative", promptPrice = "-1"),
                        FakeOpenRouterServer.modelsJson(id = "vendor/no-context", contextLength = 0),
                    ).forEach { json ->
                        Json
                            .parseToJsonElement(json)
                            .jsonObject
                            .getValue("data")
                            .jsonArray
                            .forEach { add(it) }
                    }
                }
            }.toString()
    }
}
