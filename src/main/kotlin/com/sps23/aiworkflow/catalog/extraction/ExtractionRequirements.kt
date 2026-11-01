package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.catalog.datamodel.CarDataModel
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalog
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogArtifact
import com.sps23.aiworkflow.catalog.datamodel.Dimensions
import com.sps23.aiworkflow.catalog.datamodel.NumericRange
import com.sps23.aiworkflow.catalog.datamodel.PriceBasis
import com.sps23.aiworkflow.catalog.datamodel.PriceFact
import com.sps23.aiworkflow.catalog.datamodel.Trim
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

enum class Severity { HARD, SOFT }

data class Violation(
    val rule: String,
    val severity: Severity,
    val message: String,
)

data class ValidationReport(
    val vehicle: ExtractedVehicle?,
    val violations: List<Violation>,
) {
    val hardViolations: List<Violation> get() = violations.filter { it.severity == Severity.HARD }
    val softViolations: List<Violation> get() = violations.filter { it.severity == Severity.SOFT }
    val passed: Boolean get() = hardViolations.isEmpty()
}

/**
 * Requirement checks (R1-R18) for one LLM extraction response.
 * Collects every violation instead of stopping at the first one, so one paid run yields a full report.
 * Reference checks R14-R17 run only when a reference model is given (tests); production omits it.
 */
object ExtractionRequirements {
    val FORBIDDEN_ROOT_KEYS = setOf("id", "sourcePath", "sourcePdfPath")
    private val listPriceBases =
        setOf(
            PriceBasis.PRICE,
            PriceBasis.RRP,
            PriceBasis.RRP_FROM,
            PriceBasis.RRP_EXCLUDING_GRANTS,
            PriceBasis.RRP_INCLUDING_GRANTS,
            PriceBasis.ON_THE_ROAD,
        )
    private val listPriceMinorUnits = 500_000L..25_000_000L
    private val currencyPattern = Regex("""^[A-Z]{3}$""")
    private val periodPattern = Regex("""^\d{4}-(0[1-9]|1[0-2])$""")
    private val artifactJson = Json { encodeDefaults = true }

    fun validate(
        responseText: String,
        finishReason: String?,
        reference: CarDataModel? = null,
    ): ValidationReport {
        val violations = mutableListOf<Violation>()

        fun hard(
            rule: String,
            message: String,
        ) {
            violations += Violation(rule, Severity.HARD, message)
        }

        if (finishReason != "STOP") hard("R1", "finishReason is ${finishReason ?: "missing"}, expected STOP (MAX_TOKENS = truncated).")

        val text = responseText.removePrefix("\uFEFF").trim()
        val root =
            when {
                text.startsWith("```") -> {
                    null.also { hard("R2", "Response is wrapped in a Markdown code fence.") }
                }

                !text.startsWith("{") || !text.endsWith("}") -> {
                    null.also { hard("R2", "Response is not a single JSON object (must start with '{' and end with '}').") }
                }

                else -> {
                    try {
                        Json.parseToJsonElement(text) as? JsonObject
                            ?: null.also { hard("R2", "Response root is not a JSON object.") }
                    } catch (e: SerializationException) {
                        null.also { hard("R2", "Response is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}") }
                    }
                }
            }
        if (root == null) return ValidationReport(null, violations)

        val forbidden = FORBIDDEN_ROOT_KEYS.intersect(root.keys)
        if (forbidden.isNotEmpty()) hard("R4", "Forbidden root keys present: ${forbidden.sorted()}.")

        val vehicle =
            try {
                ExtractedVehicle.strictJson.decodeFromJsonElement(
                    ExtractedVehicle.serializer(),
                    JsonObject(root - FORBIDDEN_ROOT_KEYS),
                )
            } catch (e: SerializationException) {
                hard("R3", "Strict decode into ExtractedVehicle failed: ${e.message?.lineSequence()?.firstOrNull()}")
                null
            } catch (e: IllegalArgumentException) {
                hard("R3", "Strict decode into ExtractedVehicle failed: ${e.message?.lineSequence()?.firstOrNull()}")
                null
            }
        if (vehicle == null) return ValidationReport(null, violations)

        violations += contentViolations(vehicle, reference)
        if (reference != null) violations += referenceViolations(vehicle, reference)
        return ValidationReport(vehicle, violations)
    }

    /** Hard/soft content rules (R5-R13, R18) for an already decoded vehicle, e.g. a promoted extraction file. */
    fun validateVehicle(vehicle: ExtractedVehicle): List<Violation> = contentViolations(vehicle, null)

    private fun contentViolations(
        vehicle: ExtractedVehicle,
        reference: CarDataModel?,
    ): List<Violation> {
        val out = mutableListOf<Violation>()

        fun hard(
            rule: String,
            message: String,
        ) {
            out += Violation(rule, Severity.HARD, message)
        }

        if (vehicle.title.isBlank() || vehicle.make.isBlank() || vehicle.model.isBlank()) {
            hard("R5", "title, make and model must be non-blank.")
        }
        if (reference != null && !vehicle.make.trim().equals(reference.make.trim(), ignoreCase = true)) {
            hard("R5", "make '${vehicle.make}' differs from reference make '${reference.make}'.")
        }

        vehicle.effectivePeriod?.let {
            if (!periodPattern.matches(it)) hard("R6", "effectivePeriod '$it' is not YYYY-MM.")
        }

        if (vehicle.trims.isEmpty()) hard("R7", "trims must not be empty.")
        val seenTrims = mutableSetOf<String>()
        vehicle.trims.forEach { trim ->
            if (trim.name.isBlank()) hard("R7", "A trim has a blank name.")
            if (!seenTrims.add(normalize(trim.name))) hard("R7", "Duplicate trim name '${trim.name}'.")
            if (trim.variants.isEmpty()) hard("R7", "Trim '${trim.name}' has no variants.")
            trim.variants
                .groupBy { normalize(it.name) }
                .filter { it.value.size > 1 }
                .forEach { (_, duplicates) ->
                    hard("R18", "Trim '${trim.name}' has ${duplicates.size} variants named '${duplicates.first().name}'.")
                }
            trim.variants.forEach { variant ->
                val where = "Trim '${trim.name}' variant '${variant.name}'"
                if (variant.name.isBlank()) hard("R8", "Trim '${trim.name}' has a variant with a blank name.")
                if (variant.trimName != trim.name) hard("R8", "$where has trimName '${variant.trimName}', expected '${trim.name}'.")
                if (variant.prices.isEmpty()) hard("R8", "$where has no prices.")
                variant.prices.forEach { price ->
                    out += priceFactViolations(price, where)
                    val amount = price.amount
                    if (price.basis in listPriceBases && amount != null && amount.minorUnits !in listPriceMinorUnits) {
                        hard(
                            "R10",
                            "$where ${price.basis} ${amount.minorUnits} minor units is outside EUR 5,000-250,000 ('${price.rawText}').",
                        )
                    }
                }
                rangeViolations(where, mapOf("co2GPerKm" to variant.co2GPerKm, "wltpRangeKm" to variant.wltpRangeKm)).let(out::addAll)
            }
        }

        vehicle.options.forEach { option ->
            if (option.name.isBlank()) hard("R12", "An option has a blank name.")
            option.availability.forEach { availability ->
                if (availability.scope.isBlank()) hard("R12", "Option '${option.name}' has availability with a blank scope.")
                availability.price?.let { out += priceFactViolations(it, "Option '${option.name}'") }
            }
        }

        val specs = vehicle.technicalSpecifications
        out += dimensionViolations(specs.dimensions)
        specs.powertrains.forEach { pt ->
            out +=
                rangeViolations(
                    "Powertrain '${pt.name}'",
                    mapOf(
                        "acceleration0To100S" to pt.acceleration0To100S,
                        "wltpRangeKm" to pt.wltpRangeKm,
                        "electricConsumptionKwhPer100Km" to pt.electricConsumptionKwhPer100Km,
                        "fuelConsumptionLPer100Km" to pt.fuelConsumptionLPer100Km,
                        "co2GPerKm" to pt.co2GPerKm,
                    ),
                )
        }

        try {
            val car = vehicle.toCarDataModel("extracted/${vehicle.make}.json", "extracted/${vehicle.make}.pdf")
            val artifact = DataModelCatalogArtifact.from(DataModelCatalog(listOf(car)))
            val encoded = artifactJson.encodeToString(DataModelCatalogArtifact.serializer(), artifact)
            if (artifactJson.decodeFromString(DataModelCatalogArtifact.serializer(), encoded) != artifact) {
                hard("R13", "Catalog artifact does not round-trip unchanged.")
            }
        } catch (e: Exception) {
            hard("R13", "Mapping to CarDataModel / catalog artifact failed: ${e.message}")
        }
        return out
    }

    private fun priceFactViolations(
        price: PriceFact,
        where: String,
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        if (price.rawText.isBlank()) out += Violation("R9", Severity.HARD, "$where ${price.basis} price has blank rawText.")
        val amount = price.amount ?: return out
        if (!currencyPattern.matches(amount.currency)) {
            out += Violation("R9", Severity.HARD, "$where currency '${amount.currency}' is not ISO 4217.")
        }
        if (amount.minorUnits < 0) out += Violation("R9", Severity.HARD, "$where amount ${amount.minorUnits} must be >= 0.")
        price.maxAmount?.let { max ->
            if (max.currency != amount.currency || max.minorUnits < amount.minorUnits) {
                out += Violation("R9", Severity.HARD, "$where maxAmount $max is below amount $amount or uses another currency.")
            }
        }
        return out
    }

    private fun rangeViolations(
        where: String,
        ranges: Map<String, NumericRange?>,
    ): List<Violation> =
        ranges.mapNotNull { (name, range) ->
            range?.takeIf { it.min > it.max }?.let { Violation("R11", Severity.HARD, "$where $name min ${it.min} > max ${it.max}.") }
        }

    private fun dimensionViolations(dimensions: Dimensions): List<Violation> {
        val out =
            rangeViolations(
                "Dimensions",
                mapOf(
                    "lengthMm" to dimensions.lengthMm,
                    "widthMm" to dimensions.widthMm,
                    "heightMm" to dimensions.heightMm,
                    "wheelbaseMm" to dimensions.wheelbaseMm,
                    "bootCapacityLitres" to dimensions.bootCapacityLitres,
                    "maxBootCapacityLitres" to dimensions.maxBootCapacityLitres,
                    "frontTrunkCapacityLitres" to dimensions.frontTrunkCapacityLitres,
                    "brakedTowingKg" to dimensions.brakedTowingKg,
                ),
            ).toMutableList()
        listOf(
            Triple("lengthMm", dimensions.lengthMm, 2_500.0..6_000.0),
            Triple("widthMm", dimensions.widthMm, 1_400.0..2_300.0),
            Triple("bootCapacityLitres", dimensions.bootCapacityLitres, 0.0..3_000.0),
        ).forEach { (name, range, bounds) ->
            if (range != null && (range.min !in bounds || range.max !in bounds)) {
                out += Violation("R11", Severity.HARD, "Dimensions $name ${range.min}-${range.max} outside plausible $bounds.")
            }
        }
        return out
    }

    private fun referenceViolations(
        vehicle: ExtractedVehicle,
        reference: CarDataModel,
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        val extractedByName = vehicle.trims.associateBy { normalize(it.name) }
        val referenceByName = reference.trims.associateBy { normalize(it.name) }

        if (extractedByName.keys != referenceByName.keys) {
            out +=
                Violation(
                    "R14",
                    Severity.HARD,
                    "Trim names differ. Missing: ${(referenceByName.keys - extractedByName.keys).sorted()}; " +
                        "unexpected: ${(extractedByName.keys - referenceByName.keys).sorted()}.",
                )
        }

        referenceByName.forEach { (name, referenceTrim) ->
            val extractedTrim = extractedByName[name] ?: return@forEach
            val referenceMin =
                referenceTrim.variants
                    .flatMap { it.prices }
                    .filter { it.basis in listPriceBases }
                    .mapNotNull { it.amount?.minorUnits }
                    .minOrNull() ?: return@forEach
            val extractedAmounts =
                extractedTrim.variants
                    .flatMap { it.prices }
                    .flatMap { listOfNotNull(it.amount?.minorUnits, it.maxAmount?.minorUnits) }
                    .toSet()
            if (referenceMin !in extractedAmounts) {
                out +=
                    Violation(
                        "R15",
                        Severity.HARD,
                        "Trim '${referenceTrim.name}' lowest reference price $referenceMin minor units not extracted.",
                    )
            }

            softCount(out, "Trim '${referenceTrim.name}' variant count", extractedTrim.variants.size, referenceTrim.variants.size)
            softCount(
                out,
                "Trim '${referenceTrim.name}' equipment item count",
                equipmentCount(extractedTrim),
                equipmentCount(referenceTrim),
            )
            val extractedTypes = extractedTrim.variants.map { it.powertrainType }.sorted()
            val referenceTypes = referenceTrim.variants.map { it.powertrainType }.sorted()
            if (extractedTypes != referenceTypes) {
                out +=
                    Violation(
                        "R16",
                        Severity.SOFT,
                        "Trim '${referenceTrim.name}' powertrain types $extractedTypes differ from reference $referenceTypes.",
                    )
            }
        }
        softCount(out, "Option count", vehicle.options.size, reference.options.size)

        val ext = vehicle.technicalSpecifications.dimensions
        val ref = reference.technicalSpecifications.dimensions
        listOf(
            Triple("lengthMm", ext.lengthMm, ref.lengthMm),
            Triple("widthMm", ext.widthMm, ref.widthMm),
            Triple("heightMm", ext.heightMm, ref.heightMm),
            Triple("wheelbaseMm", ext.wheelbaseMm, ref.wheelbaseMm),
            Triple("bootCapacityLitres", ext.bootCapacityLitres, ref.bootCapacityLitres),
        ).forEach { (name, extracted, expected) ->
            if (extracted != null && expected != null && extracted != expected) {
                out += Violation("R17", Severity.SOFT, "Dimensions $name $extracted differs from reference $expected.")
            }
        }
        return out
    }

    private fun softCount(
        out: MutableList<Violation>,
        label: String,
        extracted: Int,
        reference: Int,
    ) {
        if (abs(extracted - reference) > reference * 0.2) {
            out += Violation("R16", Severity.SOFT, "$label $extracted is not within ±20% of reference $reference.")
        }
    }

    private fun equipmentCount(trim: Trim): Int = trim.standardEquipment.sumOf { it.items.size }

    private fun normalize(name: String): String = name.trim().replace(Regex("""\s+"""), " ").lowercase()
}
