package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.ChatBackend
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.LocalLlmClient
import com.sps23.aiworkflow.openrouter.LocalLlmConfig
import com.sps23.aiworkflow.openrouter.OpenAiClient
import com.sps23.aiworkflow.openrouter.OpenAiConfig
import com.sps23.aiworkflow.openrouter.OpenRouterClient
import com.sps23.aiworkflow.openrouter.OpenRouterConfig
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.ui.PdfExtractorFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextPdfClientTest {
    @Test
    fun `each non-Google backend receives text and schema and writes validated extraction`() =
        runBlocking {
            for (api in listOf(LlmApi.OPENAI, LlmApi.OPENROUTER, LlmApi.LM_STUDIO, LlmApi.LLAMA_CPP, LlmApi.OLLAMA, LlmApi.MANUAL)) {
                FakeOpenRouterServer().use { server ->
                    val dir = createTempDirectory("pdf-text-test")
                    try {
                        val model = if (api == LlmApi.OPENROUTER) "openrouter/free" else "gpt-test"
                        if (api == LlmApi.OPENROUTER) server.modelsBody = FakeOpenRouterServer.modelsJson(id = model)
                        val backend: ChatBackend =
                            when (api) {
                                LlmApi.OPENAI -> {
                                    OpenAiClient(OpenAiConfig("fake-key", model, server.baseUrl))
                                }

                                LlmApi.OPENROUTER -> {
                                    OpenRouterClient(OpenRouterConfig("fake-key", model, server.baseUrl))
                                }

                                else -> {
                                    LocalLlmClient(
                                        LocalLlmConfig(
                                            api,
                                            "fake-key",
                                            "${server.baseUrl}/v1",
                                            modelId = if (api == LlmApi.MANUAL) model else null,
                                        ),
                                    )
                                }
                            }
                        server.chatBody =
                            FakeOpenRouterServer.chatResponse(
                                ExtractedVehicle.strictJson.encodeToString(ExtractedVehicle.serializer(), PdfExtractorFixtures.vehicle),
                            )
                        val pdf = dir.resolve("brochure.pdf")
                        val bytes = pdfBytes("Published brochure facts EUR 26990")
                        pdf.writeBytes(bytes)
                        val outcome = PdfExtractionService(TextPdfClient(model, backend)).extract(pdf, dir) { true }
                        val written = assertIs<PdfExtractionOutcome.Written>(outcome, "${api.label}: $outcome")
                        assertEquals(PdfExtractorFixtures.vehicle, written.extraction.vehicle)
                        assertEquals(PdfExtractionService.sha256(bytes), written.extraction.provenance.pdfSha256)
                        val request = server.chatRequests.single()
                        val body = Json.parseToJsonElement(request.body).jsonObject
                        val message =
                            body
                                .getValue("messages")
                                .jsonArray
                                .last()
                                .jsonObject
                                .getValue("content")
                                .jsonPrimitive.content
                        assertTrue("Published brochure facts EUR 26990" in message)
                        assertTrue("\"properties\"" in message)
                        assertTrue("<pdf_text>" in message)
                        assertTrue("inlineData" !in request.body)
                        assertEquals(
                            if (api == LlmApi.OPENROUTER) "/api/v1/chat/completions" else "/v1/chat/completions",
                            request.path,
                        )
                        if (api == LlmApi.OPENAI || api == LlmApi.OPENROUTER || api == LlmApi.MANUAL) {
                            assertEquals(model, body.getValue("model").jsonPrimitive.content)
                        }
                        assertTrue(written.metadata!!.notes.contains("PDF text only"))
                    } finally {
                        dir.toFile().deleteRecursively()
                    }
                }
            }
        }

    @Test
    fun `blank and damaged PDFs fail explicitly without chat inference`() =
        runBlocking {
            val empty = assertFailsWith<OpenRouterWorkflowException> { TextPdfClient.extractText(pdfBytes(null)) }
            assertEquals(FailureKind.CONFIGURATION, empty.kind)
            assertTrue("no extractable text" in empty.message.orEmpty())
            val damaged = assertFailsWith<OpenRouterWorkflowException> { TextPdfClient.extractText("%PDF-broken".toByteArray()) }
            assertEquals(FailureKind.CONFIGURATION, damaged.kind)
            FakeOpenRouterServer().use { server ->
                val dir = createTempDirectory("pdf-text-empty")
                try {
                    val pdf = dir.resolve("empty.pdf")
                    pdf.writeBytes(pdfBytes(null))
                    val client =
                        TextPdfClient(
                            "loaded model",
                            LocalLlmClient(LocalLlmConfig(LlmApi.OLLAMA, null, "${server.baseUrl}/v1")),
                        )
                    val failed = assertIs<PdfExtractionOutcome.Failed>(PdfExtractionService(client).extract(pdf, dir) { true })
                    assertEquals(FailureKind.CONFIGURATION, failed.kind)
                    assertTrue(server.chatRequests.isEmpty())
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

    @Test
    fun `missing usage stays null and provider failures preserve raw output`() =
        runBlocking {
            FakeOpenRouterServer().use { server ->
                val dir = createTempDirectory("pdf-text-failure")
                try {
                    val pdf = dir.resolve("brochure.pdf")
                    pdf.writeBytes(pdfBytes("Brochure facts"))
                    val client =
                        TextPdfClient(
                            "manual-model",
                            LocalLlmClient(LocalLlmConfig(LlmApi.MANUAL, null, "${server.baseUrl}/v1", modelId = "manual-model")),
                        )
                    server.chatBody =
                        FakeOpenRouterServer
                            .chatResponse(
                                ExtractedVehicle.strictJson.encodeToString(ExtractedVehicle.serializer(), PdfExtractorFixtures.vehicle),
                            ).replace(Regex(",?\\s*\"usage\"\\s*:\\s*\\{[^}]*}"), "")
                    val written = assertIs<PdfExtractionOutcome.Written>(PdfExtractionService(client).extract(pdf, dir) { true })
                    assertNull(written.extraction.provenance.outputTokens)
                    assertNull(PdfExtractionFile.decode(PdfExtractionFile.encode(written.extraction)).provenance.outputTokens)
                    server.chatBody = FakeOpenRouterServer.chatResponse("not JSON")
                    val failed = assertIs<PdfExtractionOutcome.Failed>(PdfExtractionService(client).extract(pdf, dir) { true })
                    assertEquals(FailureKind.INVALID_RESPONSE, failed.kind)
                    assertEquals(dir.resolve("brochure.raw.json"), failed.rawPath)
                    server.chatBody = FakeOpenRouterServer.chatResponse("{\"title\":", finishReason = "length")
                    val truncated = assertIs<PdfExtractionOutcome.Failed>(PdfExtractionService(client).extract(pdf, dir) { true })
                    assertEquals(FailureKind.TRUNCATED_RESPONSE, truncated.kind)
                    assertEquals(dir.resolve("brochure.raw.json"), truncated.rawPath)
                    assertEquals(
                        5,
                        truncated.metadata!!
                            .stages
                            .last()
                            .request
                            ?.usage
                            ?.completionTokens,
                    )
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

    private fun pdfBytes(text: String?): ByteArray =
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            if (text != null) {
                PDPageContentStream(document, page).use {
                    it.beginText()
                    it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    it.newLineAtOffset(50f, 750f)
                    it.showText(text)
                    it.endText()
                }
            }
            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
}
