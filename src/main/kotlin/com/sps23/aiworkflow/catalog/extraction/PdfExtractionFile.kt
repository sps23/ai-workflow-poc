package com.sps23.aiworkflow.catalog.extraction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PDF_EXTRACTION_SCHEMA_VERSION = 1

/** Where an extracted file came from; ignored by the catalog generator. */
@Serializable
data class ExtractionProvenance(
    val pdfFileName: String,
    val pdfSha256: String,
    /** Response `modelVersion`, or the requested model ID when absent. */
    val model: String,
    /** ISO-8601 UTC instant. */
    val extractedAt: String,
    val inputTokens: Long?,
    /** Billed output: visible candidates plus thinking tokens. */
    val outputTokens: Long,
)

/** On-disk extraction envelope written as `<pdf stem>.json`. */
@Serializable
data class PdfExtractionFile(
    val schemaVersion: Int = PDF_EXTRACTION_SCHEMA_VERSION,
    val provenance: ExtractionProvenance,
    val vehicle: ExtractedVehicle,
) {
    companion object {
        val json: Json =
            Json {
                prettyPrint = true
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = false
            }

        fun encode(file: PdfExtractionFile): String = json.encodeToString(serializer(), file) + "\n"

        fun decode(text: String): PdfExtractionFile = json.decodeFromString(serializer(), text)
    }
}
