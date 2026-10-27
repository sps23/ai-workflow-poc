package com.sps23.aiworkflow.pdfextractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfExtractionArtifactTest {
    @Test
    fun `matrix artifact round trips with stable JSON and traceable cell evidence`() {
        val artifact = exampleArtifact()

        val firstEncoding = PdfExtractionArtifactCodec.encode(artifact)
        val secondEncoding = PdfExtractionArtifactCodec.encode(artifact)
        val decoded = PdfExtractionArtifactCodec.decode(firstEncoding)
        val matrix =
            decoded.pages
                .single { it.pageNumber == 8 }
                .tables
                .single()

        assertEquals(firstEncoding, secondEncoding)
        assertTrue(firstEncoding.endsWith("\n"))
        assertTrue(firstEncoding.indexOf("\"author\"") < firstEncoding.indexOf("\"producer\""))
        assertEquals(artifact, decoded)
        assertEquals(
            "●",
            matrix.cells
                .single { it.rowId == "feature-1" && it.columnId == "style" }
                .markers
                .single()
                .rawMarker,
        )
        assertEquals("standard", matrix.cells.single { it.rowId == "feature-1" && it.columnId == "style" }.interpretedMeaning)
        assertEquals(CellState.UNKNOWN, matrix.cells.single { it.rowId == "feature-2" && it.columnId == "life" }.state)
        assertTrue(
            matrix.cells
                .single { it.rowId == "feature-2" && it.columnId == "life" }
                .markers
                .isEmpty(),
        )
        assertEquals(
            "document-1",
            matrix.cells
                .first()
                .provenance.documentId,
        )
        assertEquals(
            8,
            matrix.cells
                .first()
                .provenance.pageNumber,
        )
    }

    @Test
    fun `validation rejects invalid source page status and matrix references`() {
        val artifact = exampleArtifact()

        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(artifact.copy(schemaVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                artifact.copy(extraction = artifact.extraction.copy(status = PdfExtractionStatus.FAILED)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                artifact.copy(source = artifact.source.copy(relativePath = "../source.pdf")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                artifact.copy(pages = artifact.pages.reversed()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                withTable(
                    artifact,
                    artifact.pages.single { it.pageNumber == 8 }.tables.single().let { table ->
                        table.copy(cells = table.cells + table.cells.first())
                    },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                withTable(
                    artifact,
                    artifact.pages.single { it.pageNumber == 8 }.tables.single().let { table ->
                        table.copy(cells = listOf(table.cells.first().copy(rowId = "missing-row")))
                    },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                withTable(
                    artifact,
                    artifact.pages.single { it.pageNumber == 8 }.tables.single().let { table ->
                        table.copy(
                            cells =
                                table.cells.map { cell ->
                                    cell.copy(provenance = cell.provenance.copy(documentSha256 = "0".repeat(64)))
                                },
                        )
                    },
                ),
            )
        }
    }

    @Test
    fun `interpreted meaning requires marker evidence or an explicit blank convention`() {
        val artifact = exampleArtifact()
        val table =
            artifact.pages
                .single { it.pageNumber == 8 }
                .tables
                .single()
        val blankCell = table.cells.single { it.state == CellState.UNKNOWN }
        val interpretedBlank = blankCell.copy(state = CellState.UNMARKED, interpretedMeaning = "not included")

        assertFailsWith<IllegalArgumentException> {
            PdfExtractionArtifactValidator.validate(
                withTable(
                    artifact,
                    table.copy(cells = listOf(interpretedBlank)),
                ),
            )
        }
        val confirmedLegend =
            table.legendReference!!.copy(blankCellConvention = BlankCellConvention.UNMARKED_MEANS_NOT_INCLUDED)
        PdfExtractionArtifactValidator.validate(
            withTable(
                artifact,
                table.copy(legendReference = confirmedLegend, cells = listOf(interpretedBlank)),
            ),
        )
    }

    private fun withTable(
        artifact: PdfExtractionArtifact,
        table: PdfArtifactTable,
    ) = artifact.copy(
        pages =
            artifact.pages.map { page ->
                if (page.pageNumber == 8) page.copy(tables = listOf(table)) else page
            },
    )

    private fun exampleArtifact(): PdfExtractionArtifact {
        val sourceId = "document-1"
        val sha256 = "a".repeat(64)
        val bounds = PdfArtifactBounds(x = 100.0, y = 100.0, width = 20.0, height = 10.0)

        fun provenance(pageNumber: Int = 8) =
            PdfEvidenceProvenance(
                documentId = sourceId,
                documentSha256 = sha256,
                pageNumber = pageNumber,
                bounds = bounds,
                extractionMethod = "PDFBox TextPosition",
            )

        fun cell(
            rowId: String,
            columnId: String,
            marker: String?,
            state: CellState,
            meaning: String? = null,
        ) = PdfArtifactCell(
            rowId = rowId,
            columnId = columnId,
            rawText = marker.orEmpty(),
            state = state,
            markers =
                marker
                    ?.let {
                        listOf(
                            PdfMarkerEvidence(
                                rawMarker = it,
                                kind = MarkerKind.TEXT_GLYPH,
                                provenance = provenance(),
                            ),
                        )
                    }.orEmpty(),
            provenance = provenance(),
            interpretedMeaning = meaning,
            reviewStatus = if (state == CellState.UNKNOWN) ReviewStatus.NEEDS_REVIEW else ReviewStatus.REVIEWED,
        )

        val matrix =
            PdfArtifactTable(
                bounds = bounds,
                coordinateSystem = PdfCoordinateSystem.PDF_POINTS_TOP_LEFT,
                rows =
                    listOf(
                        PdfArtifactRow("feature-1", "Feature row 1", bounds),
                        PdfArtifactRow("feature-2", "Feature row 2", bounds),
                    ),
                columns =
                    listOf(
                        PdfArtifactColumn("life", "Life", bounds),
                        PdfArtifactColumn("style", "Style", bounds),
                    ),
                cells =
                    listOf(
                        cell("feature-1", "life", "O", CellState.MARKED, "optional"),
                        cell("feature-1", "style", "●", CellState.MARKED, "standard"),
                        cell("feature-2", "life", null, CellState.UNKNOWN),
                        cell("feature-2", "style", "-", CellState.MARKED, "unavailable"),
                    ),
                legendReference =
                    PdfLegendReference(
                        text = "● standard; O optional; - unavailable",
                        provenance = provenance(),
                    ),
                reviewStatus = ReviewStatus.NEEDS_REVIEW,
            )

        return PdfExtractionArtifact(
            source =
                PdfArtifactSource(
                    documentId = sourceId,
                    relativePath = "vw/Golf_MY27.pdf",
                    fileName = "Golf_MY27.pdf",
                    make = "vw",
                    modelCoverage = listOf(PdfModelCoverage("Golf", CoverageConfidence.KNOWN)),
                    sha256 = sha256,
                    fileSizeBytes = 100,
                    metadata = linkedMapOf("producer" to "PDF tool", "author" to "Manufacturer"),
                ),
            extraction =
                PdfExtractionSummary(
                    parserName = "PDFBox",
                    parserVersion = "3.0.8",
                    pageCount = 14,
                    status = PdfExtractionStatus.NEEDS_REVIEW,
                ),
            pages =
                (1..14).map { pageNumber ->
                    PdfArtifactPage(
                        pageNumber = pageNumber,
                        text = if (pageNumber == 8) "Feature matrix" else "",
                        tables = if (pageNumber == 8) listOf(matrix) else emptyList(),
                    )
                },
        )
    }
}
