package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionRequirements
import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACTION_SCHEMA_VERSION
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.catalog.extraction.Severity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Parses one catalog source JSON in either supported shape:
 * - a root `schemaVersion` key -> a promoted [PdfExtractionFile] (strict decode + hard rules R5-R13/R18;
 *   provenance is ignored);
 * - otherwise the hand-written brand "Data Model" shape via [CarDataModelParser].
 */
object CatalogSourceParser {
    const val SCHEMA_VERSION_KEY = "schemaVersion"

    fun parse(
        json: String,
        sourcePath: String,
    ): CarDataModel {
        val text = json.removePrefix("\uFEFF")
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (exception: SerializationException) {
                throw DataModelParseException("$sourcePath: invalid JSON", exception)
            }
        val version = root?.get(SCHEMA_VERSION_KEY) ?: return CarDataModelParser.parse(text, sourcePath)
        if ((version as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != PDF_EXTRACTION_SCHEMA_VERSION) {
            throw DataModelParseException(
                "$sourcePath: unsupported $SCHEMA_VERSION_KEY $version; this build reads extraction files " +
                    "with $SCHEMA_VERSION_KEY $PDF_EXTRACTION_SCHEMA_VERSION.",
            )
        }
        val file =
            try {
                PdfExtractionFile.json.decodeFromJsonElement(PdfExtractionFile.serializer(), root)
            } catch (exception: SerializationException) {
                throw DataModelParseException("$sourcePath: invalid extraction file: ${firstLine(exception)}", exception)
            } catch (exception: IllegalArgumentException) {
                throw DataModelParseException("$sourcePath: invalid extraction file: ${firstLine(exception)}", exception)
            }
        val hard = ExtractionRequirements.validateVehicle(file.vehicle).filter { it.severity == Severity.HARD }
        if (hard.isNotEmpty()) {
            throw DataModelParseException(
                "$sourcePath: extraction file violates ${hard.size} hard requirement(s):\n" +
                    hard.joinToString("\n") { "  ${it.rule}: ${it.message}" },
            )
        }
        return file.vehicle.toCarDataModel(sourcePath)
    }

    private fun firstLine(exception: Exception): String? = exception.message?.lineSequence()?.firstOrNull()
}
