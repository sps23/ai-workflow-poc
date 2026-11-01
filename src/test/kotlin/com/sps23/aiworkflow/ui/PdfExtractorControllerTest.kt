package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.catalog.extraction.ExtractedVehicle
import com.sps23.aiworkflow.catalog.extraction.ExtractionProvenance
import com.sps23.aiworkflow.catalog.extraction.GeminiUsage
import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACT_DEFAULT_OUTPUT_DIR
import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACT_OUTPUT_DIR_VARIABLE
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionOutcome
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionPrompts
import com.sps23.aiworkflow.catalog.extraction.Severity
import com.sps23.aiworkflow.catalog.extraction.Violation
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.TokenUsage
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object PdfExtractorFixtures {
    val vehicle: ExtractedVehicle =
        PdfExtractionPrompts.modelDefinition().let { def ->
            val example = def.substring(def.indexOf("{", def.indexOf("## Example")), def.lastIndexOf("}") + 1)
            ExtractedVehicle.strictJson.decodeFromString(ExtractedVehicle.serializer(), example)
        }
    val usage = GeminiUsage(100, 40, 10, 150)
    val metadata =
        WorkflowMetadata(
            elapsedMillis = 1300,
            stages =
                listOf(
                    StageMetrics("Model metadata", "SUCCEEDED", 20),
                    StageMetrics(
                        "PDF extraction",
                        "SUCCEEDED",
                        1280,
                        request =
                            RequestMetrics(
                                api = "Google AI (native generateContent)",
                                requestedModelId = "gemini-test",
                                inputUtf8Bytes = 1000,
                                estimatedInputTokens = null,
                                requestedOutputTokens = 65_536,
                                effectiveOutputTokens = 8192,
                                usage = TokenUsage(100, 50, 150, 10),
                            ),
                    ),
                ),
        )

    fun written(file: Path) =
        PdfExtractionOutcome.Written(
            file,
            PdfExtractionFile(
                provenance = ExtractionProvenance("brochure.pdf", "00", "gemini-test-001", "2026-10-31T12:00:00Z", 100, 50),
                vehicle = vehicle,
            ),
            usage,
            1_234,
            listOf(Violation("R16", Severity.SOFT, "Option count differs.")),
            metadata,
        )
}

class PdfExtractorControllerTest {
    @Test
    fun `non-Google selections forward independent keys models and freeze controls during extraction`() =
        runBlocking {
            val calls = mutableListOf<Triple<LlmApi, String, String?>>()
            lateinit var controller: PdfExtractorController
            controller =
                PdfExtractorController(
                    environment = emptyMap(),
                    textExtractAction = { _, dir, api, model, key, url, _ ->
                        calls += Triple(api, model, key)
                        controller.providerSelection.selectApi(LlmApi.GOOGLE_AI)
                        controller.providerSelection.selectModel("blocked")
                        controller.providerSelection.updateOpenAiApiKey("blocked")
                        assertEquals(api, controller.providerSelection.selectedApi)
                        assertEquals(model, controller.modelId)
                        if (api == LlmApi.MANUAL) assertEquals("https://example.test/v1", url)
                        PdfExtractorFixtures.written(dir.resolve("brochure.json"))
                    },
                )
            val selection = controller.providerSelection
            controller.updatePdfPath("/brochure.pdf")
            selection.updateOpenAiApiKey("openai-key")
            selection.updateLocalApiKey("local-key")
            selection.updateManualApiKey("manual-key")
            selection.updateManualBaseUrl("https://example.test/v1")
            for (api in listOf(LlmApi.OPENAI, LlmApi.OPENROUTER, LlmApi.LM_STUDIO, LlmApi.LLAMA_CPP, LlmApi.OLLAMA, LlmApi.MANUAL)) {
                selection.selectApi(api)
                if (!api.isLocal) selection.selectModel("exact-${api.name}")
                controller.extract()
                assertIs<PdfExtractorState.Done>(controller.state)
                assertFalse(selection.controlsLocked)
            }
            assertEquals(
                listOf("openai-key", null, "local-key", "local-key", "local-key", "manual-key"),
                calls.map { it.third },
            )
            selection.selectApi(LlmApi.GOOGLE_AI)
            assertEquals(DEFAULT_GOOGLE_UI_MODEL, controller.modelId)
            selection.selectApi(LlmApi.OPENAI)
            assertEquals("exact-OPENAI", controller.modelId)
            assertEquals("openai-key", selection.openAiApiKey)
        }

    private class Call(
        val pdf: Path,
        val outputDir: Path,
        val apiKey: String,
        val modelId: String,
    )

    private val calls = mutableListOf<Call>()

    private fun controller(
        environment: Map<String, String?> = emptyMap(),
        resolveKey: (String) -> String = { it.ifBlank { "env-key" } },
        outcome: suspend (Path, Path, suspend (Path) -> Boolean) -> PdfExtractionOutcome = { pdf, dir, _ ->
            PdfExtractorFixtures.written(dir.resolve("${pdf.fileName.toString().removeSuffix(".pdf")}.json"))
        },
    ) = PdfExtractorController(
        environment = environment,
        extractAction = { pdf, dir, key, model, confirm ->
            calls += Call(pdf, dir, key, model)
            outcome(pdf, dir, confirm)
        },
        discoverModels = { key -> listOf(GoogleAiModel("gemini-test", "Gemini Test ($key)", 1_000, 8_192)) },
        resolveKey = resolveKey,
    )

    @Test
    fun `UI defaults use Google and Flash Lite regardless of CLI model environment`() {
        val plain = controller()
        assertEquals(PDF_EXTRACT_DEFAULT_OUTPUT_DIR, plain.outputDir)
        assertEquals(DEFAULT_GOOGLE_UI_MODEL, plain.modelId)
        assertEquals(LlmApi.GOOGLE_AI, plain.providerSelection.selectedApi)
        assertFalse(plain.canExtract)

        val configured =
            controller(mapOf(PDF_EXTRACT_OUTPUT_DIR_VARIABLE to " /tmp/out ", GOOGLE_AI_MODEL_VARIABLE to "models/gemini-other"))
        assertEquals("/tmp/out", configured.outputDir)
        assertEquals(DEFAULT_GOOGLE_UI_MODEL, configured.modelId)
    }

    @Test
    fun `successful extraction passes trimmed inputs and resolved key`() =
        runBlocking {
            val controller = controller()
            controller.updatePdfPath(" /cars/jogger.pdf ")
            controller.updateOutputDir("/out")
            controller.selectModel("models/gemini-test")
            assertTrue(controller.canExtract)

            controller.extract()

            val done = assertIs<PdfExtractorState.Done>(controller.state)
            assertEquals(Path.of("/out/jogger.json"), done.outcome.file)
            val call = calls.single()
            assertEquals(Path.of("/cars/jogger.pdf"), call.pdf)
            assertEquals(Path.of("/out"), call.outputDir)
            assertEquals("env-key", call.apiKey)
            assertEquals("gemini-test", call.modelId)
            assertEquals(PdfExtractorFixtures.metadata, controller.metadata)
            assertEquals(WorkflowMetadataCodec.encode(PdfExtractorFixtures.metadata), controller.metadataJson)
        }

    @Test
    fun `overwrite dialog state drives the confirmation answer`() =
        runBlocking {
            listOf(true, false).forEach { replace ->
                val asked = CompletableDeferred<Path>()
                val controller =
                    controller { pdf, dir, confirm ->
                        val target = dir.resolve("jogger.json")
                        asked.complete(target)
                        if (confirm(target)) PdfExtractorFixtures.written(target) else PdfExtractionOutcome.Declined(target)
                    }
                controller.updatePdfPath("/cars/jogger.pdf")
                val job = async { controller.extract() }
                val target = asked.await()
                yield()
                assertEquals(target, controller.pendingOverwrite)
                assertTrue(controller.extracting)
                assertFalse(controller.canExtract)

                controller.answerOverwrite(replace)
                job.await()

                assertNull(controller.pendingOverwrite)
                if (replace) {
                    assertIs<PdfExtractorState.Done>(controller.state)
                } else {
                    assertEquals(PdfExtractorState.Declined(target), controller.state)
                }
            }
        }

    @Test
    fun `failed outcome keeps hard violations and loads the raw text`() =
        runBlocking {
            val raw = createTempDirectory("pdf-extractor-ui").resolve("jogger.raw.json")
            try {
                raw.writeText("""{"make":"X"}""")
                val controller =
                    controller { _, _, _ ->
                        PdfExtractionOutcome.Failed(
                            FailureKind.INVALID_RESPONSE,
                            "Gemini output violates 1 hard requirement(s).",
                            listOf(Violation("R3", Severity.HARD, "decode"), Violation("R16", Severity.SOFT, "count")),
                            raw,
                            PdfExtractorFixtures.usage,
                            PdfExtractorFixtures.metadata,
                        )
                    }
                controller.updatePdfPath("/cars/jogger.pdf")
                controller.extract()

                val failed = assertIs<PdfExtractorState.Failed>(controller.state)
                assertEquals(FailureKind.INVALID_RESPONSE, failed.kind)
                assertEquals(listOf("R3"), failed.hardViolations.map { it.rule })
                assertEquals(raw, failed.rawPath)
                assertEquals("""{"make":"X"}""", failed.rawText)
                assertEquals(PdfExtractorFixtures.metadata, controller.metadata)
            } finally {
                raw.parent.toFile().deleteRecursively()
            }
        }

    @Test
    fun `missing key fails before any extraction call`() =
        runBlocking {
            val controller =
                controller(resolveKey = { throw OpenRouterWorkflowException(FailureKind.CONFIGURATION, "Enter a Google AI key.") })
            controller.updatePdfPath("/cars/jogger.pdf")
            controller.extract()

            val failed = assertIs<PdfExtractorState.Failed>(controller.state)
            assertEquals(FailureKind.CONFIGURATION, failed.kind)
            assertEquals(
                "CONFIGURATION",
                controller.metadata
                    ?.stages
                    ?.single()
                    ?.failureKind,
            )
            assertNull(
                controller.metadata
                    ?.stages
                    ?.single()
                    ?.request,
            )
            assertTrue(calls.isEmpty())
        }

    @Test
    fun `unexpected exceptions become a provider failure`() =
        runBlocking {
            val controller = controller { _, _, _ -> error("boom") }
            controller.updatePdfPath("/cars/jogger.pdf")
            controller.extract()
            assertEquals(FailureKind.PROVIDER, assertIs<PdfExtractorState.Failed>(controller.state).kind)
        }

    @Test
    fun `inputs are frozen while extracting`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val controller =
                controller { pdf, dir, _ ->
                    release.await()
                    PdfExtractorFixtures.written(dir.resolve("x.json"))
                }
            controller.updatePdfPath("/cars/jogger.pdf")
            val job = async { controller.extract() }
            yield()
            assertIs<PdfExtractorState.Extracting>(controller.state)
            controller.updatePdfPath("/other.pdf")
            controller.updateOutputDir("/elsewhere")
            controller.selectModel("other")
            controller.updateApiKey("new")
            controller.reset()
            assertEquals("/cars/jogger.pdf", controller.pdfPath)
            assertEquals(PDF_EXTRACT_DEFAULT_OUTPUT_DIR, controller.outputDir)
            assertEquals(DEFAULT_GOOGLE_UI_MODEL, controller.modelId)
            assertEquals("", controller.apiKey)
            controller.extract()
            release.complete(Unit)
            job.await()
            assertEquals(1, calls.size)
        }

    @Test
    fun `model discovery uses the resolved key, runs once and resets on key change`() =
        runBlocking {
            val controller = controller()
            controller.selectModel("gemini-test")
            controller.loadModels()
            assertEquals("Gemini Test (env-key)", controller.models.single().name)
            assertEquals(8_192, controller.selectedModel?.outputTokenLimit)

            controller.updateApiKey("typed")
            assertTrue(controller.models.isEmpty())
            controller.loadModels()
            assertEquals("Gemini Test (typed)", controller.models.single().name)
        }

    @Test
    fun `model discovery errors are user readable`() =
        runBlocking {
            val controller =
                PdfExtractorController(
                    environment = emptyMap(),
                    discoverModels = { throw OpenRouterWorkflowException(FailureKind.AUTHENTICATION, "401") },
                    resolveKey = { "k" },
                )
            controller.loadModels()
            assertTrue(controller.modelsError!!.contains("authentication failed"))
            assertFalse(controller.modelsLoading)
        }

    @Test
    fun `new extraction and input edits clear stale metadata`() =
        runBlocking {
            var decline = false
            lateinit var controller: PdfExtractorController
            controller =
                controller { _, dir, _ ->
                    assertNull(controller.metadata)
                    if (decline) {
                        PdfExtractionOutcome.Declined(dir.resolve("jogger.json"))
                    } else {
                        PdfExtractorFixtures.written(dir.resolve("jogger.json"))
                    }
                }
            controller.updatePdfPath("/cars/jogger.pdf")
            controller.extract()
            assertEquals(PdfExtractorFixtures.metadata, controller.metadata)
            controller.updatePdfPath("/cars/peaq.pdf")
            assertNull(controller.metadata)
            controller.extract()
            controller.reset()
            assertNull(controller.metadata)
            controller.extract()
            decline = true
            controller.extract()
            assertNull(controller.metadata)
        }
}
