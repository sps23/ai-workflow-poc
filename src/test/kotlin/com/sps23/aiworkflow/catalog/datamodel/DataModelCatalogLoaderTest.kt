package com.sps23.aiworkflow.catalog.datamodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataModelCatalogLoaderTest {
    private val root = DataModelFixtures.pricelistRoot()

    @Test
    fun `every parsed json file in the pricelist subfolders is covered by a dedicated test`() {
        val discovered = DataModelCatalogLoader.findDataModelFiles(root).map { DataModelCatalogLoader.relativePath(root, it) }

        assertEquals(DataModelFixtures.ALL.sorted(), discovered.sorted())
    }

    @Test
    fun `classpath catalog loads every normalized car file`() {
        val catalog = DataModelCatalogLoader.loadFromClasspath()

        assertEquals(
            listOf("cupra-raval", "peugeot-5008", "skoda-peaq", "toyota-corolla-cross"),
            catalog.vehicles.map { it.id },
        )
        assertEquals(DataModelFixtures.CUPRA_RAVAL, catalog.byId("cupra-raval")!!.sourcePath)
        assertEquals(DataModelFixtures.SKODA_PEAQ, catalog.byId("skoda-peaq")!!.sourcePath)
        assertEquals(listOf("skoda-peaq"), catalog.byMake("ŠKODA").map { it.id })
    }

    @Test
    fun `every loaded vehicle has a complete core structure`() {
        DataModelCatalogLoader.loadDirectory(root).vehicles.forEach { car ->
            val label = car.sourcePath
            assertTrue(car.effectivePeriod != null, "$label effective period")
            assertTrue(car.trims.isNotEmpty(), "$label trims")
            assertTrue(car.trims.all { it.variants.isNotEmpty() }, "$label trim variants")
            assertTrue(car.trims.all { it.standardEquipment.isNotEmpty() }, "$label equipment")
            assertTrue(car.variants.all { it.powertrainType != PowertrainType.UNKNOWN }, "$label powertrain types")
            assertTrue(car.variants.all { it.prices.isNotEmpty() }, "$label prices")
            assertTrue(car.options.isNotEmpty(), "$label options")
            assertTrue(car.colours.isNotEmpty(), "$label colours")
            assertTrue(car.technicalSpecifications.powertrains.isNotEmpty(), "$label powertrains")
            assertTrue(car.technicalSpecifications.dimensions.lengthMm != null, "$label length")
            assertTrue(car.additional.isEmpty(), "$label unmapped top-level fields ${car.additional.keys}")
        }
    }

    @Test
    fun `duplicate vehicle ids are rejected`() {
        val dir = kotlin.io.path.createTempDirectory("datamodel")
        try {
            val json = """{"model": "Brand Car", "trims": []}"""
            dir.resolve("a.json").toFile().writeText(json)
            dir.resolve("b.json").toFile().writeText(json)

            val failure = assertFailsWith<DataModelParseException> { DataModelCatalogLoader.loadDirectory(dir) }
            assertTrue("brand-car" in failure.message!!)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
