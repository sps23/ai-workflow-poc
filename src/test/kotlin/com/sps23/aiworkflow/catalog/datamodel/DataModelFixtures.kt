package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractedVehicle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.YearMonth
import kotlin.io.path.readText

internal object DataModelFixtures {
    // TODO Add tests for all the other fixtures json specs
    const val CUPRA_LEON = "cupra/20260727_CUPRA_Ireland_Product_Guide_Leon_PA.json"
    const val CUPRA_RAVAL = "cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.json"
    const val DACIA_BIGSTER = "dacia/dacia bigster.json"
    const val DACIA_JOGGER = "dacia/dacia jogger.json"
    const val PEUGEOT_408 = "peugeot/408-spec-sheet.json"
    const val PEUGEOT_5008 = "peugeot/5008-spec-sheet.json"
    const val SKODA_KODIAQ = "skoda/Kodiaq_MY27_PG_January_CW01_2027.json"
    const val SKODA_PEAQ = "skoda/Peaq_MY27_PG_August_CW48_2026.json"
    const val TOYOTA_RAV4 = "toyota/Toyota RAV4 - PHEV Brochure.json"
    const val TOYOTA_YARIS = "toyota/Toyota Yaris - Hybrid Brochure.json"
    const val VW_ID7 = "vw/ID7_MY27.json"
    const val VW_TIGUAN = "vw/Tiguan_MY27.json"

    val ALL =
        listOf(
            CUPRA_LEON,
            CUPRA_RAVAL,
            DACIA_BIGSTER,
            DACIA_JOGGER,
            PEUGEOT_408,
            PEUGEOT_5008,
            SKODA_KODIAQ,
            SKODA_PEAQ,
            TOYOTA_RAV4,
            TOYOTA_YARIS,
            VW_ID7,
            VW_TIGUAN,
        )

    const val EXPECTED_RESOURCE_ROOT = "/data-model-fixtures"

    fun euros(amount: Long): Money = Money(amount * 100)

    val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    fun pricelistRoot(): Path = Path.of(checkNotNull(javaClass.getResource(DataModelCatalogLoader.DEFAULT_RESOURCE_ROOT)).toURI())

    fun load(relativePath: String): CarDataModel = CatalogSourceParser.parse(pricelistRoot().resolve(relativePath).readText(), relativePath)

    fun fixturePaths(): List<String> {
        val root = Path.of(checkNotNull(javaClass.getResource(EXPECTED_RESOURCE_ROOT)).toURI())
        return DataModelCatalogLoader.findDataModelFiles(root).map { DataModelCatalogLoader.relativePath(root, it) }
    }

    fun expected(relativePath: String): CarDataModel = frozen(relativePath).toModel()

    fun frozen(relativePath: String): FrozenDataModel =
        json.decodeFromString(FrozenDataModel.serializer(), resourceText("$EXPECTED_RESOURCE_ROOT/$relativePath"))

    fun artifact(): DataModelCatalogArtifact =
        DataModelCatalogArtifact.from(
            DataModelCatalog(
                fixturePaths().map { path ->
                    expected(path).copy(sourcePdfPath = path.substringBeforeLast('.') + ".pdf")
                },
            ),
        )

    private fun resourceText(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test fixture: $path" }.bufferedReader().use { it.readText() }
}

@Serializable
internal data class FrozenDataModel(
    val id: String,
    val sourcePath: String,
    val vehicle: ExtractedVehicle,
) {
    fun toModel(): CarDataModel =
        CarDataModel(
            id = id,
            sourcePath = sourcePath,
            title = vehicle.title,
            make = vehicle.make,
            model = vehicle.model,
            effectiveDate = vehicle.effectiveDate,
            effectivePeriod = vehicle.effectivePeriod?.let(YearMonth::parse),
            trims = vehicle.trims,
            options = vehicle.options,
            technicalSpecifications = vehicle.technicalSpecifications,
            additional = vehicle.additional,
        )

    companion object {
        fun from(car: CarDataModel): FrozenDataModel =
            FrozenDataModel(
                car.id,
                car.sourcePath,
                ExtractedVehicle(
                    title = car.title,
                    make = car.make,
                    model = car.model,
                    effectiveDate = car.effectiveDate,
                    effectivePeriod = car.effectivePeriod?.toString(),
                    trims = car.trims,
                    options = car.options,
                    technicalSpecifications = car.technicalSpecifications,
                    additional = car.additional,
                ),
            )
    }
}
