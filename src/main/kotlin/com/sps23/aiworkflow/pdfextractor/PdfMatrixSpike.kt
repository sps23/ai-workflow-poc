package com.sps23.aiworkflow.pdfextractor

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class PdfPageGeometry(
    val mediaBox: PdfBox,
    val cropBox: PdfBox,
    val rotation: Int,
)

data class PdfBox(
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
)

data class PdfTextGlyph(
    val text: String,
    val xFromLeft: Float,
    val yFromTop: Float,
    val width: Float,
    val height: Float,
    val direction: Float,
    val fontName: String,
) {
    val centerX: Float get() = xFromLeft + width / 2f
}

data class PdfPageEvidence(
    val pageNumber: Int,
    val geometry: PdfPageGeometry,
    val glyphs: List<PdfTextGlyph>,
)

data class TikaDocumentEvidence(
    val metadata: Map<String, String>,
    val textLength: Int,
    val text: String,
)

object PdfTextGeometryExtractor {
    fun extractPage(
        path: Path,
        pageNumber: Int,
    ): PdfPageEvidence =
        Loader.loadPDF(path.toFile()).use { document ->
            require(pageNumber in 1..document.numberOfPages) {
                "Page $pageNumber is outside PDF page range 1..${document.numberOfPages}"
            }
            val page = document.getPage(pageNumber - 1)
            val glyphs = mutableListOf<PdfTextGlyph>()
            val stripper =
                object : PDFTextStripper() {
                    override fun writeString(
                        text: String,
                        textPositions: MutableList<TextPosition>,
                    ) {
                        textPositions.forEach { position ->
                            glyphs +=
                                PdfTextGlyph(
                                    text = position.unicode,
                                    xFromLeft = position.xDirAdj,
                                    yFromTop = position.yDirAdj,
                                    width = position.widthDirAdj,
                                    height = position.heightDir,
                                    direction = position.dir,
                                    fontName = position.font.name,
                                )
                        }
                    }
                }
            stripper.setStartPage(pageNumber)
            stripper.setEndPage(pageNumber)
            stripper.getText(document)
            PdfPageEvidence(
                pageNumber = pageNumber,
                geometry =
                    PdfPageGeometry(
                        mediaBox = page.mediaBox.toPdfBox(),
                        cropBox = page.cropBox.toPdfBox(),
                        rotation = page.rotation,
                    ),
                glyphs =
                    glyphs.sortedWith(
                        compareBy<PdfTextGlyph> { it.yFromTop }
                            .thenBy { it.xFromLeft }
                            .thenBy { it.text },
                    ),
            )
        }

    private fun PDRectangle.toPdfBox() =
        PdfBox(
            left = lowerLeftX,
            bottom = lowerLeftY,
            width = width,
            height = height,
        )
}

object TikaDocumentProbe {
    fun inspect(path: Path): TikaDocumentEvidence {
        val metadata = Metadata()
        val handler = BodyContentHandler(-1)
        Files.newInputStream(path).buffered().use { input ->
            AutoDetectParser().parse(input, handler, metadata, ParseContext())
        }
        val text = handler.toString()
        return TikaDocumentEvidence(
            metadata = metadata.names().associateWith { metadata.get(it) }.toSortedMap(),
            textLength = text.length,
            text = text,
        )
    }
}

data class MatrixRowReference(
    val rowIndex: Int,
    val yFromTop: Float,
    val markersByTrim: List<String>,
)

data class MatrixEvaluationProfile(
    val columnCenters: List<Float>,
    val markerGlyphs: Set<String>,
    val xBounds: ClosedFloatingPointRange<Float>,
    val rowTolerance: Float,
    val columnTolerance: Float,
)

object PdfMatrixProfiles {
    val VW_GOLF_EXTERIOR =
        MatrixEvaluationProfile(
            columnCenters = listOf(155.0f, 177.8f, 201.55f, 225.34f),
            markerGlyphs = setOf("●", "O", "-"),
            xBounds = 145f..235f,
            rowTolerance = 1.0f,
            columnTolerance = 2.5f,
        )

    val PEUGEOT_5008_OPTIONS =
        MatrixEvaluationProfile(
            columnCenters = listOf(736.2f, 798.6f, 861.3f),
            markerGlyphs = setOf("⚫", "\uF06D", "-"),
            xBounds = 725f..870f,
            rowTolerance = 5.0f,
            columnTolerance = 5.0f,
        )
}

data class MatrixEvaluation(
    val totalCells: Int,
    val detectedCells: Int,
    val exactCells: Int,
    val reviewCells: Int,
    val diagnostics: List<String>,
) {
    val coverage: Double get() = detectedCells.toDouble() / totalCells
    val exactness: Double get() = if (detectedCells == 0) 0.0 else exactCells.toDouble() / detectedCells
}

object PdfMatrixEvaluator {
    fun readReference(
        path: Path,
        profile: MatrixEvaluationProfile,
    ): List<MatrixRowReference> =
        Files
            .readAllLines(path)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val fields = line.split('|')
                require(fields.size == profile.columnCenters.size + 2) {
                    "Reference row ${index + 1} must contain row index, y coordinate, and " +
                        "${profile.columnCenters.size} markers"
                }
                MatrixRowReference(
                    rowIndex = fields[0].toInt(),
                    yFromTop = fields[1].toFloat(),
                    markersByTrim = fields.drop(2),
                )
            }.also { rows ->
                require(rows.isNotEmpty()) { "Matrix reference is empty: $path" }
                require(rows.map { it.rowIndex } == (1..rows.size).toList()) {
                    "Matrix reference row indexes must be contiguous and ordered from 1"
                }
                require(rows.flatMap { it.markersByTrim }.all { it == "?" || it in profile.markerGlyphs }) {
                    "Matrix reference contains a marker outside the verified legend symbols"
                }
            }

    fun evaluate(
        evidence: PdfPageEvidence,
        reference: List<MatrixRowReference>,
        profile: MatrixEvaluationProfile,
    ): MatrixEvaluation {
        val diagnostics = mutableListOf<String>()
        val assignedGlyphs = mutableMapOf<Pair<Int, Int>, MutableList<PdfTextGlyph>>()
        evidence.glyphs
            .filter { it.text in profile.markerGlyphs }
            .filter { it.centerX in profile.xBounds }
            .filter {
                it.yFromTop in
                    (reference.first().yFromTop - profile.rowTolerance)..(reference.last().yFromTop + profile.rowTolerance)
            }.forEach { glyph ->
                val row =
                    reference
                        .minByOrNull { kotlin.math.abs(it.yFromTop - glyph.yFromTop) }
                        ?.takeIf { kotlin.math.abs(it.yFromTop - glyph.yFromTop) <= profile.rowTolerance }
                val column =
                    profile.columnCenters.indices
                        .minByOrNull { kotlin.math.abs(profile.columnCenters[it] - glyph.centerX) }
                        ?.takeIf {
                            kotlin.math.abs(profile.columnCenters[it] - glyph.centerX) <= profile.columnTolerance
                        }
                if (row != null && column != null) {
                    assignedGlyphs.getOrPut(row.rowIndex to column) { mutableListOf() } += glyph
                } else {
                    diagnostics +=
                        "Unassigned marker '${glyph.text}' at x=${glyph.centerX}, y=${glyph.yFromTop}"
                }
            }

        var detectedCells = 0
        var exactCells = 0
        var reviewCells = 0
        reference.forEach { row ->
            row.markersByTrim.forEachIndexed { column, expected ->
                val actual = assignedGlyphs[row.rowIndex to column].orEmpty()
                when {
                    expected == "?" && actual.isEmpty() -> {
                        reviewCells++
                    }

                    expected == "?" -> {
                        detectedCells++
                        diagnostics +=
                            "row ${row.rowIndex}, trim ${column + 1}: reference is unknown, " +
                            "but found '${actual.joinToString { it.text }}'"
                    }

                    actual.size == 1 -> {
                        detectedCells++
                        if (actual.single().text == expected) {
                            exactCells++
                        } else {
                            diagnostics +=
                                "row ${row.rowIndex}, trim ${column + 1}: expected '$expected', " +
                                "found '${actual.single().text}'"
                        }
                    }

                    else -> {
                        diagnostics +=
                            "row ${row.rowIndex}, trim ${column + 1}: expected one marker, found ${actual.size}"
                        if (actual.isNotEmpty()) {
                            detectedCells++
                        }
                    }
                }
            }
        }
        return MatrixEvaluation(
            totalCells = reference.size * profile.columnCenters.size,
            detectedCells = detectedCells,
            exactCells = exactCells,
            reviewCells = reviewCells,
            diagnostics = diagnostics,
        )
    }

    fun isSupportedMarkerGlyph(
        text: String,
        profile: MatrixEvaluationProfile = PdfMatrixProfiles.VW_GOLF_EXTERIOR,
    ): Boolean = text in profile.markerGlyphs
}

object PdfMatrixSpikeCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 3..4) {
            "Usage: PdfMatrixSpikeCli <pdf-path> <page-number> <ground-truth-reference> [profile]"
        }
        val path = Path.of(args[0])
        val pageNumber = args[1].toInt()
        val profile =
            when (args.getOrNull(3) ?: "vw-golf-exterior") {
                "vw-golf-exterior" -> PdfMatrixProfiles.VW_GOLF_EXTERIOR
                "peugeot-5008-options" -> PdfMatrixProfiles.PEUGEOT_5008_OPTIONS
                else -> error("Unknown matrix profile: ${args[3]}")
            }
        val reference = PdfMatrixEvaluator.readReference(Path.of(args[2]), profile)
        val tika = TikaDocumentProbe.inspect(path)
        val page = PdfTextGeometryExtractor.extractPage(path, pageNumber)
        val evaluation = PdfMatrixEvaluator.evaluate(page, reference, profile)
        val pageCount = tika.metadata["xmpTPg:NPages"] ?: "unreported"
        val producer = tika.metadata["pdf:docinfo:producer"] ?: "unreported"
        println(
            "Tika 3.3.3: pageCount=$pageCount, producer=$producer, " +
                "documentTextChars=${tika.textLength}; text has no cell coordinates",
        )
        println(
            "PDFBox 3.0.8: page=${page.pageNumber}, media=${page.geometry.mediaBox}, " +
                "crop=${page.geometry.cropBox}, rotation=${page.geometry.rotation}, " +
                "textGlyphs=${page.glyphs.size}",
        )
        println(
            "Matrix: ${evaluation.totalCells} cells, ${evaluation.detectedCells} assigned " +
                "(${percent(evaluation.coverage)} coverage), ${evaluation.exactCells} exact " +
                "(${percent(evaluation.exactness)} exactness), ${evaluation.reviewCells} routed to review",
        )
        evaluation.diagnostics.forEach(::println)
        check(evaluation.coverage >= 0.9) {
            "Matrix coverage ${percent(evaluation.coverage)} is below the agreed 90% minimum"
        }
        check(evaluation.exactness == 1.0 && evaluation.diagnostics.isEmpty()) {
            "Matrix has assignment errors; review the diagnostics above"
        }
    }

    private fun percent(value: Double) = String.format(Locale.ROOT, "%.1f%%", value * 100)
}
