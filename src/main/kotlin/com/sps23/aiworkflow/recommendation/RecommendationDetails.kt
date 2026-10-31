package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.CarDataModelArtifact
import com.sps23.aiworkflow.catalog.datamodel.FitmentState
import com.sps23.aiworkflow.catalog.datamodel.Money
import com.sps23.aiworkflow.catalog.datamodel.OptionAvailability
import com.sps23.aiworkflow.catalog.datamodel.PowertrainSpec
import com.sps23.aiworkflow.catalog.datamodel.PowertrainType
import com.sps23.aiworkflow.catalog.datamodel.PriceBasis
import com.sps23.aiworkflow.catalog.datamodel.PriceFact
import com.sps23.aiworkflow.catalog.datamodel.TrimVariant
import com.sps23.aiworkflow.catalog.datamodel.VehicleOption
import java.util.Locale

data class RecommendationDetails(
    val engine: String,
    val priceLines: List<String>,
)

internal class CatalogConfigurationDetails(
    private val vehicle: CarDataModelArtifact,
    private val trimName: String,
    private val variant: TrimVariant,
) {
    fun describe(options: List<RequestedOption>): RecommendationDetails = RecommendationDetails(engineDescription(), priceLines(options))

    private fun powertrain(): PowertrainSpec? {
        val candidates =
            vehicle.technicalSpecifications.powertrains.filter {
                it.type == variant.powertrainType &&
                    (it.trimsAvailable.isEmpty() || it.trimsAvailable.any { trim -> trim.equals(trimName, true) })
            }
        val byCode = candidates.filter { variant.modelCode != null && it.modelCode == variant.modelCode }
        if (byCode.isNotEmpty()) return byCode.singleOrNull()
        val byName = candidates.filter { it.name.equals(variant.name, true) }
        if (byName.isNotEmpty()) return byName.singleOrNull()
        // Some catalogs use "Selection 90X" in the price row but just "90X" in the specs.
        val shortName = variant.name.removePrefix("$trimName ")
        val byShortName = candidates.filter { normalized(shortName) == normalized(it.name) }
        if (byShortName.isNotEmpty()) return byShortName.singleOrNull()
        val byPower =
            candidates.filter {
                variant.powerHp != null && it.powerHp == variant.powerHp.toDouble() &&
                    (variant.batteryCapacityKwh == null || it.batteryCapacityKwh == variant.batteryCapacityKwh)
            }
        if (byPower.isNotEmpty()) return byPower.singleOrNull()
        val compactName = compact(shortName)
        return candidates.filter { compact(it.name).startsWith(compactName) }.singleOrNull()
    }

    private fun engineDescription(): String {
        val spec = powertrain()
        val electric = variant.powertrainType == PowertrainType.ELECTRIC
        val hybrid =
            variant.powertrainType in
                setOf(PowertrainType.HYBRID, PowertrainType.MILD_HYBRID, PowertrainType.PLUG_IN_HYBRID)
        val parts = mutableListOf(variant.powertrainType.label())
        if (electric || hybrid) {
            val battery = spec?.batteryCapacityKwh ?: variant.batteryCapacityKwh
            if (battery != null) {
                parts += "${number(battery)} kWh battery"
            } else if (electric || variant.powertrainType == PowertrainType.PLUG_IN_HYBRID) {
                parts += "battery capacity unavailable"
            }
            spec?.batteryGrossKwh?.let { parts += "${number(it)} kWh gross battery" }
        }
        if (!electric) {
            parts += spec?.displacementCc?.let { "$it cc" } ?: "engine size unavailable"
        }
        parts += (variant.powerHp?.toDouble() ?: spec?.powerHp)?.let { "${number(it)} hp" }
            ?: (variant.powerKw?.toDouble() ?: spec?.powerKw)?.let { "${number(it)} kW (horsepower unavailable)" }
            ?: "horsepower unavailable"
        val drivetrain =
            spec?.drivetrain
                ?: Regex("""(?i)\b(AWD|4WD|4x4|FWD|RWD)\b""").find(variant.name)?.value
        parts +=
            when {
                drivetrain == null -> {
                    "drivetrain unavailable"
                }

                Regex("""(?i)\b(AWD|4WD|4x4|all[- ]wheel drive|four[- ]wheel drive)\b""").containsMatchIn(drivetrain) -> {
                    "All-wheel drive ($drivetrain)"
                }

                else -> {
                    drivetrain
                }
            }
        return "Engine: ${parts.joinToString(" | ")}"
    }

    private fun priceLines(requested: List<RequestedOption>): List<String> {
        val base = PURCHASE_PRICE_BASES.firstNotNullOfOrNull { basis -> variant.prices.firstOrNull { it.basis == basis } }
        val lines =
            mutableListOf(
                base?.let { "Base price (${it.basis.label()}): ${it.rawText}" } ?: "Base price: unavailable in catalog",
            )
        val paidOptions = requested.filter { it.status == FitmentState.OPTIONAL }
        if (paidOptions.isEmpty()) {
            lines += "Paid options excluded; no additional paid options returned."
            return lines
        }
        var total = base?.exactAmount()
        val matchedCodes = mutableSetOf<String>()
        paidOptions.forEach { request ->
            val option =
                vehicle.options
                    .filter {
                        normalized(request.name) == normalized(it.name) ||
                            (it.code != null && normalized(request.name) == normalized(it.code))
                    }.singleOrNull()
            val availability = option?.availability()
            val price = availability?.takeIf { it.state == FitmentState.OPTIONAL }?.price
            val amount = price?.exactAmount()
            lines +=
                "Option: ${request.name}: " +
                (price?.rawText ?: "price unavailable in catalog") +
                (availability?.note?.let { " ($it)" } ?: "")
            val identity = option?.let { it.code ?: it.name }
            if (identity != null && !matchedCodes.add(identity)) {
                lines += "Total unavailable: duplicate option/package reference."
                total = null
            } else {
                total =
                    total?.let {
                        if (amount != null && amount.currency == it.currency && availability.note.isNullOrBlank()) {
                            Money(Math.addExact(it.minorUnits, amount.minorUnits), it.currency)
                        } else {
                            null
                        }
                    }
            }
        }
        lines += total?.let { "Total with returned options (${base?.basis?.label()}): $it" }
            ?: "Total with returned options: unavailable (unknown, conditional or ambiguous catalog pricing)."
        return lines
    }

    private fun VehicleOption.availability(): OptionAvailability? {
        val exact =
            availability.filter {
                it.scope.equals(variant.name, true) || it.scope.equals(trimName, true) ||
                    (
                        it.scope.split(',').all { scope -> vehicle.trims.any { trim -> trim.name.equals(scope.trim(), true) } } &&
                            it.scope.split(',').any { scope -> scope.trim().equals(trimName, true) }
                    )
            }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val variantScopes =
            availability.filter {
                Regex("""(?i)\b${Regex.escape(it.scope)}\b""").containsMatchIn(variant.name)
            }
        if (variantScopes.isNotEmpty()) return variantScopes.singleOrNull()
        return availability.filter { it.scope.equals("All Trims", true) }.singleOrNull()
    }

    private fun PriceFact.exactAmount(): Money? = amount?.takeIf { maxAmount == null && basis != PriceBasis.RRP_FROM }

    companion object {
        private val PURCHASE_PRICE_BASES =
            listOf(
                PriceBasis.ON_THE_ROAD,
                PriceBasis.PRICE,
                PriceBasis.RRP,
                PriceBasis.RRP_EXCLUDING_GRANTS,
                PriceBasis.RRP_INCLUDING_GRANTS,
                PriceBasis.RRP_FROM,
            )
    }
}

private fun normalized(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

private fun compact(value: String): String = normalized(value).replace(Regex("[^a-z0-9]"), "")

private fun number(value: Double): String =
    java.math.BigDecimal
        .valueOf(value)
        .stripTrailingZeros()
        .toPlainString()

private fun PowertrainType.label(): String =
    when (this) {
        PowertrainType.ELECTRIC -> "Electric"
        PowertrainType.PLUG_IN_HYBRID -> "Plug-in hybrid"
        PowertrainType.HYBRID -> "Hybrid"
        PowertrainType.MILD_HYBRID -> "Mild hybrid"
        PowertrainType.PETROL -> "Petrol"
        PowertrainType.DIESEL -> "Diesel"
        PowertrainType.OTHER -> "Other powertrain"
        PowertrainType.UNKNOWN -> "Powertrain unavailable"
    }

private fun PriceBasis.label(): String =
    when (this) {
        PriceBasis.ON_THE_ROAD -> "on-the-road"
        PriceBasis.PRICE -> "catalog list price"
        PriceBasis.RRP -> "RRP"
        PriceBasis.RRP_EXCLUDING_GRANTS -> "RRP excluding grants"
        PriceBasis.RRP_INCLUDING_GRANTS -> "RRP including grants"
        PriceBasis.RRP_FROM -> "RRP from"
        else -> name
    }
