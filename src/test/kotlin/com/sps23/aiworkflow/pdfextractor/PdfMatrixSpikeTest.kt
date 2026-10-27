package com.sps23.aiworkflow.pdfextractor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfMatrixSpikeTest {
    @Test
    fun `annotated real-matrix references retain expected coverage and exactness`() {
        assertReferenceMetrics(
            referencePath = "docs/evidence/vw-golf-my27-page-8-exterior-highlights.psv",
            profile = PdfMatrixProfiles.VW_GOLF_EXTERIOR,
            pageNumber = 8,
            expectedTotal = 96,
            expectedDetected = 96,
            expectedReview = 0,
        )
        assertReferenceMetrics(
            referencePath = "docs/evidence/peugeot-5008-page-17-options.psv",
            profile = PdfMatrixProfiles.PEUGEOT_5008_OPTIONS,
            pageNumber = 17,
            expectedTotal = 12,
            expectedDetected = 11,
            expectedReview = 1,
        )
    }

    @Test
    fun `PDFBox text positions preserve page geometry and glyph coordinates`() {
        val pdf = Files.createTempFile("pdf-geometry", ".pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(300f, 400f))
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(40f, 350f)
                    content.showText("Coordinates")
                    content.endText()
                }
                document.save(pdf.toFile())
            }

            val evidence = PdfTextGeometryExtractor.extractPage(pdf, 1)

            assertEquals(1, evidence.pageNumber)
            assertEquals(PdfBox(0f, 0f, 300f, 400f), evidence.geometry.mediaBox)
            assertEquals(evidence.geometry.mediaBox, evidence.geometry.cropBox)
            assertEquals(0, evidence.geometry.rotation)
            val firstGlyph = evidence.glyphs.first { it.text == "C" }
            assertTrue(abs(firstGlyph.xFromLeft - 40f) < 0.1f)
            assertTrue(abs(firstGlyph.yFromTop - 50f) < 1f)
            assertTrue(firstGlyph.width > 0f)
            assertFalse(PdfMatrixEvaluator.isSupportedMarkerGlyph("*"))
            assertFalse(PdfMatrixEvaluator.isSupportedMarkerGlyph(""))
            assertTrue(PdfMatrixEvaluator.isSupportedMarkerGlyph("⚫", PdfMatrixProfiles.PEUGEOT_5008_OPTIONS))
            assertTrue(PdfMatrixEvaluator.isSupportedMarkerGlyph("\uF06D", PdfMatrixProfiles.PEUGEOT_5008_OPTIONS))
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    @Test
    fun `explicitly blank reference cells remain unknown and are excluded from coverage`() {
        val reference =
            listOf(
                MatrixRowReference(
                    rowIndex = 1,
                    yFromTop = 100f,
                    markersByTrim = listOf("⚫", "\uF06D", "?"),
                ),
            )
        val evidence =
            PdfPageEvidence(
                pageNumber = 1,
                geometry =
                    PdfPageGeometry(
                        mediaBox = PdfBox(0f, 0f, 960f, 540f),
                        cropBox = PdfBox(0f, 0f, 960f, 540f),
                        rotation = 0,
                    ),
                glyphs =
                    listOf(
                        PdfTextGlyph("⚫", 733.6f, 100f, 5.2f, 2f, 0f, "Wingdings"),
                        PdfTextGlyph("\uF06D", 793.8f, 100f, 6.6f, 2f, 0f, "Wingdings"),
                    ),
            )

        val result =
            PdfMatrixEvaluator.evaluate(
                evidence,
                reference,
                PdfMatrixProfiles.PEUGEOT_5008_OPTIONS,
            )

        assertEquals(3, result.totalCells)
        assertEquals(2, result.detectedCells)
        assertEquals(2, result.exactCells)
        assertEquals(1, result.reviewCells)
        assertEquals(2.0 / 3.0, result.coverage)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `unsupported and out-of-region glyphs are not assigned to cells`() {
        val reference =
            listOf(
                MatrixRowReference(
                    rowIndex = 1,
                    yFromTop = 100f,
                    markersByTrim = listOf("●", "●", "●", "●"),
                ),
            )
        val evidence =
            PdfPageEvidence(
                pageNumber = 8,
                geometry =
                    PdfPageGeometry(
                        mediaBox = PdfBox(0f, 0f, 960f, 540f),
                        cropBox = PdfBox(0f, 0f, 960f, 540f),
                        rotation = 0,
                    ),
                glyphs =
                    listOf(
                        PdfTextGlyph("*", 154f, 100f, 2f, 2f, 0f, "Fixture"),
                        PdfTextGlyph("●", 158f, 100f, 2f, 2f, 0f, "Fixture"),
                    ),
            )

        val result = PdfMatrixEvaluator.evaluate(evidence, reference, PdfMatrixProfiles.VW_GOLF_EXTERIOR)

        assertEquals(4, result.totalCells)
        assertEquals(0, result.detectedCells)
        assertEquals(0, result.exactCells)
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun `Tika probe returns document text and metadata without cell coordinates`() {
        val pdf = Files.createTempFile("pdf-tika-evidence", ".pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.newLineAtOffset(40f, 700f)
                    content.showText("Matrix probe text")
                    content.endText()
                }
                document.save(pdf.toFile())
            }

            val evidence = TikaDocumentProbe.inspect(pdf)

            assertTrue(evidence.text.contains("Matrix probe text"))
            assertTrue(evidence.textLength > 0)
            assertTrue(evidence.metadata.isNotEmpty())
        } finally {
            Files.deleteIfExists(pdf)
        }
    }

    private fun assertReferenceMetrics(
        referencePath: String,
        profile: MatrixEvaluationProfile,
        pageNumber: Int,
        expectedTotal: Int,
        expectedDetected: Int,
        expectedReview: Int,
    ) {
        val reference = PdfMatrixEvaluator.readReference(Path.of(referencePath), profile)
        val glyphs =
            reference.flatMap { row ->
                row.markersByTrim.mapIndexedNotNull { column, marker ->
                    if (marker == "?") {
                        null
                    } else {
                        PdfTextGlyph(
                            text = marker,
                            xFromLeft = profile.columnCenters[column],
                            yFromTop = row.yFromTop,
                            width = 0f,
                            height = 0f,
                            direction = 0f,
                            fontName = "annotated reference",
                        )
                    }
                }
            }
        val evidence =
            PdfPageEvidence(
                pageNumber = pageNumber,
                geometry =
                    PdfPageGeometry(
                        mediaBox = PdfBox(0f, 0f, 960f, 540f),
                        cropBox = PdfBox(0f, 0f, 960f, 540f),
                        rotation = 0,
                    ),
                glyphs = glyphs,
            )

        val result = PdfMatrixEvaluator.evaluate(evidence, reference, profile)

        assertEquals(expectedTotal, result.totalCells)
        assertEquals(expectedDetected, result.detectedCells)
        assertEquals(expectedDetected, result.exactCells)
        assertEquals(expectedReview, result.reviewCells)
        assertEquals(1.0, result.exactness)
        assertEquals(expectedDetected.toDouble() / expectedTotal, result.coverage)
        assertTrue(result.diagnostics.isEmpty())
    }
}
