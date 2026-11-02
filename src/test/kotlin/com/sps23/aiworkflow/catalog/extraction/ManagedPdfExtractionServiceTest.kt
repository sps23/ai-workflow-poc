package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedPdfExtractionServiceTest {
    private val directory = Files.createTempDirectory("managed-pdf-extraction-test")
    private val paths = WorkspacePaths.at(directory.resolve("workspace"))
    private val external = Files.createDirectories(directory.resolve("external"))
    private val pdfBytes = "%PDF-1.7 captured snapshot".toByteArray()
    private val validJson =
        PdfExtractionPrompts.modelDefinition().let { definition ->
            definition.substring(
                definition.indexOf("{", definition.indexOf("## Example")),
                definition.lastIndexOf("}") + 1,
            )
        }

    init {
        Files.createDirectories(paths.input)
        Files.createDirectories(paths.data)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private class FakeClient(
        private val responses: MutableList<String>,
    ) : PdfExtractionClient {
        override val modelId = "requested-model"
        override val metadataNotes = "test"
        var extractCalls = 0
        var lastPdfBytes: ByteArray? = null

        override suspend fun outputTokenLimit(): Long = 4096

        override suspend fun extract(
            pdf: ByteArray,
            systemPrompt: String,
            userPrompt: String,
            responseSchema: JsonObject?,
            maxOutputTokens: Long,
        ): GeminiPdfResult {
            extractCalls++
            lastPdfBytes = pdf.copyOf()
            return GeminiPdfResult(
                text = responses.removeAt(0),
                finishReason = "STOP",
                usage = GeminiUsage(promptTokens = 12, candidatesTokens = 8, thoughtsTokens = 2, totalTokens = 22),
                modelVersion = "reported-model",
                latencyMillis = 123,
                metrics = null,
            )
        }
    }

    @Test
    fun `imports captured bytes once and writes validated extraction beside the PDF`() =
        runBlocking {
            val source = external.resolve("brochure.pdf").also { it.writeBytes(pdfBytes) }
            val client = FakeClient(mutableListOf(validJson))
            val result = service(client).extract(source) { error("new import has no overwrite prompt") }

            val written = assertIs<ManagedPdfExtractionOutcome.Written>(result)
            assertEquals(paths.input.toRealPath().resolve("brochure.pdf"), written.pdfPath)
            assertEquals(paths.input.toRealPath().resolve("brochure.json"), written.extraction.file)
            assertEquals(pdfBytes.toList(), written.pdfPath.readBytes().toList())
            assertEquals(pdfBytes.toList(), client.lastPdfBytes!!.toList())
            assertEquals(pdfBytes.toList(), source.readBytes().toList())
            assertEquals("brochure.pdf", written.extraction.extraction.provenance.pdfFileName)
            assertEquals(PdfExtractionService.sha256(pdfBytes), written.extraction.extraction.provenance.pdfSha256)
            assertTrue(written.warnings.isEmpty())
        }

    @Test
    fun `same filename with different content gets a collision-free imported name`() =
        runBlocking {
            val first = external.resolve("brochure.pdf").also { it.writeBytes(pdfBytes) }
            val second = external.resolve("second/brochure.pdf")
            Files.createDirectories(second.parent)
            second.writeBytes("%PDF-1.7 different".toByteArray())
            val client = FakeClient(mutableListOf(validJson, validJson))
            val service = service(client)

            val firstResult =
                assertIs<ManagedPdfExtractionOutcome.Written>(service.extract(first) { error("unexpected prompt") })
            val secondResult =
                assertIs<ManagedPdfExtractionOutcome.Written>(service.extract(second) { error("unexpected prompt") })

            assertEquals("brochure.pdf", firstResult.pdfPath.fileName.toString())
            assertEquals("brochure-2.pdf", secondResult.pdfPath.fileName.toString())
            assertEquals(second.readBytes().toList(), secondResult.pdfPath.readBytes().toList())
            assertEquals(2, client.extractCalls)
        }

    @Test
    fun `identical workspace PDF requires confirmation even without extraction JSON`() =
        runBlocking {
            val imported =
                paths.input
                    .resolve("existing.pdf")
                    .also { it.writeBytes(pdfBytes) }
                    .toRealPath()
            val source = external.resolve("existing.pdf").also { it.writeBytes(pdfBytes) }
            val client = FakeClient(mutableListOf(validJson))
            var confirmed: Path? = null

            val result =
                service(client).extract(source) {
                    confirmed = it
                    false
                }

            assertEquals(ManagedPdfExtractionOutcome.Declined(imported), result)
            assertEquals(imported, confirmed)
            assertFalse(Files.exists(paths.input.resolve("existing.json")))
            assertEquals(0, client.extractCalls)
        }

    @Test
    fun `failed retry preserves previous success and writes a diagnostic envelope`() =
        runBlocking {
            val source = external.resolve("brochure.pdf").also { it.writeBytes(pdfBytes) }
            val client = FakeClient(mutableListOf(validJson, """{"title":"","make":"X"}""", validJson))
            val service = service(client)
            val first = assertIs<ManagedPdfExtractionOutcome.Written>(service.extract(source) { true })
            val originalJson = first.extraction.file.readBytes()

            val failed = assertIs<ManagedPdfExtractionOutcome.Failed>(service.extract(source) { true })
            val diagnosticPath = assertNotNull(failed.diagnosticPath)
            val diagnostic =
                Json.decodeFromString<PdfExtractionDiagnostic>(diagnosticPath.readText())
            assertEquals("pdf-extraction-diagnostic", diagnostic.kind)
            assertEquals("brochure.pdf", diagnostic.provenance.pdfFileName)
            assertEquals("reported-model", diagnostic.provenance.model)
            assertEquals("""{"title":"","make":"X"}""", diagnostic.rawText)
            assertEquals(FailureKind.INVALID_RESPONSE.name, diagnostic.failure.kind)
            assertTrue(diagnostic.failure.violations.isNotEmpty())
            assertEquals(
                originalJson.toList(),
                first.extraction.file
                    .readBytes()
                    .toList(),
            )
            assertTrue(failed.previousExtractionAvailable)

            val retried = assertIs<ManagedPdfExtractionOutcome.Written>(service.extract(source) { true })
            assertFalse(Files.exists(diagnosticPath))
            assertTrue(retried.warnings.isEmpty())
        }

    @Test
    fun `provider failure without text keeps imported PDF and creates no diagnostic`() =
        runBlocking {
            val source = external.resolve("brochure.pdf").also { it.writeBytes(pdfBytes) }
            val client =
                object : PdfExtractionClient by FakeClient(mutableListOf()) {
                    override val modelId = "requested-model"

                    override suspend fun outputTokenLimit(): Long = throw OpenRouterWorkflowException(FailureKind.AUTHENTICATION, "Bad key")
                }
            val result = assertIs<ManagedPdfExtractionOutcome.Failed>(service(client).extract(source) { true })

            assertEquals(FailureKind.AUTHENTICATION, result.kind)
            assertTrue(Files.exists(paths.input.resolve("brochure.pdf")))
            assertFalse(Files.exists(paths.input.resolve("brochure.json")))
            assertNull(result.diagnosticPath)
        }

    private fun service(client: PdfExtractionClient) =
        ManagedPdfExtractionService(
            paths,
            PdfExtractionService(
                client,
                Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC),
            ),
            clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC),
        )
}
