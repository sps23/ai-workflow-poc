package com.sps23.aiworkflow.openrouter

import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmMetricsTest {
    private val secret = "private-api-key"
    private val cached = LlmRequest("x", emptyList(), emptyList(), emptyList(), listOf("x"))
    private val catalog = """{"privateCatalog":"catalog-marker"}"""

    private fun backend(
        server: FakeOpenRouterServer,
        local: Boolean,
    ): ChatBackend =
        if (local) {
            LocalLlmClient(LocalLlmConfig(LlmApi.LM_STUDIO, secret, "${server.baseUrl}/v1", 10_000, 512))
        } else {
            OpenRouterClient(OpenRouterConfig(secret, FakeOpenRouterServer.FREE_MODEL, server.baseUrl, 10_000, 512))
        }

    private fun envelope(
        text: String = FakeOpenRouterServer.CAR_OUTPUT,
        finish: String = "stop",
    ): String {
        val root = Json.parseToJsonElement(FakeOpenRouterServer.chatResponse(text, finish)).jsonObject
        return JsonObject(
            root +
                mapOf(
                    "model" to JsonPrimitive("actual/model"),
                    "provider" to JsonPrimitive("Example provider"),
                    "usage" to
                        Json.parseToJsonElement(
                            """{"prompt_tokens":100,"completion_tokens":40,"total_tokens":140,
                        "completion_tokens_details":{"reasoning_tokens":30},
                        "prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":5},
                        "cost":0.001,"private":"usage-marker"}""",
                        ),
                    "stats" to Json.parseToJsonElement("""{"tokens_per_second":25.5,"time_to_first_token":0.75}"""),
                    "private" to JsonPrimitive("envelope-marker"),
                ),
        ).toString()
    }

    @Test
    fun `both backends capture real usage limits timing and provider identity without exposing content`() {
        listOf(false, true).forEach { local ->
            FakeOpenRouterServer().use { server ->
                server.chatBody = envelope()
                server.chatDelayMillis = 20
                val completion =
                    runBlocking {
                        backend(
                            server,
                            local,
                        ).complete("private-user-marker", systemPrompt = "private-system-marker", context = catalog)
                    }
                val metrics = assertNotNull(completion.metrics)
                assertEquals("actual/model", completion.responseModelId)
                assertEquals("actual/model", metrics.responseModelId)
                assertEquals("Example provider", metrics.provider)
                assertEquals("gen-1", metrics.responseId)
                assertEquals("stop", metrics.finishReason)
                assertEquals(200, metrics.httpStatus)
                assertTrue(metrics.dispatched)
                assertEquals(512, metrics.requestedOutputTokens)
                assertEquals(512, metrics.effectiveOutputTokens)
                assertEquals(if (local) 262_144L else 100_000L, metrics.contextLength)
                assertEquals(TokenUsage(100, 40, 140, 30, 80, 5, 0.001), metrics.usage)
                assertEquals(25.5, metrics.reportedGenerationTokensPerSecond)
                assertEquals(0.75, metrics.reportedTimeToFirstTokenSeconds)
                assertTrue(metrics.elapsedMillis > 0)
                assertEquals(40_000.0 / metrics.elapsedMillis, metrics.completionTokensPerSecondOverRequest)
                val message = buildUserMessage("private-user-marker", catalog)
                assertEquals(estimateInputTokens("private-system-marker", message), metrics.estimatedInputTokens)
                assertEquals(("private-system-marker" + message).toByteArray().size.toLong(), metrics.inputUtf8Bytes)
                val json =
                    WorkflowMetadataCodec.encode(
                        WorkflowMetadata(elapsedMillis = 1, stages = listOf(StageMetrics("test", "SUCCEEDED", 1, request = metrics))),
                    )
                listOf(
                    secret,
                    "private-user-marker",
                    "private-system-marker",
                    "catalog-marker",
                    "envelope-marker",
                    "usage-marker",
                    "Requires comfort pack",
                ).forEach {
                    assertFalse(it in json, it)
                }
                assertEquals(1, server.chatRequests.size)
            }
        }
    }

    @Test
    fun `workflow success separates metadata translation and recommendation while reuse has no invented usage`() {
        listOf(false, true).forEach { local ->
            FakeOpenRouterServer().use { server ->
                server.chatBody = envelope()
                val workflow = OpenRouterCarWorkflow(backend(server, local), catalog)
                val metadata = assertNotNull(runBlocking { workflow.answer("x") }.metadata)
                assertEquals(listOf("Model metadata", "Request translation", "Car recommendation"), metadata.stages.map { it.stage })
                assertTrue(metadata.stages.all { it.outcome == "SUCCEEDED" })
                assertNull(metadata.stages.first().request)
                assertEquals(
                    10,
                    metadata.stages[1]
                        .request
                        ?.usage
                        ?.promptTokens,
                )
                assertEquals(
                    100,
                    metadata.stages[2]
                        .request
                        ?.usage
                        ?.promptTokens,
                )
                assertTrue(metadata.elapsedMillis >= metadata.stages.sumOf { it.elapsedMillis })
                val reused = assertNotNull(runBlocking { workflow.answer("x", cached) }.metadata)
                val translation = reused.stages.single { it.stage == "Request translation" }
                assertEquals("REUSED", translation.outcome)
                assertEquals(0, translation.elapsedMillis)
                assertNull(translation.request)
                assertEquals(3, server.chatRequests.size)
            }
        }
    }

    @Test
    fun `truncated empty and invalid responses retain usage from both backends and completed translation`() {
        listOf(false, true).forEach { local ->
            listOf(
                Triple("partial", "length", FailureKind.TRUNCATED_RESPONSE),
                Triple("", "stop", FailureKind.EMPTY_RESPONSE),
                Triple("invalid selection", "stop", FailureKind.INVALID_RESPONSE),
            ).forEach { (text, finish, kind) ->
                FakeOpenRouterServer().use { server ->
                    server.chatBody = envelope(text, finish)
                    val error =
                        assertFailsWith<OpenRouterWorkflowException> {
                            runBlocking { OpenRouterCarWorkflow(backend(server, local), catalog).answer("x") }
                        }
                    assertEquals(kind, error.kind)
                    val metadata = assertNotNull(error.metadata)
                    assertEquals(3, metadata.stages.size)
                    assertEquals("SUCCEEDED", metadata.stages[1].outcome)
                    val failed = metadata.stages.last()
                    assertEquals("FAILED", failed.outcome)
                    assertEquals(kind.name, failed.failureKind)
                    assertEquals(140, failed.request?.usage?.totalTokens)
                    assertEquals(finish, failed.request?.finishReason)
                    assertEquals(200, failed.request?.httpStatus)
                }
            }
        }
    }

    @Test
    fun `HTTP errors and malformed bodies keep dispatch and status but never invent usage`() {
        listOf(false, true).forEach { local ->
            listOf(429 to """{"error":{"message":"busy"}}""", 200 to "not JSON").forEach { (status, body) ->
                FakeOpenRouterServer().use { server ->
                    server.chatStatus = status
                    server.chatBody = body
                    val error =
                        assertFailsWith<OpenRouterWorkflowException> {
                            runBlocking { OpenRouterCarWorkflow(backend(server, local), catalog).answer("x", cached) }
                        }
                    val failed = assertNotNull(error.metadata).stages.last()
                    assertEquals("FAILED", failed.outcome)
                    assertTrue(assertNotNull(failed.request).dispatched)
                    assertEquals(status, failed.request.httpStatus)
                    assertNull(failed.request.usage)
                }
            }
        }
    }

    @Test
    fun `preflight failure exports size and limits with no usage or dispatch`() {
        listOf(false, true).forEach { local ->
            FakeOpenRouterServer().use { server ->
                server.modelsBody = FakeOpenRouterServer.modelsJson(contextLength = 100)
                server.localModelsBody = FakeOpenRouterServer.localModelsJson(context = 100)
                val error =
                    assertFailsWith<OpenRouterWorkflowException> {
                        runBlocking { OpenRouterCarWorkflow(backend(server, local), catalog).answer("x") }
                    }
                val request = assertNotNull(assertNotNull(error.metadata).stages.last().request)
                assertFalse(request.dispatched)
                assertEquals(100, request.contextLength)
                assertTrue(request.estimatedInputTokens > 100)
                assertNull(request.usage)
                assertNull(request.httpStatus)
                assertTrue(server.chatRequests.isEmpty())
            }
        }
    }

    @Test
    fun `optional missing invalid and zero metrics remain distinct and credential strings are redacted`() {
        val recorder =
            RequestMetricsRecorder(
                RequestMetrics("local", "model", inputUtf8Bytes = 1, estimatedInputTokens = 1, requestedOutputTokens = 1),
            )
        recorder.response(
            metricsEnvelope(
                """{"model":"$secret","provider":"Bearer secret-token","id":"$secret",
                    "usage":{"prompt_tokens":0,"completion_tokens":-1,"total_tokens":"123",
                    "completion_tokens_details":{"reasoning_tokens":1.5},
                    "prompt_tokens_details":{"cached_tokens":0},"cost":-2},
                    "stats":{"tokens_per_second":"fake","time_to_first_token":-1}}""",
            ),
            200,
            secret,
        )
        val usage = assertNotNull(recorder.metrics.usage)
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.cachedPromptTokens)
        assertNull(usage.completionTokens)
        assertNull(usage.totalTokens)
        assertNull(usage.reasoningTokens)
        assertNull(usage.cost)
        assertNull(recorder.metrics.reportedGenerationTokensPerSecond)
        assertNull(recorder.metrics.reportedTimeToFirstTokenSeconds)
        val json =
            WorkflowMetadataCodec.encode(
                WorkflowMetadata(elapsedMillis = 0, stages = listOf(StageMetrics("test", "SUCCEEDED", 0, request = recorder.metrics))),
            )
        assertFalse(secret in json)
        assertFalse("secret-token" in json)
        recorder.response(metricsEnvelope("""{"usage":null}"""), 200, null)
        assertNull(recorder.metrics.usage)
        assertNull(recorder.metrics.responseModelId)
        assertEquals(
            1,
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("schemaVersion")
                .jsonPrimitive.content
                .toInt(),
        )
        val exportedRequest =
            Json
                .parseToJsonElement(
                    json,
                ).jsonObject
                .getValue("stages")
                .jsonArray
                .single()
                .jsonObject
                .getValue("request")
                .jsonObject
        assertEquals(
            "null",
            exportedRequest
                .getValue("usage")
                .jsonObject
                .getValue("completionTokens")
                .toString(),
        )
    }
}
