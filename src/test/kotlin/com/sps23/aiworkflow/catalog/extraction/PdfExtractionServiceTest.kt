package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.logging.WorkspaceLogs
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.TokenUsage
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfExtractionServiceTest {
    private val dir = createTempDirectory("pdf-extraction-service")
    private val outputDir = dir.resolve("out")
    private val pdfBytes = "%PDF-1.7 fake".toByteArray()
    private val pdf = dir.resolve("brochure.pdf").also { it.writeBytes(pdfBytes) }
    private val clock = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC)
    private val usage = GeminiUsage(promptTokens = 100, candidatesTokens = 40, thoughtsTokens = 10, totalTokens = 150)
    private val metrics =
        RequestMetrics(
            api = "Google AI (native generateContent)",
            requestedModelId = "gemini-test",
            inputUtf8Bytes = 1000,
            estimatedInputTokens = null,
            requestedOutputTokens = GEMINI_PDF_MAX_OUTPUT_TOKENS,
            effectiveOutputTokens = 8192,
            dispatched = true,
            httpStatus = 200,
            elapsedMillis = 1234,
            usage = TokenUsage(100, 50, 150, 10),
        )

    private val validJson: String =
        PdfExtractionPrompts.modelDefinition().let { def ->
            def.substring(def.indexOf("{", def.indexOf("## Example")), def.lastIndexOf("}") + 1)
        }

    private class FakeClient(
        private val limit: Long = 8_192,
        private val answer: () -> GeminiPdfResult,
    ) : PdfExtractionClient {
        override val modelId = "gemini-test"
        var extractCalls = 0
        var lastMaxOutputTokens = 0L
        var lastSchema: JsonObject? = null

        override suspend fun outputTokenLimit(): Long = limit

        override suspend fun extract(
            pdf: ByteArray,
            systemPrompt: String,
            userPrompt: String,
            responseSchema: JsonObject?,
            maxOutputTokens: Long,
        ): GeminiPdfResult {
            extractCalls++
            lastMaxOutputTokens = maxOutputTokens
            lastSchema = responseSchema
            return answer()
        }
    }

    private fun result(
        text: String,
        modelVersion: String? = "gemini-test-001",
    ) = GeminiPdfResult(text, "STOP", usage, modelVersion, 1_234, metrics)

    private fun service(client: PdfExtractionClient) = PdfExtractionService(client, clock)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `native extraction logs full JSON schema response validation and performance without PDF bytes`() =
        runBlocking {
            val paths = WorkspacePaths.at(dir)
            val logged = PdfExtractionService(FakeClient { result(validJson) }, clock, WorkspaceLogs(paths))
            assertIs<PdfExtractionOutcome.Written>(logged.extract(pdf, outputDir) { true })
            val file = paths.logs.listDirectoryEntries("*_extractor.log").single()
            val log = file.readText()
            assertTrue("responseSchema" in log)
            assertTrue("systemPrompt" in log)
            assertTrue("rawResponse" in log)
            assertTrue("responseJson" in log)
            assertTrue("extractionJson" in log)
            assertTrue("performanceMetadata" in log)
            assertTrue("gemini-test-001" in log)
            assertTrue("totalTokens" in log)
            assertFalse("%PDF-1.7 fake" in log)
            assertTrue("\"event\":\"COMPLETED\"" in log.lines().filter { it.isNotBlank() }.last())
        }

    @Test
    fun `invalid extraction logs FAILED with raw response and rule violations`() =
        runBlocking {
            val paths = WorkspacePaths.at(dir)
            val logged = PdfExtractionService(FakeClient { result("""{"invalid":true}""") }, clock, WorkspaceLogs(paths))
            assertIs<PdfExtractionOutcome.Failed>(logged.extract(pdf, outputDir) { true })
            val log =
                paths.logs
                    .listDirectoryEntries("*_extractor.log")
                    .single()
                    .readText()
            assertTrue("rawResponse" in log)
            assertTrue("violations" in log)
            assertTrue("INVALID_RESPONSE" in log)
            assertTrue("\"event\":\"FAILED\"" in log.lines().filter { it.isNotBlank() }.last())
            assertTrue("\"level\":\"ERROR\"" in paths.logs.resolve("application.log").readText())
        }

    @Test
    fun `valid output is written as an envelope with provenance`() =
        runBlocking {
            val client = FakeClient { result(validJson) }
            val outcome = service(client).extract(pdf, outputDir) { error("no overwrite prompt expected") }

            val written = assertIs<PdfExtractionOutcome.Written>(outcome)
            assertEquals(outputDir.resolve("brochure.json"), written.file)
            assertEquals(8_192, client.lastMaxOutputTokens)
            assertEquals(ExtractedVehicle.jsonSchema, client.lastSchema)
            val file = PdfExtractionFile.decode(written.file.readText())
            assertEquals(PDF_EXTRACTION_SCHEMA_VERSION, file.schemaVersion)
            assertEquals(
                ExtractionProvenance(
                    pdfFileName = "brochure.pdf",
                    pdfSha256 = PdfExtractionService.sha256(pdfBytes),
                    model = "gemini-test-001",
                    extractedAt = "2026-10-31T12:00:00Z",
                    inputTokens = 100,
                    outputTokens = 50,
                ),
                file.provenance,
            )
            assertEquals(ExtractionRequirements.validate(validJson, "STOP").vehicle, file.vehicle)
            assertTrue(written.file.readText().startsWith("{\n    \"schemaVersion\": 1,"))
            assertEquals(listOf("brochure.json"), outputDir.listDirectoryEntries().map { it.fileName.toString() })
            val metadata = assertNotNull(written.metadata)
            assertEquals(listOf("Model metadata", "PDF extraction"), metadata.stages.map { it.stage })
            assertTrue(metadata.stages.all { it.outcome == "SUCCEEDED" })
            assertEquals(metrics, metadata.stages.last().request)
            assertTrue(metadata.notes.contains("PDF input tokens are not estimated"))
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertTrue(PosixFilePermission.OTHERS_READ in Files.getPosixFilePermissions(written.file), "umask-default, not 0600")
            }
        }

    @Test
    fun `sha256 is lowercase hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PdfExtractionService.sha256("abc".toByteArray()))
    }

    @Test
    fun `model id is used when the response has no modelVersion and max output is capped`() =
        runBlocking {
            val client = FakeClient(limit = 1_000_000) { result(validJson, modelVersion = null) }
            val written = assertIs<PdfExtractionOutcome.Written>(service(client).extract(pdf, outputDir) { true })
            assertEquals("gemini-test", written.extraction.provenance.model)
            assertEquals(GEMINI_PDF_MAX_OUTPUT_TOKENS, client.lastMaxOutputTokens)
        }

    @Test
    fun `declined overwrite keeps the file and never calls the model`() =
        runBlocking {
            Files.createDirectories(outputDir)
            val existing = outputDir.resolve("brochure.json").also { it.writeText("old") }
            val client = FakeClient { result(validJson) }
            var asked: Path? = null
            val outcome =
                service(client).extract(pdf, outputDir) {
                    asked = it
                    false
                }

            assertEquals(PdfExtractionOutcome.Declined(existing), outcome)
            assertEquals(existing, asked)
            assertEquals(0, client.extractCalls)
            assertEquals("old", existing.readText())
        }

    @Test
    fun `accepted overwrite replaces the file`() =
        runBlocking {
            Files.createDirectories(outputDir)
            val existing = outputDir.resolve("brochure.json").also { it.writeText("old") }
            val outcome = service(FakeClient { result(validJson) }).extract(pdf, outputDir) { true }
            assertIs<PdfExtractionOutcome.Written>(outcome)
            assertEquals(PDF_EXTRACTION_SCHEMA_VERSION, PdfExtractionFile.decode(existing.readText()).schemaVersion)
        }

    @Test
    fun `invalid output never writes the json and saves the raw response`() =
        runBlocking {
            val raw = """{"title":"","make":"X"}"""
            val outcome = service(FakeClient { result(raw) }).extract(pdf, outputDir) { true }

            val failed = assertIs<PdfExtractionOutcome.Failed>(outcome)
            assertEquals(FailureKind.INVALID_RESPONSE, failed.kind)
            assertTrue(failed.violations.any { it.rule == "R3" })
            assertEquals(outputDir.resolve("brochure.raw.json"), failed.rawPath)
            assertEquals(raw, failed.rawPath!!.readText())
            assertEquals(usage, failed.usage)
            assertEquals(
                metrics,
                failed.metadata
                    ?.stages
                    ?.last()
                    ?.request,
            )
            assertEquals(
                "INVALID_RESPONSE",
                failed.metadata
                    ?.stages
                    ?.last()
                    ?.failureKind,
            )
            assertEquals(
                "SUCCEEDED",
                failed.metadata
                    ?.stages
                    ?.first()
                    ?.outcome,
            )
            assertFalse(outputDir.resolve("brochure.json").exists())
        }

    @Test
    fun `truncated response is mapped and its partial text saved`() =
        runBlocking {
            val client =
                FakeClient {
                    throw OpenRouterWorkflowException(
                        FailureKind.TRUNCATED_RESPONSE,
                        "cut",
                        rawOutput = """{"title":""",
                        requestMetrics = metrics.copy(finishReason = "MAX_TOKENS"),
                    )
                }
            val failed = assertIs<PdfExtractionOutcome.Failed>(service(client).extract(pdf, outputDir) { true })
            assertEquals(FailureKind.TRUNCATED_RESPONSE, failed.kind)
            assertEquals("""{"title":""", failed.rawPath!!.readText())
            assertEquals(
                "MAX_TOKENS",
                failed.metadata
                    ?.stages
                    ?.last()
                    ?.request
                    ?.finishReason,
            )
            assertEquals(
                50,
                failed.metadata
                    ?.stages
                    ?.last()
                    ?.request
                    ?.usage
                    ?.completionTokens,
            )
            assertFalse(outputDir.resolve("brochure.json").exists())
        }

    @Test
    fun `client errors without output are mapped without files`() =
        runBlocking {
            val client = FakeClient { throw OpenRouterWorkflowException(FailureKind.RATE_LIMIT, "slow down") }
            val failed = assertIs<PdfExtractionOutcome.Failed>(service(client).extract(pdf, outputDir) { true })
            assertEquals(FailureKind.RATE_LIMIT, failed.kind)
            assertEquals("slow down", failed.message)
            assertNull(failed.rawPath)
            assertFalse(outputDir.exists())
        }

    @Test
    fun `missing or non-pdf input fails before any call`() =
        runBlocking {
            val client = FakeClient { result(validJson) }
            val missing = service(client).extract(dir.resolve("nope.pdf"), outputDir) { true }
            assertEquals(FailureKind.CONFIGURATION, assertIs<PdfExtractionOutcome.Failed>(missing).kind)

            val pointer = dir.resolve("pointer.pdf").also { it.writeText("version https://git-lfs.github.com/spec/v1") }
            val notPdf = assertIs<PdfExtractionOutcome.Failed>(service(client).extract(pointer, outputDir) { true })
            assertEquals(FailureKind.CONFIGURATION, notPdf.kind)
            assertNotNull(notPdf.message)
            assertNull(
                notPdf.metadata
                    ?.stages
                    ?.single()
                    ?.request,
            )
            assertEquals(
                "CONFIGURATION",
                notPdf.metadata
                    ?.stages
                    ?.single()
                    ?.failureKind,
            )
            assertEquals(0, client.extractCalls)
        }

    @Test
    fun `failed model discovery records its own stage without inference`() =
        runBlocking {
            val fake = FakeClient { result(validJson) }
            val client =
                object : PdfExtractionClient by fake {
                    override suspend fun outputTokenLimit(): Long =
                        throw OpenRouterWorkflowException(
                            FailureKind.AUTHENTICATION,
                            "Bad key",
                            diagnostics = OpenRouterErrorDiagnostics("Model metadata", 401),
                        )
                }
            val failed = assertIs<PdfExtractionOutcome.Failed>(service(client).extract(pdf, outputDir) { true })
            val stage = assertNotNull(failed.metadata).stages.single()
            assertEquals("Model metadata", stage.stage)
            assertEquals("FAILED", stage.outcome)
            assertEquals("AUTHENTICATION", stage.failureKind)
            assertEquals(401, stage.httpStatus)
            assertNull(stage.request)
            assertEquals(0, fake.extractCalls)
        }
}
