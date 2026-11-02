package com.sps23.aiworkflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.renderComposeScene
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionOutcome
import com.sps23.aiworkflow.catalog.extraction.Severity
import com.sps23.aiworkflow.catalog.extraction.Violation
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PdfExtractorScreenTest {
    private fun controller(outcome: suspend (Path, suspend (Path) -> Boolean) -> PdfExtractionOutcome) =
        PdfExtractorController(
            environment = emptyMap(),
            extractAction = { _, dir, _, _, confirm -> outcome(dir, confirm) },
            discoverModels = { listOf(GoogleAiModel("gemini-3.8-flash", "Gemini Flash", 1_000_000, 65_536)) },
            resolveKey = { "fake-key" },
        )

    private fun render(
        controller: PdfExtractorController,
        tab: AppTab = AppTab.PDF_EXTRACTOR,
    ) {
        renderComposeScene(width = 900, height = 1200) {
            MaterialTheme {
                CarCatalogApp(CarPromptController(answer = { _, _, _, _, _, _ -> error("unused") }), controller, tab)
            }
        }.use { image -> assertEquals(900, image.width) }
    }

    @Test
    fun `tab renders idle, written, declined and failed states offline`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val outcomes =
                    listOf<suspend (Path, suspend (Path) -> Boolean) -> PdfExtractionOutcome>(
                        { dir, _ -> PdfExtractorFixtures.written(dir.resolve("jogger.json")) },
                        { dir, _ -> PdfExtractionOutcome.Declined(dir.resolve("jogger.json")) },
                        { dir, _ ->
                            PdfExtractionOutcome.Failed(
                                FailureKind.INVALID_RESPONSE,
                                "Gemini output violates 1 hard requirement(s).",
                                listOf(Violation("R3", Severity.HARD, "decode failed")),
                            )
                        },
                        { _, _ -> PdfExtractionOutcome.Failed(FailureKind.RATE_LIMIT, "Gemini PDF extraction failed (HTTP 429).") },
                    )
                render(controller { _, _ -> error("unused") })
                render(controller { _, _ -> error("unused") }, AppTab.ASSISTANT)
                outcomes.forEach { outcome ->
                    val controller = controller(outcome)
                    controller.updatePdfPath("/cars/jogger.pdf")
                    controller.loadModels()
                    controller.extract()
                    render(controller)
                }
            }
        }

    @Test
    fun `extracting state and overwrite dialog render offline`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val asked = CompletableDeferred<Unit>()
                val controller =
                    controller { dir, confirm ->
                        asked.complete(Unit)
                        if (confirm(dir.resolve("jogger.json"))) {
                            PdfExtractorFixtures.written(dir.resolve("jogger.json"))
                        } else {
                            PdfExtractionOutcome.Declined(dir.resolve("jogger.json"))
                        }
                    }
                controller.updatePdfPath("/cars/jogger.pdf")
                val job = async { controller.extract() }
                asked.await()
                yield()
                render(controller)
                controller.answerOverwrite(false)
                job.await()
            }
        }
}
