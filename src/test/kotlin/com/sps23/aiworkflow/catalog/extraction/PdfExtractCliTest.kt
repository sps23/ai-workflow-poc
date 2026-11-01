package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfExtractCliTest {
    private val key = "cli-secret-key"
    private val dir = createTempDirectory("pdf-extract-cli")
    private val pdf = dir.resolve("jogger.pdf").also { it.writeBytes("%PDF-1.7 fake".toByteArray()) }
    private val outDir = dir.resolve("out")
    private val env = mapOf(GOOGLE_AI_API_KEY_VARIABLE to key)
    private val usage = GeminiUsage(100, 40, 10, 150)
    private val validJson =
        PdfExtractionPrompts.modelDefinition().let { def ->
            def.substring(def.indexOf("{", def.indexOf("## Example")), def.lastIndexOf("}") + 1)
        }

    private class Result(
        val code: Int,
        val out: String,
        val err: String,
    )

    private val configs = mutableListOf<GeminiPdfConfig>()
    private var calls = 0

    private fun run(
        args: List<String>,
        environment: Map<String, String?> = env,
        answer: () -> GeminiPdfResult = { GeminiPdfResult(validJson, "STOP", usage, "gemini-test-001", 42) },
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code =
            runPdfExtract(args, environment, PrintStream(out, true), PrintStream(err, true), "http://localhost:1") { config ->
                configs += config
                object : PdfExtractionClient {
                    override val modelId = config.modelId

                    override suspend fun outputTokenLimit() = 65_536L

                    override suspend fun extract(
                        pdf: ByteArray,
                        systemPrompt: String,
                        userPrompt: String,
                        responseSchema: JsonObject?,
                        maxOutputTokens: Long,
                    ): GeminiPdfResult {
                        calls++
                        return answer()
                    }
                }
            }
        return Result(code, out.toString(), err.toString())
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `writes the envelope and prints summary and tokens`() {
        val result = run(listOf(pdf.toString(), outDir.toString()))

        assertEquals(PdfExtractExit.WRITTEN, result.code, result.err)
        val file = outDir.resolve("jogger.json")
        assertEquals("jogger.pdf", PdfExtractionFile.decode(file.readText()).provenance.pdfFileName)
        assertTrue("Wrote ${file.toAbsolutePath()}" in result.out)
        assertTrue("Model: gemini-test-001" in result.out)
        assertTrue("Tokens: input 100, output 50 (thinking 10), total 150" in result.out)
        assertEquals("", result.err)
        assertEquals(GEMINI_PDF_DEFAULT_MODEL, configs.single().modelId)
        assertFalse(key in result.out)
    }

    @Test
    fun `output dir falls back to the environment variable then the default`() {
        val envDir = dir.resolve("from-env")
        assertEquals(0, run(listOf(pdf.toString()), env + (PDF_EXTRACT_OUTPUT_DIR_VARIABLE to envDir.toString())).code)
        assertTrue(envDir.resolve("jogger.json").exists())
        assertEquals("extracted-catalog", PDF_EXTRACT_DEFAULT_OUTPUT_DIR)
    }

    @Test
    fun `model comes from GOOGLE_AI_MODEL`() {
        run(listOf(pdf.toString(), outDir.toString()), env + (GOOGLE_AI_MODEL_VARIABLE to "models/gemini-other"))
        assertEquals("gemini-other", configs.single().modelId)
    }

    @Test
    fun `existing file is kept without --overwrite and replaced with it`() {
        val existing = outDir.createDirectories().resolve("jogger.json").also { it.writeText("old") }

        val refused = run(listOf(pdf.toString(), outDir.toString()))
        assertEquals(PdfExtractExit.EXISTS, refused.code)
        assertTrue("--overwrite" in refused.err)
        assertEquals("old", existing.readText())
        assertEquals(0, calls)

        val replaced = run(listOf("--overwrite", pdf.toString(), outDir.toString()))
        assertEquals(PdfExtractExit.WRITTEN, replaced.code)
        assertEquals(1, PdfExtractionFile.decode(existing.readText()).schemaVersion)
    }

    @Test
    fun `invalid output exits nonzero with violations and raw path`() {
        val result = run(listOf(pdf.toString(), outDir.toString())) { GeminiPdfResult("""{"make":"X"}""", "STOP", usage, null, 1) }

        assertEquals(PdfExtractExit.FAILED, result.code)
        assertTrue("Error [INVALID_RESPONSE]" in result.err)
        assertTrue("  R3: " in result.err)
        assertTrue("jogger.raw.json" in result.err)
        assertTrue("Tokens: input 100" in result.err)
        assertFalse(outDir.resolve("jogger.json").exists())
    }

    @Test
    fun `provider failure exits nonzero without leaking the key`() {
        val result =
            run(listOf(pdf.toString(), outDir.toString())) {
                throw OpenRouterWorkflowException(FailureKind.AUTHENTICATION, "Gemini PDF extraction failed (HTTP 401).")
            }
        assertEquals(PdfExtractExit.FAILED, result.code)
        assertTrue("Error [AUTHENTICATION]" in result.err)
        assertFalse(key in result.err + result.out)
    }

    @Test
    fun `usage errors and missing key are rejected without calls`() {
        listOf(emptyList(), listOf("a.pdf", "b", "c"), listOf("a.pdf", "--force"), listOf(" ")).forEach { args ->
            val result = run(args)
            assertEquals(PdfExtractExit.USAGE, result.code, "args=$args")
            assertTrue("Usage:" in result.err)
        }
        val missingKey = run(listOf(pdf.toString()), emptyMap())
        assertEquals(PdfExtractExit.USAGE, missingKey.code)
        assertTrue(GOOGLE_AI_API_KEY_VARIABLE in missingKey.err)
        assertEquals(0, calls)
        assertTrue(configs.isEmpty())
    }
}
