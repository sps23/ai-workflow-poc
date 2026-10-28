package com.sps23.aiworkflow.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

const val VEHICLE_CATALOG_SCHEMA_VERSION = 1

@Serializable
data class VehicleCatalog(
    val schemaVersion: Int = VEHICLE_CATALOG_SCHEMA_VERSION,
    val sources: List<CatalogSource>,
    val models: List<VehicleModel>,
    val configurations: List<VehicleConfiguration>,
    val features: List<VehicleFeature>,
    val prices: List<VehiclePrice>,
    val measurements: List<VehicleMeasurement>,
    val fitments: List<EquipmentFitment>,
)

@Serializable
data class CatalogSource(
    val documentId: String,
    val relativePath: String,
    val sha256: String,
    val market: String,
    val pageCount: Int,
    val effectivePeriod: String? = null,
)

@Serializable
data class VehicleModel(
    val id: String,
    val make: String,
    val name: String,
    val generation: String? = null,
)

@Serializable
data class VehicleConfiguration(
    val id: String,
    val modelId: String,
    val market: String,
    val modelYear: Int? = null,
    val trim: String? = null,
    val powertrain: Powertrain,
    val engine: String? = null,
    val transmission: String? = null,
    val drivetrain: String? = null,
    val bodyStyle: String? = null,
    val seatingCapacity: Int? = null,
    val manufacturerCode: String? = null,
    val reviewStatus: FactReviewStatus,
    val evidence: List<SourceEvidence>,
)

@Serializable
enum class Powertrain {
    PETROL,
    DIESEL,
    MILD_HYBRID,
    HYBRID,
    PLUG_IN_HYBRID,
    ELECTRIC,
    OTHER,
    UNKNOWN,
}

@Serializable
data class SourceEvidence(
    val documentId: String,
    val sha256: String,
    val pageNumber: Int,
    val rawText: String,
)

@Serializable
enum class FactReviewStatus {
    REVIEWED,
    NEEDS_REVIEW,
}

@Serializable
data class VehicleFeature(
    val id: String,
    val name: String,
    val category: String? = null,
)

@Serializable
enum class FitmentStatus {
    STANDARD,
    OPTIONAL,
    NOT_AVAILABLE,
    UNKNOWN,
}

@Serializable
data class EquipmentFitment(
    val id: String,
    val featureId: String,
    val configurationIds: List<String>,
    val status: FitmentStatus,
    val conditions: String? = null,
    val reviewStatus: FactReviewStatus,
    val evidence: List<SourceEvidence>,
)

@Serializable
enum class PriceBasis {
    MANUFACTURER_RRP,
    RRP_BEFORE_INCENTIVES,
    RRP_AFTER_INCENTIVES,
    DELIVERY_SERVICE_CHARGE,
    ON_THE_ROAD_TOTAL,
    OPTION_PRICE,
    OTHER,
}

@Serializable
data class VehiclePrice(
    val id: String,
    val configurationIds: List<String>,
    val featureId: String? = null,
    val amountMinorUnits: Long,
    val currency: String,
    val basis: PriceBasis,
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    val conditions: String? = null,
    val reviewStatus: FactReviewStatus,
    val evidence: List<SourceEvidence>,
)

@Serializable
data class VehicleMeasurement(
    val id: String,
    val name: String,
    val configurationIds: List<String>,
    val value: Double? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val unit: String,
    val testCycleOrCondition: String? = null,
    val approvalStatus: MeasurementApprovalStatus,
    val reviewStatus: FactReviewStatus,
    val evidence: List<SourceEvidence>,
)

@Serializable
enum class MeasurementApprovalStatus {
    CONFIRMED,
    PENDING_HOMOLOGATION,
    UNSPECIFIED,
}

object VehicleCatalogCodec {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(catalog: VehicleCatalog): String {
        VehicleCatalogValidator.validate(catalog)
        return json.encodeToString(catalog.stableOrder()) + "\n"
    }

    fun decode(value: String): VehicleCatalog = json.decodeFromString<VehicleCatalog>(value).also(VehicleCatalogValidator::validate)

    private fun VehicleCatalog.stableOrder() =
        copy(
            sources = sources.sortedBy { it.documentId },
            models = models.sortedBy { it.id },
            configurations =
                configurations
                    .map { it.copy(evidence = it.evidence.sortedEvidence()) }
                    .sortedBy { it.id },
            features = features.sortedBy { it.id },
            prices =
                prices
                    .map {
                        it.copy(
                            configurationIds = it.configurationIds.sorted(),
                            evidence = it.evidence.sortedEvidence(),
                        )
                    }.sortedBy { it.id },
            measurements =
                measurements
                    .map {
                        it.copy(
                            configurationIds = it.configurationIds.sorted(),
                            evidence = it.evidence.sortedEvidence(),
                        )
                    }.sortedBy { it.id },
            fitments =
                fitments
                    .map {
                        it.copy(
                            configurationIds = it.configurationIds.sorted(),
                            evidence = it.evidence.sortedEvidence(),
                        )
                    }.sortedBy { it.id },
        )

    private fun List<SourceEvidence>.sortedEvidence(): List<SourceEvidence> =
        sortedWith(compareBy(SourceEvidence::documentId, SourceEvidence::pageNumber, SourceEvidence::rawText))
}

object VehicleCatalogValidator {
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val periodPattern = Regex("\\d{4}(?:-\\d{2})?(?:-\\d{2})?")

    fun validate(catalog: VehicleCatalog) {
        require(catalog.schemaVersion == VEHICLE_CATALOG_SCHEMA_VERSION) {
            "Unsupported vehicle catalog schema version: ${catalog.schemaVersion}"
        }
        requireUnique(catalog.sources.map { it.documentId }, "source document ID")
        requireUnique(catalog.models.map { it.id }, "vehicle model ID")
        requireUnique(catalog.configurations.map { it.id }, "vehicle configuration ID")
        requireUnique(catalog.features.map { it.id }, "feature ID")
        requireUnique(catalog.prices.map { it.id }, "price fact ID")
        requireUnique(catalog.measurements.map { it.id }, "measurement fact ID")
        requireUnique(catalog.fitments.map { it.id }, "fitment fact ID")

        val sourceById = catalog.sources.associateBy { it.documentId }
        val modelIds = catalog.models.map { it.id }.toSet()
        val configurationsById = catalog.configurations.associateBy { it.id }
        val featureIds = catalog.features.map { it.id }.toSet()

        catalog.sources.forEach(::validateSource)
        catalog.models.forEach(::validateModel)
        catalog.configurations.forEach { configuration ->
            validateConfiguration(configuration, modelIds, sourceById)
        }
        validateUniqueConfigurationIdentity(catalog.configurations)
        catalog.features.forEach(::validateFeature)
        catalog.prices.forEach { price ->
            validatePrice(price, featureIds, configurationsById.keys, sourceById)
        }
        catalog.measurements.forEach { measurement ->
            validateMeasurement(measurement, configurationsById.keys, sourceById)
        }
        catalog.fitments.forEach { fitment ->
            validateFitment(fitment, featureIds, configurationsById.keys, sourceById)
        }
    }

    private fun validateSource(source: CatalogSource) {
        require(source.documentId.isNotBlank()) { "Source document ID must not be blank" }
        require(
            source.relativePath.isNotBlank() &&
                !source.relativePath.startsWith("/") &&
                !source.relativePath.contains('\\') &&
                source.relativePath.split('/').all { it.isNotBlank() && it != "." && it != ".." },
        ) { "Source path must be a normalized relative path: ${source.relativePath}" }
        require(source.sha256.matches(sha256Pattern)) { "Source SHA-256 must be 64 lowercase hexadecimal characters" }
        require(source.market.matches(Regex("[A-Z]{2}"))) { "Source market must be a two-letter uppercase code" }
        require(source.pageCount > 0) { "Source page count must be positive" }
        source.effectivePeriod?.let(::validatePeriod)
    }

    private fun validateModel(model: VehicleModel) {
        requireId(model.id, "vehicle model")
        require(model.make.isNotBlank()) { "Vehicle make must not be blank" }
        require(model.name.isNotBlank()) { "Vehicle model name must not be blank" }
        require(model.generation == null || model.generation.isNotBlank()) { "Generation must not be blank when specified" }
    }

    private fun validateConfiguration(
        configuration: VehicleConfiguration,
        modelIds: Set<String>,
        sourceById: Map<String, CatalogSource>,
    ) {
        requireId(configuration.id, "vehicle configuration")
        require(configuration.modelId in modelIds) { "Configuration references unknown model ID: ${configuration.modelId}" }
        require(configuration.market.matches(Regex("[A-Z]{2}"))) { "Configuration market must be a two-letter uppercase code" }
        require(configuration.modelYear == null || configuration.modelYear in 1886..2200) {
            "Configuration model year is outside the supported range"
        }
        require(configuration.trim == null || configuration.trim.isNotBlank()) { "Trim must not be blank when specified" }
        require(configuration.engine == null || configuration.engine.isNotBlank()) { "Engine must not be blank when specified" }
        require(
            configuration.transmission == null || configuration.transmission.isNotBlank(),
        ) { "Transmission must not be blank when specified" }
        require(configuration.drivetrain == null || configuration.drivetrain.isNotBlank()) {
            "Drivetrain must not be blank when specified"
        }
        require(configuration.seatingCapacity == null || configuration.seatingCapacity > 0) {
            "Seating capacity must be positive when specified"
        }
        requireEvidence(configuration.evidence, sourceById)
        if (configuration.powertrain == Powertrain.UNKNOWN) {
            require(configuration.reviewStatus == FactReviewStatus.NEEDS_REVIEW) {
                "Unknown powertrain must remain under review"
            }
        }
    }

    private fun validateUniqueConfigurationIdentity(configurations: List<VehicleConfiguration>) {
        val duplicate =
            configurations
                .groupBy { configuration ->
                    listOf(
                        configuration.modelId,
                        configuration.market,
                        configuration.modelYear?.toString().orEmpty(),
                        configuration.trim.normalizedIdentity(),
                        configuration.powertrain.name,
                        configuration.engine.normalizedIdentity(),
                        configuration.transmission.normalizedIdentity(),
                        configuration.drivetrain.normalizedIdentity(),
                        configuration.bodyStyle.normalizedIdentity(),
                        configuration.seatingCapacity?.toString().orEmpty(),
                        configuration.manufacturerCode.normalizedIdentity(),
                    ).joinToString("|")
                }.entries
                .firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Duplicate vehicle configuration identity: ${duplicate?.value?.map { it.id }}"
        }
    }

    private fun validateFeature(feature: VehicleFeature) {
        requireId(feature.id, "feature")
        require(feature.name.isNotBlank()) { "Feature name must not be blank" }
        require(feature.category == null || feature.category.isNotBlank()) { "Feature category must not be blank" }
    }

    private fun validatePrice(
        price: VehiclePrice,
        featureIds: Set<String>,
        configurationIds: Set<String>,
        sourceById: Map<String, CatalogSource>,
    ) {
        requireId(price.id, "price fact")
        validateConfigurationReferences(price.configurationIds, configurationIds, "price")
        require(price.featureId == null || price.featureId in featureIds) {
            "Price references unknown feature ID: ${price.featureId}"
        }
        require(price.amountMinorUnits >= 0) { "Price cannot be negative: ${price.id}" }
        require(price.currency.matches(Regex("[A-Z]{3}"))) { "Price currency must be an uppercase ISO-style code" }
        require(price.effectiveFrom == null || validatePeriod(price.effectiveFrom)) {
            "Price effective-from must be an ISO year, month, or date"
        }
        require(price.effectiveTo == null || validatePeriod(price.effectiveTo)) {
            "Price effective-to must be an ISO year, month, or date"
        }
        if (price.effectiveFrom != null && price.effectiveTo != null) {
            require(toYearMonth(price.effectiveFrom) <= toYearMonth(price.effectiveTo)) {
                "Price effective period is reversed: ${price.id}"
            }
        }
        require(price.conditions == null || price.conditions.isNotBlank()) {
            "Price conditions must not be blank when specified"
        }
        requireEvidence(price.evidence, sourceById)
    }

    private fun validateMeasurement(
        measurement: VehicleMeasurement,
        configurationIds: Set<String>,
        sourceById: Map<String, CatalogSource>,
    ) {
        requireId(measurement.id, "measurement fact")
        require(measurement.name.isNotBlank()) { "Measurement name must not be blank" }
        validateConfigurationReferences(measurement.configurationIds, configurationIds, "measurement")
        require(measurement.unit.isNotBlank()) { "Measurement unit must not be blank" }
        val value = measurement.value
        val minimum = measurement.minValue
        val maximum = measurement.maxValue
        val hasCompleteRange = minimum != null && maximum != null
        require((value != null && minimum == null && maximum == null) || (value == null && hasCompleteRange)) {
            "Measurement must contain either one value or a complete range: ${measurement.id}"
        }
        if (value != null) {
            require(value.isFinite()) { "Measurement value must be finite: ${measurement.id}" }
        } else {
            val rangeMinimum = requireNotNull(minimum)
            val rangeMaximum = requireNotNull(maximum)
            require(rangeMinimum.isFinite() && rangeMaximum.isFinite()) {
                "Measurement range endpoints must be finite: ${measurement.id}"
            }
            require(rangeMinimum <= rangeMaximum) {
                "Measurement range is reversed: ${measurement.id}"
            }
        }
        require(measurement.testCycleOrCondition == null || measurement.testCycleOrCondition.isNotBlank()) {
            "Measurement conditions must not be blank when specified"
        }
        requireEvidence(measurement.evidence, sourceById)
        if (measurement.approvalStatus == MeasurementApprovalStatus.PENDING_HOMOLOGATION) {
            require(measurement.reviewStatus == FactReviewStatus.NEEDS_REVIEW) {
                "Measurements pending homologation must remain under review"
            }
        }
    }

    private fun validateFitment(
        fitment: EquipmentFitment,
        featureIds: Set<String>,
        configurationIds: Set<String>,
        sourceById: Map<String, CatalogSource>,
    ) {
        requireId(fitment.id, "fitment fact")
        require(fitment.featureId in featureIds) { "Fitment references unknown feature ID: ${fitment.featureId}" }
        validateConfigurationReferences(fitment.configurationIds, configurationIds, "fitment")
        require(fitment.conditions == null || fitment.conditions.isNotBlank()) {
            "Fitment conditions must not be blank when specified"
        }
        requireEvidence(fitment.evidence, sourceById)
        if (fitment.status == FitmentStatus.UNKNOWN) {
            require(fitment.reviewStatus == FactReviewStatus.NEEDS_REVIEW) {
                "Unknown feature fitment must remain under review"
            }
        }
    }

    private fun validateConfigurationReferences(
        references: List<String>,
        knownIds: Set<String>,
        factName: String,
    ) {
        require(references.isNotEmpty()) { "$factName must reference at least one vehicle configuration" }
        require(references.distinct().size == references.size) { "$factName contains duplicate configuration references" }
        references.forEach { id ->
            require(id in knownIds) { "$factName references unknown configuration ID: $id" }
        }
    }

    private fun requireEvidence(
        evidence: List<SourceEvidence>,
        sourceById: Map<String, CatalogSource>,
    ) {
        require(evidence.isNotEmpty()) { "Catalog facts must include source evidence" }
        evidence.forEach { citation ->
            val source = sourceById[citation.documentId]
            require(source != null) { "Evidence references unknown source document: ${citation.documentId}" }
            require(citation.sha256 == source.sha256) { "Evidence hash does not match source: ${citation.documentId}" }
            require(citation.pageNumber > 0) { "Evidence page number must be positive" }
            require(citation.pageNumber <= source.pageCount) {
                "Evidence page ${citation.pageNumber} exceeds page count for source ${citation.documentId}"
            }
            require(citation.rawText.isNotBlank()) { "Evidence must preserve non-blank source wording" }
        }
    }

    private fun requireUnique(
        values: List<String>,
        name: String,
    ) {
        require(values.distinct().size == values.size) { "Duplicate $name" }
    }

    private fun requireId(
        value: String,
        kind: String,
    ) {
        require(value.matches(idPattern)) { "$kind ID must be a lowercase kebab-case identifier: $value" }
    }

    private fun validatePeriod(value: String): Boolean {
        require(value.matches(periodPattern)) { "Period must use ISO year, month, or date format: $value" }
        try {
            when (value.length) {
                4 -> value.toInt()
                7 -> YearMonth.parse(value)
                10 -> LocalDate.parse(value)
            }
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("Invalid ISO period: $value", exception)
        }
        return true
    }

    private fun toYearMonth(value: String): YearMonth =
        when (value.length) {
            4 -> YearMonth.of(value.toInt(), 1)
            7 -> YearMonth.parse(value)
            10 -> YearMonth.from(LocalDate.parse(value))
            else -> error("Invalid period: $value")
        }

    private fun String?.normalizedIdentity(): String = this?.trim()?.lowercase().orEmpty()
}
