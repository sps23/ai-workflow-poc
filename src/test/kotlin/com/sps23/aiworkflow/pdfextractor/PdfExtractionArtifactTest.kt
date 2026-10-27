package com.sps23.aiworkflow.pdfextractor

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfExtractionArtifactTest {
    @Test
    fun `VW annotated reference round trips row trim marker assignments`() {
        val artifact =
            annotatedMatrixArtifact(
                referencePath = "docs/evidence/vw-golf-my27-page-8-exterior-highlights.psv",
                profile = PdfMatrixProfiles.VW_GOLF_EXTERIOR,
                pageNumber = 8,
                pageCount = 14,
                relativePath = "vw/Golf_MY27.pdf",
                make = "vw",
                columns = listOf("Life", "Edition 75", "Style", "R-Line"),
                legend = "● standard; O optional; - unavailable",
            )

        val decoded = PdfExtractionArtifactCodec.decode(PdfExtractionArtifactCodec.encode(artifact))
        val table =
            decoded.pages
                .single { it.pageNumber == 8 }
                .tables
                .single()

        assertEquals(24, table.rows.size)
        assertEquals(listOf("Life", "Edition 75", "Style", "R-Line"), table.columns.map { it.header })
        assertEquals(96, table.cells.size)
        assertEquals("O", table.cells.single { it.rowId == "row-1" && it.columnId == "trim-1" }.rawText)
        assertEquals("optional", table.cells.single { it.rowId == "row-1" && it.columnId == "trim-1" }.interpretedMeaning)
        assertEquals("-", table.cells.single { it.rowId == "row-3" && it.columnId == "trim-3" }.rawText)
        assertEquals("unavailable", table.cells.single { it.rowId == "row-3" && it.columnId == "trim-3" }.interpretedMeaning)
        assertTrue(table.cells.all { it.state == CellState.MARKED })
    }

    @Test
    fun `Peugeot annotated reference preserves private glyph and blank review cell`() {
        val artifact =
            annotatedMatrixArtifact(
                referencePath = "docs/evidence/peugeot-5008-page-17-options.psv",
                profile = PdfMatrixProfiles.PEUGEOT_5008_OPTIONS,
                pageNumber = 17,
                pageCount = 31,
                relativePath = "peugeot/5008-spec-sheet.pdf",
                make = "peugeot",
                columns = listOf("Allure", "GT", "GT Exclusive"),
                legend = "⚫ standard; \uF06D optional; - unavailable",
            )

        val decoded = PdfExtractionArtifactCodec.decode(PdfExtractionArtifactCodec.encode(artifact))
        val table =
            decoded.pages
                .single { it.pageNumber == 17 }
                .tables
                .single()
        val privateGlyphCell = table.cells.single { it.rowId == "row-2" && it.columnId == "trim-1" }
        val blankCell = table.cells.single { it.rowId == "row-4" && it.columnId == "trim-1" }

        assertEquals(4, table.rows.size)
        assertEquals(3, table.columns.size)
        assertEquals(12, table.cells.size)
        assertEquals("\uF06D", privateGlyphCell.markers.single().rawMarker)
        assertEquals(CellState.UNKNOWN, blankCell.state)
        assertTrue(blankCell.markers.isEmpty())
        assertEquals(ReviewStatus.NEEDS_REVIEW, blankCell.reviewStatus)
        assertEquals(ReviewStatus.NEEDS_REVIEW, table.reviewStatus)
    }

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

    private fun annotatedMatrixArtifact(
        referencePath: String,
        profile: MatrixEvaluationProfile,
        pageNumber: Int,
        pageCount: Int,
        relativePath: String,
        make: String,
        columns: List<String>,
        legend: String,
    ): PdfExtractionArtifact {
        require(columns.size == profile.columnCenters.size)
        val reference = PdfMatrixEvaluator.readReference(Path.of(referencePath), profile)
        val documentId = relativePath
        val sha256 = "b".repeat(64)
        val tableBounds = PdfArtifactBounds(0.0, 0.0, 960.0, 540.0)

        fun provenance(bounds: PdfArtifactBounds) =
            PdfEvidenceProvenance(
                documentId = documentId,
                documentSha256 = sha256,
                pageNumber = pageNumber,
                bounds = bounds,
                extractionMethod = "Annotated matrix reference",
            )

        val rows =
            reference.map { row ->
                PdfArtifactRow(
                    id = "row-${row.rowIndex}",
                    label = "Feature row ${row.rowIndex}",
                    bounds = PdfArtifactBounds(0.0, row.yFromTop.toDouble() - 2, 960.0, 4.0),
                )
            }
        val artifactColumns =
            columns.mapIndexed { index, header ->
                PdfArtifactColumn(
                    id = "trim-${index + 1}",
                    header = header,
                    bounds = PdfArtifactBounds(profile.columnCenters[index].toDouble() - 10, 0.0, 20.0, 540.0),
                )
            }
        val cells =
            reference.flatMap { row ->
                row.markersByTrim.mapIndexed { columnIndex, rawMarker ->
                    val cellBounds =
                        PdfArtifactBounds(
                            x = profile.columnCenters[columnIndex].toDouble() - 3,
                            y = row.yFromTop.toDouble() - 2,
                            width = 6.0,
                            height = 4.0,
                        )
                    val unknown = rawMarker == "?"
                    val meaning =
                        when (rawMarker) {
                            "O", "\uF06D" -> "optional"
                            "●", "⚫" -> "standard"
                            "-" -> "unavailable"
                            else -> null
                        }
                    PdfArtifactCell(
                        rowId = "row-${row.rowIndex}",
                        columnId = "trim-${columnIndex + 1}",
                        rawText = if (unknown) "" else rawMarker,
                        state = if (unknown) CellState.UNKNOWN else CellState.MARKED,
                        markers =
                            if (unknown) {
                                emptyList()
                            } else {
                                listOf(
                                    PdfMarkerEvidence(
                                        rawMarker = rawMarker,
                                        kind = MarkerKind.TEXT_GLYPH,
                                        provenance = provenance(cellBounds),
                                    ),
                                )
                            },
                        provenance = provenance(cellBounds),
                        interpretedMeaning = if (unknown) null else meaning,
                        reviewStatus = if (unknown) ReviewStatus.NEEDS_REVIEW else ReviewStatus.REVIEWED,
                    )
                }
            }
        val matrix =
            PdfArtifactTable(
                bounds = tableBounds,
                coordinateSystem = PdfCoordinateSystem.PDF_POINTS_TOP_LEFT,
                rows = rows,
                columns = artifactColumns,
                cells = cells,
                legendReference =
                    PdfLegendReference(
                        text = legend,
                        provenance = provenance(tableBounds),
                    ),
                reviewStatus = ReviewStatus.NEEDS_REVIEW,
            )

        return PdfExtractionArtifact(
            source =
                PdfArtifactSource(
                    documentId = documentId,
                    relativePath = relativePath,
                    fileName = relativePath.substringAfterLast('/'),
                    make = make,
                    modelCoverage = emptyList(),
                    sha256 = sha256,
                    fileSizeBytes = 1,
                ),
            extraction =
                PdfExtractionSummary(
                    parserName = "PDFBox",
                    parserVersion = "3.0.8",
                    pageCount = pageCount,
                    status = PdfExtractionStatus.NEEDS_REVIEW,
                ),
            pages =
                (1..pageCount).map { currentPage ->
                    PdfArtifactPage(
                        pageNumber = currentPage,
                        text = "",
                        tables = if (currentPage == pageNumber) listOf(matrix) else emptyList(),
                    )
                },
        )
    }

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
