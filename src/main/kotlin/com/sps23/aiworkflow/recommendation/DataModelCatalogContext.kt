package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.CarDataModelArtifact
import com.sps23.aiworkflow.catalog.datamodel.DATA_MODEL_CATALOG_SCHEMA_VERSION
import com.sps23.aiworkflow.catalog.datamodel.DataModelCatalogArtifact
import com.sps23.aiworkflow.catalog.datamodel.EquipmentGroup
import com.sps23.aiworkflow.catalog.datamodel.TechnicalSpecifications
import com.sps23.aiworkflow.catalog.datamodel.TrimVariant
import com.sps23.aiworkflow.catalog.datamodel.VehicleOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

@Serializable
data class DataModelPromptCatalog(
    val schemaVersion: Int,
    val vehicles: List<DataModelPromptVehicle>,
)

@Serializable
data class DataModelPromptVehicle(
    val id: String,
    val make: String,
    val model: String,
    val title: String,
    val effectiveDate: String?,
    val sourcePdfPath: String,
    val trims: List<DataModelPromptTrim>,
    val options: List<VehicleOption>,
    val technicalSpecifications: TechnicalSpecifications,
    val additional: Map<String, String>,
)

@Serializable
data class DataModelPromptTrim(
    val name: String,
    val configurations: List<DataModelPromptConfiguration>,
    val standardEquipment: List<EquipmentGroup>,
    val additional: Map<String, String>,
)

@Serializable
data class DataModelPromptConfiguration(
    val configurationId: String,
    val variant: TrimVariant,
)

/**
 * UI-only prompt source. It preserves the Data Model catalog's published values and
 * derives stable IDs for each trim/powertrain row; source PDFs are document-level only.
 */
class DataModelCatalogContext private constructor(
    val promptJson: String,
    private val sourcePdfByConfiguration: Map<String, String>,
    private val detailsByConfiguration: Map<String, CatalogConfigurationDetails>,
) {
    fun sourcePdfPath(configurationId: String): String? = sourcePdfByConfiguration[configurationId]

    fun details(car: SelectedCar): RecommendationDetails =
        detailsByConfiguration[car.configurationId]?.describe(car.options)
            ?: RecommendationDetails(
                "Engine: unavailable (configuration not found in catalog)",
                listOf("Price: unavailable (configuration not found in catalog)"),
            )

    companion object {
        const val BUNDLED_CATALOG_RESOURCE = "/vehicle-catalog/data-model-catalog.json"

        private val json =
            Json {
                encodeDefaults = false
                explicitNulls = false
            }

        fun loadBundled(): DataModelCatalogContext {
            val resource =
                checkNotNull(DataModelCatalogContext::class.java.getResource(BUNDLED_CATALOG_RESOURCE)) {
                    "Bundled data-model catalog is missing: $BUNDLED_CATALOG_RESOURCE"
                }
            val artifact =
                try {
                    json.decodeFromString<DataModelCatalogArtifact>(resource.readText())
                } catch (exception: SerializationException) {
                    throw IllegalStateException("Bundled data-model catalog is invalid: $BUNDLED_CATALOG_RESOURCE", exception)
                }
            require(artifact.schemaVersion == DATA_MODEL_CATALOG_SCHEMA_VERSION) {
                "Unsupported data-model catalog schema version ${artifact.schemaVersion}."
            }
            require(artifact.vehicles.isNotEmpty()) { "Bundled data-model catalog contains no vehicles." }

            val sourcePdfByConfiguration = linkedMapOf<String, String>()
            val detailsByConfiguration = linkedMapOf<String, CatalogConfigurationDetails>()
            val vehicles =
                artifact.vehicles.map { car ->
                    validatePdfPath(car.sourcePdfPath, car.sourcePath)
                    DataModelPromptVehicle(
                        id = car.id,
                        make = car.make,
                        model = car.model,
                        title = car.title,
                        effectiveDate = car.effectiveDate,
                        sourcePdfPath = car.sourcePdfPath,
                        trims =
                            car.trims.map { trim ->
                                DataModelPromptTrim(
                                    name = trim.name,
                                    configurations =
                                        trim.variants.map { variant ->
                                            val configurationId = configurationId(car, trim.name, variant)
                                            require(sourcePdfByConfiguration.put(configurationId, car.sourcePdfPath) == null) {
                                                "Duplicate derived configuration id: $configurationId"
                                            }
                                            detailsByConfiguration[configurationId] = CatalogConfigurationDetails(car, trim.name, variant)
                                            DataModelPromptConfiguration(configurationId, variant)
                                        },
                                    standardEquipment = trim.standardEquipment,
                                    additional = trim.additional,
                                )
                            },
                        options = car.options,
                        technicalSpecifications = car.technicalSpecifications,
                        additional = car.additional,
                    )
                }
            return DataModelCatalogContext(
                promptJson =
                    json.encodeToString(
                        DataModelPromptCatalog.serializer(),
                        DataModelPromptCatalog(DATA_MODEL_CATALOG_SCHEMA_VERSION, vehicles),
                    ),
                sourcePdfByConfiguration = sourcePdfByConfiguration,
                detailsByConfiguration = detailsByConfiguration,
            )
        }

        private fun configurationId(
            car: CarDataModelArtifact,
            trimName: String,
            variant: TrimVariant,
        ): String = listOf(car.id, slug(trimName), slug(variant.name)).filter(String::isNotEmpty).joinToString("-")

        private fun slug(value: String): String =
            Normalizer
                .normalize(value, Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("""[^a-z0-9]+"""), "-")
                .trim('-')

        private fun validatePdfPath(
            path: String,
            sourcePath: String,
        ) {
            require(
                path.endsWith(".pdf", ignoreCase = true) &&
                    path.split('/').all { segment ->
                        segment.isNotBlank() && segment != "." && segment != ".." && '\\' !in segment && ':' !in segment
                    },
            ) {
                "$sourcePath: invalid linked PDF resource path."
            }
        }
    }
}

/** Resolves PDF links only through the bundled Data Model catalog, never model-authored paths. */
class DataModelPdfStore(
    private val context: DataModelCatalogContext,
) {
    fun read(configurationId: String): Pair<String, ByteArray> {
        val path =
            context.sourcePdfPath(configurationId)
                ?: throw IOException("The configuration is not registered in the bundled data-model catalog.")
        val bytes =
            DataModelPdfStore::class.java.getResourceAsStream("/car-pricelists/$path")?.use { it.readBytes() }
                ?: throw IOException("The linked source PDF is missing from application resources.")
        if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
            throw IOException("The linked source is not a PDF. Restore the original PDF with Git LFS and rebuild the app.")
        }
        return path.substringAfterLast('/') to bytes
    }
}
