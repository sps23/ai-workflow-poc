package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiPdfClientTest {
    private class Captured(
        val path: String,
        val apiKey: String?,
        val query: String?,
        val body: String,
    )

    /** Loopback fake of Gemini model discovery and generateContent. */
    private class FakeGemini : AutoCloseable {
        @Volatile var status = 200

        @Volatile var body = response("{\"title\":\"x\"}")

        @Volatile var delayMillis = 0L

        val requests = CopyOnWriteArrayList<Captured>()
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.use {
                        requests +=
                            Captured(
                                it.requestURI.path,
                                it.requestHeaders.getFirst("x-goog-api-key"),
                                it.requestURI.rawQuery,
                                it.requestBody.readBytes().toString(Charsets.UTF_8),
                            )
                        val (code, payload) =
                            when (it.requestURI.path) {
                                "/v1beta/models" -> {
                                    200 to MODELS
                                }

                                "/v1beta/models/gemini-test:generateContent" -> {
                                    if (delayMillis > 0) Thread.sleep(delayMillis)
                                    status to body
                                }

                                else -> {
                                    404 to "{}"
                                }
                            }
                        val bytes = payload.toByteArray()
                        it.sendResponseHeaders(code, bytes.size.toLong())
                        it.responseBody.write(bytes)
                    }
                }
                start()
            }

        val baseUrl = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    private val fake = FakeGemini()
    private val pdf = "%PDF-1.7\nfake".toByteArray()

    private fun client(
        temperature: Double? = null,
        timeoutMillis: Long = 10_000,
    ) = GeminiPdfClient(
        GeminiPdfConfig("secret-key", "models/gemini-test", fake.baseUrl, temperature, requestTimeoutMillis = timeoutMillis),
    )

    private fun extract(
        client: GeminiPdfClient = client(),
        schema: JsonObject? = SCHEMA,
    ) = runBlocking { client.extract(pdf, "system", "user", schema, 1000) }

    private fun failure(block: () -> Unit): OpenRouterWorkflowException = assertFailsWith<OpenRouterWorkflowException> { block() }

    @AfterTest
    fun close() = fake.close()

    @Test
    fun `request sends inline pdf, prompts, schema and key header only`() {
        val result = extract()
        assertEquals("{\"title\":\"x\"}", result.text)
        val request = fake.requests.single()
        assertEquals("/v1beta/models/gemini-test:generateContent", request.path)
        assertEquals("secret-key", request.apiKey)
        assertNull(request.query)
        assertFalse(request.body.contains("secret-key"))

        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(
            "system",
            body["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("application/pdf", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            java.util.Base64
                .getEncoder()
                .encodeToString(pdf),
            inline["data"]!!.jsonPrimitive.content,
        )
        assertEquals("user", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(SCHEMA, config["responseJsonSchema"])
        assertEquals("1000", config["maxOutputTokens"]!!.jsonPrimitive.content)
        assertFalse("temperature" in config)
    }

    @Test
    fun `temperature and schema are optional`() {
        extract(client(temperature = 0.5), schema = null)
        val config = Json.parseToJsonElement(fake.requests.single().body).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("0.5", config["temperature"]!!.jsonPrimitive.content)
        assertFalse("responseJsonSchema" in config)
    }

    @Test
    fun `usage, model version and thought filtering are parsed`() {
        fake.body =
            """
            {"candidates":[{"content":{"parts":[{"text":"plan","thought":true},{"text":"{\"a\":"},{"text":"1}"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"totalTokenCount":125,
             "cachedContentTokenCount":40},
             "modelVersion":"gemini-test-001","responseId":"response-123"}
            """.trimIndent()
        val result = extract()
        assertEquals("{\"a\":1}", result.text)
        assertEquals("STOP", result.finishReason)
        assertEquals(GeminiUsage(100, 20, 5, 125), result.usage)
        assertEquals(25, result.usage.outputTokens)
        assertEquals("gemini-test-001", result.modelVersion)
        val metrics = assertNotNull(result.metrics)
        assertTrue(metrics.dispatched)
        assertEquals(200, metrics.httpStatus)
        assertEquals("gemini-test", metrics.requestedModelId)
        assertEquals("gemini-test-001", metrics.responseModelId)
        assertEquals("response-123", metrics.responseId)
        assertEquals("STOP", metrics.finishReason)
        assertNull(metrics.estimatedInputTokens)
        assertEquals(pdf.size.toLong() + 6 + 4 + SCHEMA.toString().toByteArray().size, metrics.inputUtf8Bytes)
        assertEquals(100, metrics.usage?.promptTokens)
        assertEquals(25, metrics.usage?.completionTokens)
        assertEquals(5, metrics.usage?.reasoningTokens)
        assertEquals(125, metrics.usage?.totalTokens)
        assertEquals(40, metrics.usage?.cachedPromptTokens)
        assertNull(metrics.usage?.cacheWriteTokens)
        assertNull(metrics.usage?.cost)
        assertTrue(metrics.elapsedMillis > 0)
        assertEquals(25 * 1000.0 / metrics.elapsedMillis, metrics.completionTokensPerSecondOverRequest)
        assertNull(metrics.reportedGenerationTokensPerSecond)
        assertNull(metrics.reportedTimeToFirstTokenSeconds)
    }

    @Test
    fun `MAX_TOKENS is a truncation failure that keeps the raw output`() {
        fake.body =
            """{"candidates":[{"content":{"parts":[{"text":"{\"title\":"}]},"finishReason":"MAX_TOKENS"}],
                "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"totalTokenCount":125}}"""
        val e = failure { extract() }
        assertEquals(FailureKind.TRUNCATED_RESPONSE, e.kind)
        assertEquals("{\"title\":", e.rawOutput)
        assertEquals(25, e.requestMetrics?.usage?.completionTokens)
        assertEquals("MAX_TOKENS", e.requestMetrics?.finishReason)
        assertEquals(200, e.requestMetrics?.httpStatus)
    }

    @Test
    fun `non-STOP, blocked and empty answers fail`() {
        fake.body = """{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"RECITATION"}]}"""
        assertEquals(FailureKind.INVALID_RESPONSE, failure { extract() }.kind)
        fake.body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertEquals(FailureKind.INVALID_RESPONSE, failure { extract() }.kind)
        fake.body = """{"candidates":[]}"""
        assertEquals(FailureKind.EMPTY_RESPONSE, failure { extract() }.kind)
        fake.body = """{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""
        assertEquals(FailureKind.EMPTY_RESPONSE, failure { extract() }.kind)
        fake.body = "not json"
        assertEquals(FailureKind.INVALID_RESPONSE, failure { extract() }.kind)
    }

    @Test
    fun `HTTP errors map to failure kinds with redacted diagnostics`() {
        val cases =
            mapOf(
                401 to FailureKind.AUTHENTICATION,
                403 to FailureKind.AUTHENTICATION,
                404 to FailureKind.MODEL_UNAVAILABLE,
                413 to FailureKind.REQUEST_TOO_LARGE,
                429 to FailureKind.RATE_LIMIT,
                504 to FailureKind.TIMEOUT,
                500 to FailureKind.PROVIDER,
                400 to FailureKind.PROVIDER,
            )
        cases.forEach { (status, kind) ->
            fake.status = status
            fake.body = """{"error":{"code":$status,"message":"bad secret-key","status":"X"}}"""
            val e = failure { extract() }
            assertEquals(kind, e.kind, "HTTP $status")
            assertEquals(status, e.diagnostics?.httpStatus)
            assertEquals("bad [REDACTED]", e.diagnostics?.providerMessage)
            assertEquals(status, e.requestMetrics?.httpStatus)
            assertTrue(assertNotNull(e.requestMetrics).dispatched)
            assertNull(e.requestMetrics.usage)
        }
        fake.status = 400
        fake.body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertEquals(FailureKind.AUTHENTICATION, failure { extract() }.kind)
    }

    @Test
    fun `timeout is reported as TIMEOUT`() {
        fake.delayMillis = 1_500
        val failure = failure { extract(client(timeoutMillis = 300)) }
        assertEquals(FailureKind.TIMEOUT, failure.kind)
        assertTrue(assertNotNull(failure.requestMetrics).dispatched)
        assertNull(failure.requestMetrics.httpStatus)
        assertNull(failure.requestMetrics.usage)
    }

    @Test
    fun `size and header gates fail before any request`() {
        val tooLarge = ByteArray((GEMINI_PDF_MAX_BYTES + 1).toInt()).also { "%PDF-".toByteArray().copyInto(it) }
        val large = failure { runBlocking { client().extract(tooLarge, "s", "u", null, 10) } }
        assertEquals(FailureKind.REQUEST_TOO_LARGE, large.kind)
        assertFalse(assertNotNull(large.requestMetrics).dispatched)
        val pointer = "version https://git-lfs.github.com/spec/v1\n".toByteArray()
        val notPdf = failure { runBlocking { client().extract(pointer, "s", "u", null, 10) } }
        assertEquals(FailureKind.CONFIGURATION, notPdf.kind)
        assertTrue(notPdf.message!!.contains("git lfs pull"))
        assertFalse(assertNotNull(notPdf.requestMetrics).dispatched)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `output token limit comes from model discovery`() {
        val client = client()
        assertEquals(65_536, runBlocking { client.outputTokenLimit() })
        assertEquals("secret-key", fake.requests.single().apiKey)
        val metrics = assertNotNull(extract(client).metrics)
        assertEquals(1_048_576, metrics.contextLength)
        assertEquals(65_536, metrics.modelMaxOutputTokens)
        assertEquals(GEMINI_PDF_MAX_OUTPUT_TOKENS, metrics.requestedOutputTokens)
        assertEquals(1000, metrics.effectiveOutputTokens)
        val missing = GeminiPdfClient(GeminiPdfConfig("secret-key", "gemini-unknown", fake.baseUrl))
        assertEquals(FailureKind.MODEL_UNAVAILABLE, failure { runBlocking { missing.outputTokenLimit() } }.kind)
    }

    @Test
    fun `metadata excludes body content and credentials and does not invent malformed token counts`() {
        fake.body =
            """{"candidates":[{"content":{"parts":[{"text":"private-output-marker","thought":true},
                {"text":"answer"}]},"finishReason":"STOP"}],
                "usageMetadata":{"promptTokenCount":-1,"candidatesTokenCount":"20","thoughtsTokenCount":2.5,
                "cachedContentTokenCount":-5},
                "modelVersion":"secret-key model","responseId":"secret-key id","arbitrary":"private-metadata-marker"}"""
        val metrics = assertNotNull(extract().metrics)
        assertNull(metrics.usage?.promptTokens)
        assertNull(metrics.usage?.completionTokens)
        assertNull(metrics.usage?.reasoningTokens)
        assertNull(metrics.usage?.totalTokens)
        assertNull(metrics.usage?.cachedPromptTokens)
        val encoded =
            WorkflowMetadataCodec.encode(
                WorkflowMetadata(elapsedMillis = 0, stages = listOf(StageMetrics("PDF extraction", "SUCCEEDED", 0, request = metrics))),
            )
        listOf("secret-key", "private-output-marker", "private-metadata-marker", "systemInstruction", "inlineData").forEach {
            assertFalse(encoded.contains(it), it)
        }
        assertTrue(encoded.contains("[REDACTED]"))
    }

    @Test
    fun `native metrics reach successful and invalid extraction outcomes without extra API calls`() =
        runBlocking {
            val dir = createTempDirectory("gemini-pdf-metrics")
            try {
                val pdfFile = dir.resolve("brochure.pdf").also { it.writeBytes(pdf) }
                val definition = PdfExtractionPrompts.modelDefinition()
                val example =
                    definition.substring(
                        definition.indexOf("{", definition.indexOf("## Example")),
                        definition.lastIndexOf("}") + 1,
                    )
                listOf(example, """{"title":""}""").forEach { text ->
                    fake.body =
                        JsonObject(
                            Json.parseToJsonElement(response(text)).jsonObject +
                                (
                                    "usageMetadata" to
                                        buildJsonObject {
                                            put("promptTokenCount", 100)
                                            put("candidatesTokenCount", 20)
                                            put("thoughtsTokenCount", 5)
                                            put("totalTokenCount", 125)
                                        }
                                ),
                        ).toString()
                    val outcome = PdfExtractionService(client()).extract(pdfFile, dir.resolve("out")) { true }
                    val metadata =
                        if (text == example) {
                            val written = assertIs<PdfExtractionOutcome.Written>(outcome)
                            assertEquals(written.extraction, PdfExtractionFile.decode(written.file.readText()))
                            assertNotNull(written.metadata)
                        } else {
                            assertNotNull(assertIs<PdfExtractionOutcome.Failed>(outcome).metadata)
                        }
                    assertEquals(listOf("Model metadata", "PDF extraction"), metadata.stages.map { it.stage })
                    val request = assertNotNull(metadata.stages.last().request)
                    assertEquals(25, request.usage?.completionTokens)
                    assertEquals(200, request.httpStatus)
                    assertEquals(65_536, request.effectiveOutputTokens)
                    assertEquals(1_048_576, request.contextLength)
                    assertNull(request.estimatedInputTokens)
                }
                assertEquals(
                    listOf(
                        "/v1beta/models",
                        "/v1beta/models/gemini-test:generateContent",
                        "/v1beta/models",
                        "/v1beta/models/gemini-test:generateContent",
                    ),
                    fake.requests.map { it.path },
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `config requires key and model and hides the key`() {
        assertEquals(FailureKind.CONFIGURATION, failure { GeminiPdfConfig(" ") }.kind)
        assertEquals(FailureKind.CONFIGURATION, failure { GeminiPdfConfig("k", "models/") }.kind)
        assertEquals(GEMINI_PDF_DEFAULT_MODEL, GeminiPdfConfig("k").modelId)
        assertFalse(GeminiPdfConfig("secret-key").toString().contains("secret-key"))
    }

    companion object {
        val SCHEMA = buildJsonObject { put("type", "object") }

        const val MODELS =
            """{"models":[{"name":"models/gemini-test","displayName":"Gemini Test","inputTokenLimit":1048576,
              "outputTokenLimit":65536,"supportedGenerationMethods":["generateContent"]}]}"""

        fun response(text: String): String =
            """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(text)}}]},"finishReason":"STOP"}]}"""
    }
}
