package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.catalog.datamodel.CarDataModel
import com.sps23.aiworkflow.catalog.datamodel.DataModelValues
import com.sps23.aiworkflow.catalog.datamodel.TechnicalSpecifications
import com.sps23.aiworkflow.catalog.datamodel.Trim
import com.sps23.aiworkflow.catalog.datamodel.VehicleOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * The vehicle part an LLM extracts from one source PDF, in the normalized catalog shape.
 *
 * Identity and provenance fields (`id`, `sourcePath`, `sourcePdfPath`) are deliberately absent:
 * code computes them, the model never supplies them.
 */
@Serializable
data class ExtractedVehicle(
    val title: String,
    val make: String,
    val model: String,
    val effectiveDate: String? = null,
    /** `YYYY-MM`, or null when the source does not state a month. */
    val effectivePeriod: String? = null,
    val trims: List<Trim>,
    val options: List<VehicleOption> = emptyList(),
    val technicalSpecifications: TechnicalSpecifications,
    val additional: Map<String, String> = emptyMap(),
) {
    fun toCarDataModel(
        sourcePath: String,
        sourcePdfPath: String? = null,
    ): CarDataModel =
        CarDataModel(
            id = DataModelValues.slug("$make $model"),
            sourcePath = sourcePath,
            title = title,
            make = make,
            model = model,
            effectiveDate = effectiveDate,
            effectivePeriod = parsePeriod(effectivePeriod) ?: DataModelValues.effectivePeriod(effectiveDate),
            trims = trims,
            options = options,
            technicalSpecifications = technicalSpecifications,
            additional = additional,
            sourcePdfPath = sourcePdfPath,
        )

    companion object {
        /** Gemini `responseJsonSchema` for this type, derived from its serial descriptor. */
        val jsonSchema: JsonObject by lazy { JsonSchemaGenerator.generate(serializer().descriptor) }

        /** Strict decoding: unknown keys, unknown enum values and missing required fields fail. */
        val strictJson: Json =
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
                coerceInputValues = false
                isLenient = false
            }

        private fun parsePeriod(text: String?): YearMonth? =
            text?.let {
                try {
                    YearMonth.parse(it.trim())
                } catch (_: DateTimeParseException) {
                    null
                }
            }
    }
}
