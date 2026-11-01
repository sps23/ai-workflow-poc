package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.catalog.datamodel.CarDataModelParser
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogLoader
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.fail

/**
 * Live check: one repository PDF -> [GeminiPdfClient] with `responseJsonSchema` -> JSON string ->
 * requirement checks R1-R18.
 *
 * Opt in with `-PpdfExtractionIntegration`; requires GOOGLE_AI_API_KEY. Paid tier calls incur charges.
 * Artifacts: build/pdf-extraction-it/<pdf stem>/.
 */
class PdfExtractionIntegrationTest {
    @Test
    fun `gemini extracts a valid vehicle json from a pdf resource`() =
        runBlocking {
            val env = System.getenv()
            val apiKey =
                env[GOOGLE_AI_API_KEY_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("Set $GOOGLE_AI_API_KEY_VARIABLE to run the live PDF extraction test.")
            val modelId = env[GOOGLE_AI_MODEL_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() } ?: GEMINI_PDF_DEFAULT_MODEL
            val resource = env[RESOURCE_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_RESOURCE
            val temperature =
                env[TEMPERATURE_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    it.toDoubleOrNull() ?: error("$TEMPERATURE_VARIABLE must be a number, was '$it'.")
                }

            val root = pricelistRoot()
            val pdfPath = root.resolve(resource)
            check(resource.endsWith(".pdf", ignoreCase = true) && pdfPath.isRegularFile()) {
                "PDF resource '$resource' not found under $root."
            }
            val pdf = pdfPath.readBytes()
            val referencePath = pdfPath.resolveSibling(pdfPath.nameWithoutExtension + ".json")
            check(referencePath.isRegularFile()) { "Reference JSON ${referencePath.fileName} next to '$resource' is required." }
            val reference = CarDataModelParser.parse(referencePath)

            val config = GeminiPdfConfig(apiKey, modelId, temperature = temperature)
            val client = GeminiPdfClient(config)
            val maxOutputTokens = minOf(GEMINI_PDF_MAX_OUTPUT_TOKENS, client.outputTokenLimit())
            val systemPrompt = PdfExtractionPrompts.systemPrompt()
            val userPrompt = PdfExtractionPrompts.userPrompt()
            val schema = ExtractedVehicle.jsonSchema

            val outDir = Path.of("build", "pdf-extraction-it", pdfPath.nameWithoutExtension).createDirectories()
            listOf("response.raw.txt", "vehicle.json", "report.md").forEach { Files.deleteIfExists(outDir.resolve(it)) }
            outDir.resolve("schema.json").writeText(prettyJson.encodeToString(JsonObject.serializer(), schema) + "\n")
            outDir.resolve("request-meta.json").writeText(
                prettyJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("timestamp", Instant.now().toString())
                        put("model", config.modelId)
                        put("resource", resource)
                        put("pdfBytes", pdf.size)
                        put("systemPromptChars", systemPrompt.length)
                        put("userPromptChars", userPrompt.length)
                        put("responseJsonSchemaChars", schema.toString().length)
                        put("maxOutputTokens", maxOutputTokens)
                        put("temperature", temperature?.toString() ?: "model default")
                        put("responseMimeType", "application/json")
                    },
                ) + "\n",
            )

            val result =
                try {
                    client.extract(pdf, systemPrompt, userPrompt, schema, maxOutputTokens)
                } catch (e: OpenRouterWorkflowException) {
                    e.rawOutput?.let { outDir.resolve("response.raw.txt").writeText(it) }
                    val failure =
                        "# PDF extraction report\n\n- Resource: `$resource`\n- Model: `${config.modelId}`\n" +
                            "- Result: FAILED before validation [${e.kind}]: ${e.message}\n" +
                            (e.diagnostics?.let { d -> "- Diagnostics: HTTP ${d.httpStatus}, ${d.providerMessage ?: "n/a"}\n" } ?: "")
                    outDir.resolve("report.md").writeText(failure)
                    println(failure)
                    fail("Gemini extraction failed [${e.kind}]: ${e.message}. Artifacts: ${outDir.toAbsolutePath()}")
                }
            val report = ExtractionRequirements.validate(result.text, result.finishReason, reference)

            outDir.resolve("response.raw.txt").writeText(result.text)
            report.vehicle?.let {
                outDir.resolve("vehicle.json").writeText(prettyJson.encodeToString(ExtractedVehicle.serializer(), it) + "\n")
            }
            val markdown = renderReport(resource, config.modelId, result, report, maxOutputTokens)
            outDir.resolve("report.md").writeText(markdown)
            println(markdown)
            println("Artifacts: ${outDir.toAbsolutePath()}")

            if (!report.passed) {
                fail("Hard requirement violations:\n" + report.hardViolations.joinToString("\n") { "${it.rule}: ${it.message}" })
            }
        }

    companion object {
        const val DEFAULT_RESOURCE = "dacia/dacia jogger.pdf"
        const val RESOURCE_VARIABLE = "PDF_EXTRACTION_IT_RESOURCE"
        const val TEMPERATURE_VARIABLE = "PDF_EXTRACTION_IT_TEMPERATURE"

        /** Paid-tier USD per 1M tokens (input, output incl. thinking), checked 2026-10-31. */
        val PRICES =
            mapOf(
                "gemini-3.8-flash" to (0.75 to 3.75),
                "gemini-3.5-flash-lite" to (0.30 to 2.50),
                "gemini-3.1-flash-lite" to (0.25 to 1.50),
                "gemini-2.5-flash-lite" to (0.10 to 0.40),
            )

        private val prettyJson =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }

        private fun pricelistRoot(): Path =
            Path.of(
                checkNotNull(PdfExtractionIntegrationTest::class.java.getResource(DataModelCatalogLoader.DEFAULT_RESOURCE_ROOT)).toURI(),
            )

        fun estimatedCostUsd(
            modelId: String,
            usage: GeminiUsage,
        ): Double? =
            PRICES[modelId]?.let { (input, output) ->
                ((usage.promptTokens ?: 0) * input + usage.outputTokens * output) / 1_000_000
            }

        fun renderReport(
            resource: String,
            modelId: String,
            result: GeminiPdfResult,
            report: ValidationReport,
            maxOutputTokens: Long,
        ): String =
            buildString {
                appendLine("# PDF extraction report")
                appendLine()
                appendLine("- Resource: `$resource`")
                appendLine("- Model: `$modelId` (response modelVersion: `${result.modelVersion ?: "n/a"}`)")
                appendLine("- Mode: JSON + responseJsonSchema (ExtractedVehicle)")
                appendLine("- Finish reason: `${result.finishReason}`")
                appendLine("- Latency: ${result.latencyMillis} ms")
                appendLine(
                    "- Tokens: prompt ${result.usage.promptTokens ?: "n/a"}, candidates ${result.usage.candidatesTokens ?: "n/a"}, " +
                        "thoughts ${result.usage.thoughtsTokens ?: 0}, total ${result.usage.totalTokens ?: "n/a"} (max output $maxOutputTokens)",
                )
                appendLine(
                    "- Estimated cost: " +
                        (estimatedCostUsd(modelId, result.usage)?.let { "USD %.4f".format(Locale.ROOT, it) } ?: "unknown model price"),
                )
                report.vehicle?.let { v ->
                    appendLine(
                        "- Extracted: ${v.trims.size} trims, ${v.trims.sumOf { it.variants.size }} variants, " +
                            "${v.options.size} options, ${v.technicalSpecifications.powertrains.size} powertrains",
                    )
                }
                appendLine(
                    "- Result: ${if (report.passed) "PASSED" else "FAILED"} (${report.hardViolations.size} hard, ${report.softViolations.size} soft)",
                )
                appendLine()
                appendLine("## Hard violations")
                if (report.hardViolations.isEmpty()) appendLine("None.")
                report.hardViolations.forEach { appendLine("- ${it.rule}: ${it.message}") }
                appendLine()
                appendLine("## Soft deviations")
                if (report.softViolations.isEmpty()) appendLine("None.")
                report.softViolations.forEach { appendLine("- ${it.rule}: ${it.message}") }
            }
    }
}
