package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterErrorDiagnostics
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class CarSelection(
    val cars: List<SelectedCar>,
)

@Serializable
data class SelectedCar(
    val configurationId: String,
    val car: String,
    val trim: String?,
    val options: List<RequestedOption>,
)

@Serializable
data class RequestedOption(
    val name: String,
    val status: FitmentState,
    val details: String,
    val sources: List<String>,
)

object CarSelectionCodec {
    fun decode(rawOutput: String): CarSelection {
        val result =
            try {
                val json = Json.parseToJsonElement(rawOutput)
                if (!json.hasOnlyTextValues()) invalid(rawOutput)
                Json.decodeFromJsonElement<CarSelection>(json)
            } catch (e: SerializationException) {
                invalid(rawOutput, e)
            }
        if (result.cars.distinctBy { it.configurationId }.size != result.cars.size ||
            result.cars.any { car ->
                car.configurationId.isBlank() || car.car.isBlank() || car.trim?.isBlank() == true ||
                    car.options.any { option ->
                        option.name.isBlank() || option.sources.any { !it.matches(Regex(".+:[1-9][0-9]*")) } ||
                            option.sources.distinct().size != option.sources.size
                    } ||
                    car.options.distinctBy { it.name }.size != car.options.size
            }
        ) {
            invalid(rawOutput)
        }
        return result
    }

    private fun invalid(
        rawOutput: String,
        cause: Throwable? = null,
    ): Nothing =
        throw OpenRouterWorkflowException(
            FailureKind.INVALID_RESPONSE,
            "The model output does not match the required cars/trim/options JSON contract.",
            cause,
            diagnostics = OpenRouterErrorDiagnostics("Response JSON validation"),
            rawOutput = rawOutput,
        )
}

internal fun JsonElement.hasOnlyTextValues(): Boolean =
    when (this) {
        is JsonObject -> values.all { it.hasOnlyTextValues() }
        is JsonArray -> all { it.hasOnlyTextValues() }
        is JsonPrimitive -> this == JsonNull || isString
    }

fun FitmentState.displayLabel(): String =
    when (this) {
        FitmentState.STANDARD -> "Standard"
        FitmentState.OPTIONAL -> "Optional"
        FitmentState.NOT_AVAILABLE -> "Not available"
        FitmentState.UNKNOWN -> "Unknown"
    }

fun CarSelection.toReadableText(catalog: DataModelCatalogContext? = null): String =
    if (cars.isEmpty()) {
        "No cars returned."
    } else {
        cars.joinToString("\n\n") { car ->
            buildString {
                appendLine("Car: ${car.car}")
                appendLine("Trim: ${car.trim ?: "Unknown"}")
                appendLine("Configuration: ${car.configurationId}")
                catalog?.details(car)?.let { details ->
                    appendLine(details.engine)
                    details.priceLines.forEach { appendLine(it) }
                }
                if (car.options.isEmpty()) {
                    append("No requested options returned.")
                } else {
                    append("Requested option\tAvailability\tDetails\tSources")
                    car.options.forEach { option ->
                        append("\n${option.name}\t${option.status.displayLabel()}\t${option.details}\t${option.sources.joinToString(", ")}")
                    }
                }
            }
        }
    }
