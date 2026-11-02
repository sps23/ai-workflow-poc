package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.PowertrainType
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.prompts.AppPrompts
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class LlmRequest(
    val originalRequest: String,
    val requirements: List<RequestTerm>,
    val preferences: List<RequestTerm>,
    val requestedAttributes: List<RequestTerm>,
    val unresolvedPhrases: List<String>,
)

@Serializable
data class RequestTerm(
    val field: CatalogRequestField,
    val operator: RequestOperator,
    val value: String,
    val unit: String?,
    val sourceText: String,
    val metric: RequestMetric? = null,
    val scope: EquipmentScope? = null,
    val fitment: RequestedFitment? = null,
    val measurementBasis: MeasurementBasis? = null,
)

@Serializable
enum class RequestMetric {
    OVERALL_LENGTH,
    OVERALL_WIDTH,
    OVERALL_HEIGHT,
    WHEELBASE,
    BOOT_ALL_SEATS_UP,
    BOOT_SEATS_FOLDED,
    ELECTRIC_RANGE,
    FUEL_CONSUMPTION,
    ELECTRIC_CONSUMPTION,
}

@Serializable
enum class EquipmentScope {
    FRONT_SEATS,
    DRIVER_SEAT,
    REAR_SEATS,
    FRONT,
    REAR,
    FRONT_AND_REAR,
}

@Serializable
enum class RequestedFitment {
    STANDARD_ONLY,
    OPTIONAL_ONLY,
    STANDARD_OR_OPTIONAL,
}

@Serializable
enum class MeasurementBasis {
    WLTP_COMBINED,
    WLTP_CITY,
    WLTP_WEIGHTED_COMBINED,
    WLTP_DEPLETED_BATTERY,
}

@Serializable
enum class CatalogRequestField {
    MAKE,
    MODEL,
    TRIM,
    POWERTRAIN,
    ENGINE,
    TRANSMISSION,
    DRIVETRAIN,
    BODY_STYLE,
    SEATING_CAPACITY,
    PRICE,
    MEASUREMENT,
    EQUIPMENT,
    MARKET,
    MODEL_YEAR,
    OTHER,
}

@Serializable
enum class RequestOperator {
    EQUALS,
    AT_LEAST,
    AT_MOST,
    GREATER_THAN,
    LESS_THAN,
    EXCLUDES,
    CONTAINS,
    MINIMIZE,
    MAXIMIZE,
    DESCRIBE,
    OTHER,
}

object LlmRequestCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(request: LlmRequest): String = json.encodeToString(request)

    fun decode(
        raw: String,
        originalRequest: String,
    ): LlmRequest {
        fun invalid(cause: Throwable? = null): Nothing =
            throw OpenRouterWorkflowException(
                FailureKind.INVALID_RESPONSE,
                "The translated request is invalid or does not preserve the original wording and source phrases.",
                cause,
                diagnostics = OpenRouterErrorDiagnostics("JSON validation"),
                rawOutput = raw,
            )
        val request =
            try {
                val element = json.parseToJsonElement(raw)
                if (!element.hasOnlyTextValues()) invalid()
                json.decodeFromJsonElement<LlmRequest>(element)
            } catch (e: SerializationException) {
                invalid(e)
            }
        val terms = request.requirements + request.preferences + request.requestedAttributes
        if (request.originalRequest != originalRequest ||
            terms.any {
                it.value.isBlank() || it.sourceText.isBlank() || it.sourceText !in originalRequest ||
                    it.unit?.isBlank() == true ||
                    !it.hasValidQualifiers() ||
                    (
                        it.field == CatalogRequestField.POWERTRAIN &&
                            it.value !in PowertrainType.entries.map { powertrain -> powertrain.name }
                    )
            } ||
            request.unresolvedPhrases.any { it.isBlank() || it !in originalRequest } ||
            (terms.isEmpty() && request.unresolvedPhrases.isEmpty())
        ) {
            invalid()
        }
        return request
    }

    private fun RequestTerm.hasValidQualifiers(): Boolean {
        if (scope != null && field != CatalogRequestField.EQUIPMENT) return false
        if (fitment != null && field != CatalogRequestField.EQUIPMENT) return false
        if (measurementBasis != null && metric == null) return false
        val metric = metric ?: return true
        if (field != CatalogRequestField.MEASUREMENT || scope != null || fitment != null) return false
        val expectedUnit =
            when (metric) {
                RequestMetric.OVERALL_LENGTH, RequestMetric.OVERALL_WIDTH,
                RequestMetric.OVERALL_HEIGHT, RequestMetric.WHEELBASE,
                -> "mm"

                RequestMetric.BOOT_ALL_SEATS_UP, RequestMetric.BOOT_SEATS_FOLDED -> "L"

                RequestMetric.ELECTRIC_RANGE -> "km"

                RequestMetric.FUEL_CONSUMPTION -> "L/100km"

                RequestMetric.ELECTRIC_CONSUMPTION -> "kWh/100km"
            }
        val hasTestBasis =
            metric in setOf(RequestMetric.ELECTRIC_RANGE, RequestMetric.FUEL_CONSUMPTION, RequestMetric.ELECTRIC_CONSUMPTION)
        if (measurementBasis != null && !hasTestBasis) return false
        if (measurementBasis in setOf(MeasurementBasis.WLTP_WEIGHTED_COMBINED, MeasurementBasis.WLTP_DEPLETED_BATTERY) &&
            metric != RequestMetric.FUEL_CONSUMPTION
        ) {
            return false
        }
        if (unit != expectedUnit) return false
        if (operator in
            setOf(
                RequestOperator.EQUALS,
                RequestOperator.AT_LEAST,
                RequestOperator.AT_MOST,
                RequestOperator.GREATER_THAN,
                RequestOperator.LESS_THAN,
            )
        ) {
            val number = value.toBigDecimalOrNull() ?: return false
            if (number.signum() < 0) return false
        }
        return true
    }
}

val REQUEST_TRANSLATION_INSTRUCTIONS: String get() = AppPrompts.translation
