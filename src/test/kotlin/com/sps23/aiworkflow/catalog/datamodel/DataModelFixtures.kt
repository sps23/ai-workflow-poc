package com.sps23.aiworkflow.catalog.datamodel

import java.nio.file.Path

internal object DataModelFixtures {
    const val CUPRA_RAVAL = "cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.json"
    const val PEUGEOT_5008 = "peugeot/5008-spec-sheet.json"
    const val SKODA_PEAQ = "skoda/Peaq_MY27_PG_August_CW48_2026.json"
    const val TOYOTA_COROLLA_CROSS = "toyota/Toyota Corolla Cross - Brochure.json"

    val ALL = listOf(CUPRA_RAVAL, PEUGEOT_5008, SKODA_PEAQ, TOYOTA_COROLLA_CROSS)

    fun pricelistRoot(): Path = Path.of(checkNotNull(javaClass.getResource(DataModelCatalogLoader.DEFAULT_RESOURCE_ROOT)).toURI())

    fun load(relativePath: String): CarDataModel = CarDataModelParser.parse(pricelistRoot().resolve(relativePath))

    fun euros(amount: Long): Money = Money(amount * 100)
}
