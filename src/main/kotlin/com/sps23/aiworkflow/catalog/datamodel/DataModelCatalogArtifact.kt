package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.Serializable

const val DATA_MODEL_CATALOG_SCHEMA_VERSION = 1

@Serializable
data class DataModelCatalogArtifact(
    val schemaVersion: Int = DATA_MODEL_CATALOG_SCHEMA_VERSION,
    val vehicles: List<CarDataModelArtifact>,
) {
    companion object {
        fun from(catalog: DataModelCatalog) =
            DataModelCatalogArtifact(
                vehicles =
                    catalog.vehicles.map { car ->
                        CarDataModelArtifact(
                            id = car.id,
                            sourcePath = car.sourcePath,
                            sourcePdfPath =
                                requireNotNull(car.sourcePdfPath) {
                                    "${car.sourcePath}: source PDF was not linked"
                                },
                            title = car.title,
                            make = car.make,
                            model = car.model,
                            effectiveDate = car.effectiveDate,
                            effectivePeriod = car.effectivePeriod?.toString(),
                            trims = car.trims,
                            options = car.options,
                            technicalSpecifications = car.technicalSpecifications,
                            additional = car.additional,
                        )
                    },
            )
    }
}

@Serializable
data class CarDataModelArtifact(
    val id: String,
    /** Resource-relative path to the source Data Model JSON. */
    val sourcePath: String,
    /** Resource-relative path to the matched PDF. */
    val sourcePdfPath: String,
    val title: String,
    val make: String,
    val model: String,
    val effectiveDate: String?,
    val effectivePeriod: String?,
    val trims: List<Trim>,
    val options: List<VehicleOption>,
    val technicalSpecifications: TechnicalSpecifications,
    val additional: Map<String, String> = emptyMap(),
)
