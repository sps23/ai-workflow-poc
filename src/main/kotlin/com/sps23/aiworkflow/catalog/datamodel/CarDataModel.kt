package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.Serializable
import java.time.YearMonth

/**
 * In-memory catalog built from the per-car "Data Model" JSON files under `car-pricelists/`.
 *
 * Each source file uses a slightly different shape; the parser normalizes them into this model
 * and keeps any field it does not map as a raw string in the `additional` maps.
 */
data class DataModelCatalog(
    val vehicles: List<CarDataModel>,
) {
    fun byId(id: String): CarDataModel? = vehicles.firstOrNull { it.id == id }

    fun byMake(make: String): List<CarDataModel> = vehicles.filter { it.make.equals(make, ignoreCase = true) }
}

data class CarDataModel(
    val id: String,
    val sourcePath: String,
    /** Model string exactly as published, e.g. "Peugeot 5008 (Including Electric & Hybrid)". */
    val title: String,
    val make: String,
    val model: String,
    val effectiveDate: String?,
    val effectivePeriod: YearMonth?,
    val trims: List<Trim>,
    val options: List<VehicleOption>,
    val technicalSpecifications: TechnicalSpecifications,
    val additional: Map<String, String> = emptyMap(),
    /** Resource-relative path of the matched source PDF, when a generator has linked one. */
    val sourcePdfPath: String? = null,
) {
    val variants: List<TrimVariant> get() = trims.flatMap { it.variants }

    val colours: List<VehicleOption>
        get() = options.filter { it.category == OptionCategory.PAINT || it.category == OptionCategory.ROOF_COLOUR }

    fun trim(name: String): Trim? = trims.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun option(code: String): VehicleOption? = options.firstOrNull { it.code == code }
}

@Serializable
data class Trim(
    val name: String,
    val variants: List<TrimVariant>,
    val standardEquipment: List<EquipmentGroup>,
    val additional: Map<String, String> = emptyMap(),
) {
    val allEquipmentItems: List<String> get() = standardEquipment.flatMap { it.items }
}

/** One priced row of a trim: a specific powertrain/battery/power combination. */
@Serializable
data class TrimVariant(
    val name: String,
    val trimName: String,
    val modelCode: String?,
    val powertrainType: PowertrainType,
    val batteryCapacityKwh: Double?,
    val powerHp: Int?,
    val powerKw: Int?,
    val co2GPerKm: NumericRange?,
    val wltpRangeKm: NumericRange?,
    val electricConsumptionKwhPer100Km: Double?,
    val taxBand: String?,
    val vrtRatePercent: Double?,
    val prices: List<PriceFact>,
    val additional: Map<String, String> = emptyMap(),
) {
    fun price(basis: PriceBasis): PriceFact? = prices.firstOrNull { it.basis == basis }
}

@Serializable
enum class PowertrainType {
    ELECTRIC,
    PLUG_IN_HYBRID,
    HYBRID,
    MILD_HYBRID,
    PETROL,
    DIESEL,
    OTHER,
    UNKNOWN,
}

@Serializable
enum class PriceBasis {
    /** Unlabelled list price as published. */
    PRICE,
    RRP,
    RRP_FROM,
    RRP_EXCLUDING_GRANTS,
    RRP_INCLUDING_GRANTS,
    ON_THE_ROAD,
    VRT_REBATE,
    SEAI_GRANT,
    ANNUAL_ROAD_TAX,
    OPTION,
}

/** A price as published; [amount] is null when the source text is not a plain amount. */
@Serializable
data class PriceFact(
    val basis: PriceBasis,
    val amount: Money?,
    val maxAmount: Money? = null,
    val rawText: String,
)

@Serializable
data class Money(
    val minorUnits: Long,
    val currency: String = "EUR",
) {
    override fun toString(): String = "%s %d.%02d".format(currency, minorUnits / 100, minorUnits % 100)
}

@Serializable
data class NumericRange(
    val min: Double,
    val max: Double,
) {
    constructor(value: Double) : this(value, value)

    val isSingleValue: Boolean get() = min == max
}

@Serializable
data class EquipmentGroup(
    /** Source category path, e.g. "safety_and_security" or "v1_base/exterior". */
    val category: String,
    /** True when the group lists items added on top of a lower trim/variant. */
    val isAddition: Boolean,
    val items: List<String>,
)

@Serializable
enum class OptionCategory {
    PAINT,
    ROOF_COLOUR,
    WHEELS,
    INTERIOR,
    PACKAGE,
    SINGLE_OPTION,
    ACCESSORY_PACK,
    OTHER,
}

@Serializable
data class VehicleOption(
    val code: String?,
    val name: String,
    val category: OptionCategory,
    /** Category label as published in the source (section name or category text). */
    val categoryLabel: String,
    val type: String? = null,
    val items: List<String> = emptyList(),
    val availability: List<OptionAvailability> = emptyList(),
    val additional: Map<String, String> = emptyMap(),
) {
    fun availabilityFor(scope: String): OptionAvailability? = availability.firstOrNull { it.scope.equals(scope, ignoreCase = true) }
}

/**
 * Availability of an option within a [scope]: a trim/variant label such as "V1", "Selection",
 * or a published free-text scope such as "All Trims" or "GT, GT EXCLUSIVE".
 */
@Serializable
data class OptionAvailability(
    val scope: String,
    val state: FitmentState,
    val price: PriceFact? = null,
    val note: String? = null,
)

@Serializable
enum class FitmentState {
    STANDARD,
    OPTIONAL,
    NOT_AVAILABLE,
    UNKNOWN,
}

@Serializable
data class TechnicalSpecifications(
    val dimensions: Dimensions,
    val powertrains: List<PowertrainSpec>,
    val additional: Map<String, String> = emptyMap(),
)

@Serializable
data class Dimensions(
    val lengthMm: NumericRange?,
    val widthMm: NumericRange?,
    val heightMm: NumericRange?,
    val wheelbaseMm: NumericRange?,
    val bootCapacityLitres: NumericRange?,
    val maxBootCapacityLitres: NumericRange?,
    val frontTrunkCapacityLitres: NumericRange?,
    val brakedTowingKg: NumericRange?,
    val additional: Map<String, String> = emptyMap(),
)

@Serializable
data class PowertrainSpec(
    val name: String,
    val type: PowertrainType,
    val modelCode: String?,
    val drivetrain: String?,
    val transmission: String?,
    val powerKw: Double?,
    val powerHp: Double?,
    val torqueNm: Double?,
    val topSpeedKmh: Double?,
    val acceleration0To100S: NumericRange?,
    val wltpRangeKm: NumericRange?,
    val electricConsumptionKwhPer100Km: NumericRange?,
    val fuelConsumptionLPer100Km: NumericRange?,
    val co2GPerKm: NumericRange?,
    val batteryCapacityKwh: Double?,
    val batteryGrossKwh: Double?,
    val batteryType: String?,
    val displacementCc: Int?,
    val dcMaxChargingKw: Double?,
    val grossVehicleWeightKg: Double?,
    val brakedTowingKg: Double?,
    val unbrakedTowingKg: Double?,
    val trimsAvailable: List<String>,
    val charging: Map<String, String>,
    val additional: Map<String, String> = emptyMap(),
)
