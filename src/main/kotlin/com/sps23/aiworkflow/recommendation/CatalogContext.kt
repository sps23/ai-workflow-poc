package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.FactReviewStatus
import com.sps23.aiworkflow.catalog.FitmentStatus
import com.sps23.aiworkflow.catalog.MeasurementApprovalStatus
import com.sps23.aiworkflow.catalog.Powertrain
import com.sps23.aiworkflow.catalog.PriceBasis
import com.sps23.aiworkflow.catalog.SourceEvidence
import com.sps23.aiworkflow.catalog.VehicleCatalog
import com.sps23.aiworkflow.catalog.VehicleCatalogCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Compact, model-facing view of the catalog. Absent (null) fields mean "unknown";
 * empty lists are omitted from the JSON. Source references are `documentId:page`.
 */
@Serializable
data class CompactCatalog(
    val configurations: List<CompactConfiguration>,
    val features: List<CompactFeature>,
)

@Serializable
data class CompactConfiguration(
    val id: String,
    val make: String,
    val model: String,
    val generation: String? = null,
    val market: String,
    val modelYear: Int? = null,
    val trim: String? = null,
    val powertrain: Powertrain,
    val engine: String? = null,
    val transmission: String? = null,
    val drivetrain: String? = null,
    val bodyStyle: String? = null,
    val seats: Int? = null,
    val manufacturerCode: String? = null,
    val sources: List<String> = emptyList(),
    val prices: List<CompactPrice> = emptyList(),
    val measurements: List<CompactMeasurement> = emptyList(),
    val equipment: List<CompactEquipment> = emptyList(),
)

@Serializable
data class CompactPrice(
    val featureId: String? = null,
    val amountMinorUnits: Long,
    val currency: String,
    val basis: PriceBasis,
    val effectiveFrom: String? = null,
    val effectiveTo: String? = null,
    val conditions: String? = null,
    val sources: List<String> = emptyList(),
)

@Serializable
data class CompactMeasurement(
    val name: String,
    val value: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String,
    val condition: String? = null,
    val approval: MeasurementApprovalStatus,
    val sources: List<String> = emptyList(),
)

@Serializable
data class CompactEquipment(
    val featureId: String,
    val status: FitmentStatus,
    val conditions: String? = null,
    val sources: List<String> = emptyList(),
)

@Serializable
data class CompactFeature(
    val id: String,
    val name: String,
    val category: String? = null,
)

object CatalogContext {
    const val BUNDLED_CATALOG_RESOURCE = "/vehicle-catalog/catalog.json"

    private val json =
        Json {
            encodeDefaults = false
            prettyPrint = false
        }

    fun loadBundledCatalog(): VehicleCatalog {
        val resource =
            checkNotNull(CatalogContext::class.java.getResource(BUNDLED_CATALOG_RESOURCE)) {
                "Bundled catalog resource is missing: $BUNDLED_CATALOG_RESOURCE"
            }
        return VehicleCatalogCodec.decode(resource.readText())
    }

    /** Compact JSON for the bundled catalog. */
    fun bundledJson(): String = toJson(compact(loadBundledCatalog()))

    fun toJson(catalog: CompactCatalog): String = json.encodeToString(catalog)

    /** Keeps every reviewed configuration and joins only its reviewed facts. */
    fun compact(catalog: VehicleCatalog): CompactCatalog {
        val modelsById = catalog.models.associateBy { it.id }
        val reviewed = catalog.configurations.filter { it.reviewStatus == FactReviewStatus.REVIEWED }
        val reviewedIds = reviewed.map { it.id }.toSet()

        val prices = HashMap<String, MutableList<CompactPrice>>()
        catalog.prices
            .filter { it.reviewStatus == FactReviewStatus.REVIEWED }
            .sortedBy { it.id }
            .forEach { price ->
                val compact =
                    CompactPrice(
                        featureId = price.featureId,
                        amountMinorUnits = price.amountMinorUnits,
                        currency = price.currency,
                        basis = price.basis,
                        effectiveFrom = price.effectiveFrom,
                        effectiveTo = price.effectiveTo,
                        conditions = price.conditions,
                        sources = price.evidence.references(),
                    )
                price.configurationIds
                    .filter { it in reviewedIds }
                    .forEach { prices.getOrPut(it) { mutableListOf() }.add(compact) }
            }

        val measurements = HashMap<String, MutableList<CompactMeasurement>>()
        catalog.measurements
            .filter { it.reviewStatus == FactReviewStatus.REVIEWED }
            .sortedBy { it.id }
            .forEach { measurement ->
                val compact =
                    CompactMeasurement(
                        name = measurement.name,
                        value = measurement.value,
                        min = measurement.minValue,
                        max = measurement.maxValue,
                        unit = measurement.unit,
                        condition = measurement.testCycleOrCondition,
                        approval = measurement.approvalStatus,
                        sources = measurement.evidence.references(),
                    )
                measurement.configurationIds
                    .filter { it in reviewedIds }
                    .forEach { measurements.getOrPut(it) { mutableListOf() }.add(compact) }
            }

        val equipment = HashMap<String, MutableList<CompactEquipment>>()
        catalog.fitments
            .filter { it.reviewStatus == FactReviewStatus.REVIEWED }
            .sortedBy { it.id }
            .forEach { fitment ->
                val compact =
                    CompactEquipment(
                        featureId = fitment.featureId,
                        status = fitment.status,
                        conditions = fitment.conditions,
                        sources = fitment.evidence.references(),
                    )
                fitment.configurationIds
                    .filter { it in reviewedIds }
                    .forEach { equipment.getOrPut(it) { mutableListOf() }.add(compact) }
            }

        val configurations =
            reviewed.sortedBy { it.id }.map { configuration ->
                val model = checkNotNull(modelsById[configuration.modelId]) { "Unknown model: ${configuration.modelId}" }
                CompactConfiguration(
                    id = configuration.id,
                    make = model.make,
                    model = model.name,
                    generation = model.generation,
                    market = configuration.market,
                    modelYear = configuration.modelYear,
                    trim = configuration.trim,
                    powertrain = configuration.powertrain,
                    engine = configuration.engine,
                    transmission = configuration.transmission,
                    drivetrain = configuration.drivetrain,
                    bodyStyle = configuration.bodyStyle,
                    seats = configuration.seatingCapacity,
                    manufacturerCode = configuration.manufacturerCode,
                    sources = configuration.evidence.references(),
                    prices = prices[configuration.id].orEmpty(),
                    measurements = measurements[configuration.id].orEmpty(),
                    equipment = equipment[configuration.id].orEmpty(),
                )
            }

        val referencedFeatureIds =
            configurations
                .flatMap { configuration ->
                    configuration.prices.mapNotNull { it.featureId } + configuration.equipment.map { it.featureId }
                }.toSet()
        val features =
            catalog.features
                .filter { it.id in referencedFeatureIds }
                .sortedBy { it.id }
                .map { CompactFeature(it.id, it.name, it.category) }

        return CompactCatalog(configurations, features)
    }

    private fun List<SourceEvidence>.references(): List<String> = map { "${it.documentId}:${it.pageNumber}" }.distinct().sorted()
}
