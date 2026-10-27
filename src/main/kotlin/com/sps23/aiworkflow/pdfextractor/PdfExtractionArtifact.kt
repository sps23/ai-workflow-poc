package com.sps23.aiworkflow.pdfextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PDF_EXTRACTION_SCHEMA_VERSION = 1

@Serializable
data class PdfExtractionArtifact(
    val schemaVersion: Int = PDF_EXTRACTION_SCHEMA_VERSION,
    val source: PdfArtifactSource,
    val extraction: PdfExtractionSummary,
    val pages: List<PdfArtifactPage>,
)

@Serializable
data class PdfArtifactSource(
    val documentId: String,
    val relativePath: String,
    val fileName: String,
    val make: String,
    val modelCoverage: List<PdfModelCoverage>,
    val sha256: String,
    val fileSizeBytes: Long,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class PdfModelCoverage(
    val model: String,
    val confidence: CoverageConfidence,
)

@Serializable
enum class CoverageConfidence {
    KNOWN,
    GUESSED,
}

@Serializable
data class PdfExtractionSummary(
    val parserName: String,
    val parserVersion: String,
    val pageCount: Int?,
    val status: PdfExtractionStatus,
    val diagnostics: List<PdfExtractionDiagnostic> = emptyList(),
)

@Serializable
enum class PdfExtractionStatus {
    COMPLETE,
    NEEDS_REVIEW,
    FAILED,
}

@Serializable
data class PdfExtractionDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val pageNumber: Int? = null,
)

@Serializable
enum class DiagnosticSeverity {
    WARNING,
    ERROR,
}

@Serializable
data class PdfArtifactPage(
    val pageNumber: Int,
    val text: String,
    val blocks: List<PdfArtifactBlock> = emptyList(),
    val tables: List<PdfArtifactTable> = emptyList(),
)

@Serializable
data class PdfArtifactBlock(
    val kind: PdfBlockKind,
    val text: String,
    val bounds: PdfArtifactBounds? = null,
    val extractionMethod: String,
)

@Serializable
enum class PdfBlockKind {
    TEXT,
    OTHER,
}

@Serializable
data class PdfArtifactBounds(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@Serializable
enum class PdfCoordinateSystem {
    PDF_POINTS_TOP_LEFT,
}

@Serializable
data class PdfArtifactTable(
    val bounds: PdfArtifactBounds,
    val coordinateSystem: PdfCoordinateSystem,
    val rows: List<PdfArtifactRow>,
    val columns: List<PdfArtifactColumn>,
    val cells: List<PdfArtifactCell>,
    val legendReference: PdfLegendReference? = null,
    val reviewStatus: ReviewStatus,
)

@Serializable
data class PdfArtifactRow(
    val id: String,
    val label: String,
    val bounds: PdfArtifactBounds,
)

@Serializable
data class PdfArtifactColumn(
    val id: String,
    val header: String,
    val bounds: PdfArtifactBounds,
)

@Serializable
data class PdfArtifactCell(
    val rowId: String,
    val columnId: String,
    val rawText: String,
    val state: CellState,
    val markers: List<PdfMarkerEvidence>,
    val provenance: PdfEvidenceProvenance,
    val interpretedMeaning: String? = null,
    val reviewStatus: ReviewStatus,
)

@Serializable
enum class CellState {
    MARKED,
    UNMARKED,
    UNKNOWN,
    AMBIGUOUS,
}

@Serializable
data class PdfMarkerEvidence(
    val rawMarker: String,
    val kind: MarkerKind,
    val provenance: PdfEvidenceProvenance,
)

@Serializable
enum class MarkerKind {
    TEXT_GLYPH,
    VECTOR,
    RASTER,
    UNKNOWN,
}

@Serializable
data class PdfEvidenceProvenance(
    val documentId: String,
    val documentSha256: String,
    val pageNumber: Int,
    val bounds: PdfArtifactBounds,
    val extractionMethod: String,
)

@Serializable
data class PdfLegendReference(
    val text: String,
    val provenance: PdfEvidenceProvenance,
    val blankCellConvention: BlankCellConvention = BlankCellConvention.UNSPECIFIED,
)

@Serializable
enum class BlankCellConvention {
    UNSPECIFIED,
    UNMARKED_MEANS_NOT_INCLUDED,
}

@Serializable
enum class ReviewStatus {
    REVIEWED,
    NEEDS_REVIEW,
}

object PdfExtractionArtifactCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(artifact: PdfExtractionArtifact): String {
        PdfExtractionArtifactValidator.validate(artifact)
        val stableArtifact =
            artifact.copy(
                source = artifact.source.copy(metadata = artifact.source.metadata.toSortedMap()),
            )
        return json.encodeToString(stableArtifact) + "\n"
    }

    fun decode(value: String): PdfExtractionArtifact =
        json.decodeFromString<PdfExtractionArtifact>(value).also(PdfExtractionArtifactValidator::validate)
}

object PdfExtractionArtifactValidator {
    fun validate(artifact: PdfExtractionArtifact) {
        require(artifact.schemaVersion == PDF_EXTRACTION_SCHEMA_VERSION) {
            "Unsupported PDF extraction schema version: ${artifact.schemaVersion}"
        }
        validateSource(artifact.source)
        validateExtraction(artifact.extraction, artifact.pages)
        artifact.pages.forEach { page ->
            page.blocks.forEach { block ->
                require(block.extractionMethod.isNotBlank()) {
                    "Page ${page.pageNumber} has a block without an extraction method"
                }
                block.bounds?.let(::validateBounds)
            }
            page.tables.forEach { table -> validateTable(artifact, page, table) }
        }
    }

    private fun validateSource(source: PdfArtifactSource) {
        require(source.documentId.isNotBlank()) { "Source document ID must not be blank" }
        require(
            source.relativePath.isNotBlank() &&
                !source.relativePath.startsWith("/") &&
                !source.relativePath.matches(Regex("^[A-Za-z]:.*")) &&
                !source.relativePath.contains('\\') &&
                source.relativePath.split('/').all { it.isNotBlank() && it != "." && it != ".." },
        ) { "Source path must be a normalized relative path: ${source.relativePath}" }
        require(source.fileName == source.relativePath.substringAfterLast('/')) {
            "Source filename must match the final path segment"
        }
        require(source.make.isNotBlank()) { "Source make must not be blank" }
        require(source.sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Source SHA-256 must be 64 lowercase hexadecimal characters"
        }
        require(source.fileSizeBytes >= 0) { "Source file size must not be negative" }
        require(source.modelCoverage.all { it.model.isNotBlank() }) { "Covered model names must not be blank" }
    }

    private fun validateExtraction(
        extraction: PdfExtractionSummary,
        pages: List<PdfArtifactPage>,
    ) {
        require(extraction.parserName.isNotBlank()) { "Parser name must not be blank" }
        require(extraction.parserVersion.isNotBlank()) { "Parser version must not be blank" }
        require(extraction.pageCount == null || extraction.pageCount >= 0) { "Page count must not be negative" }
        require(
            extraction.status == PdfExtractionStatus.FAILED ||
                (extraction.pageCount != null && extraction.pageCount > 0),
        ) { "A successful extraction must report a positive page count" }
        require(
            extraction.status != PdfExtractionStatus.FAILED ||
                extraction.diagnostics.any { it.severity == DiagnosticSeverity.ERROR },
        ) { "Failed extraction must include an error diagnostic" }
        require(pages.map { it.pageNumber }.all { it > 0 }) { "Page numbers must be positive" }
        require(pages.map { it.pageNumber } == pages.map { it.pageNumber }.sorted().distinct()) {
            "Pages must be ordered and have unique page numbers"
        }
        extraction.pageCount?.let { pageCount ->
            require(pages.all { it.pageNumber <= pageCount }) { "Page number exceeds reported page count $pageCount" }
            if (extraction.status != PdfExtractionStatus.FAILED) {
                require(pages.map { it.pageNumber } == (1..pageCount).toList()) {
                    "A successful extraction must include every page in order"
                }
            }
        }
        require(
            extraction.diagnostics.all {
                it.message.isNotBlank() &&
                    (it.pageNumber == null || it.pageNumber > 0) &&
                    (it.pageNumber == null || extraction.pageCount == null || it.pageNumber <= extraction.pageCount)
            },
        ) { "Diagnostics must have a message and a valid page number when specified" }
    }

    private fun validateTable(
        artifact: PdfExtractionArtifact,
        page: PdfArtifactPage,
        table: PdfArtifactTable,
    ) {
        validateBounds(table.bounds)
        val rowIds = table.rows.map { it.id }
        val columnIds = table.columns.map { it.id }
        require(rowIds.all { it.isNotBlank() } && rowIds.distinct().size == rowIds.size) {
            "Table rows must have unique, non-blank IDs"
        }
        require(columnIds.all { it.isNotBlank() } && columnIds.distinct().size == columnIds.size) {
            "Table columns must have unique, non-blank IDs"
        }
        table.rows.forEach { validateBounds(it.bounds) }
        table.columns.forEach { validateBounds(it.bounds) }
        val cellKeys = table.cells.map { it.rowId to it.columnId }
        require(cellKeys.distinct().size == cellKeys.size) { "Table contains duplicate row/column cells" }
        table.cells.forEach { cell ->
            require(cell.rowId in rowIds) { "Cell references unknown row ID: ${cell.rowId}" }
            require(cell.columnId in columnIds) { "Cell references unknown column ID: ${cell.columnId}" }
            validateProvenance(artifact, page, cell.provenance)
            require(cell.markers.all { it.rawMarker.isNotEmpty() }) { "Marker evidence must retain a non-empty raw marker" }
            cell.markers.forEach { marker -> validateProvenance(artifact, page, marker.provenance) }
            when (cell.state) {
                CellState.MARKED -> {
                    require(cell.markers.isNotEmpty()) { "Marked cells must include marker evidence" }
                }

                CellState.UNMARKED -> {
                    require(cell.markers.isEmpty()) { "Unmarked cells cannot include marker evidence" }
                }

                CellState.UNKNOWN, CellState.AMBIGUOUS -> {
                    require(cell.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                        "Unknown and ambiguous cells must be routed to review"
                    }
                }
            }
            if (cell.interpretedMeaning != null) {
                val legend =
                    requireNotNull(table.legendReference) {
                        "Interpreted cell meaning requires a cited legend"
                    }
                require(legend.text.isNotBlank()) { "Interpreted cell meaning requires a cited legend" }
                require(
                    cell.state == CellState.MARKED ||
                        (
                            cell.state == CellState.UNMARKED &&
                                legend.blankCellConvention == BlankCellConvention.UNMARKED_MEANS_NOT_INCLUDED
                        ),
                ) {
                    "Interpreted meaning requires a marked cell or an explicit blank-cell legend convention"
                }
            }
        }
        table.legendReference?.let { legend ->
            require(legend.text.isNotBlank()) { "Legend reference text must not be blank" }
            validateProvenance(artifact, null, legend.provenance)
        }
    }

    private fun validateProvenance(
        artifact: PdfExtractionArtifact,
        page: PdfArtifactPage?,
        provenance: PdfEvidenceProvenance,
    ) {
        require(provenance.documentId == artifact.source.documentId) { "Evidence document ID does not match artifact source" }
        require(provenance.documentSha256 == artifact.source.sha256) { "Evidence hash does not match artifact source" }
        require(provenance.pageNumber > 0) { "Evidence page number must be positive" }
        require(provenance.extractionMethod.isNotBlank()) { "Evidence extraction method must not be blank" }
        validateBounds(provenance.bounds)
        require(artifact.extraction.pageCount == null || provenance.pageNumber <= artifact.extraction.pageCount) {
            "Evidence page number exceeds reported page count"
        }
        require(page == null || provenance.pageNumber == page.pageNumber) {
            "Evidence page number does not match containing page ${page?.pageNumber}"
        }
    }

    private fun validateBounds(bounds: PdfArtifactBounds) {
        require(bounds.x.isFinite() && bounds.y.isFinite() && bounds.width.isFinite() && bounds.height.isFinite()) {
            "Evidence bounds must contain finite coordinates"
        }
        require(bounds.width >= 0 && bounds.height >= 0) { "Evidence bounds cannot have negative dimensions" }
    }
}
