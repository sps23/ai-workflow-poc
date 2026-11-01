package com.sps23.aiworkflow.catalog.datamodel

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataModelCatalogLoaderTest {
    private val root = DataModelFixtures.pricelistRoot()

    @Test
    fun `every model source has exactly one frozen fixture and comparison case`() {
        val sources = DataModelCatalogLoader.findDataModelFiles(root).map { DataModelCatalogLoader.relativePath(root, it) }
        val fixtures = DataModelFixtures.fixturePaths()
        assertEquals(DataModelFixtures.ALL.sorted(), sources.sorted(), "Add a comparison case for every new model source")
        assertEquals(sources.sorted(), fixtures.sorted(), "Missing or orphaned frozen fixtures")
        assertEquals(DataModelFixtures.ALL.size, DataModelFixtures.ALL.distinct().size, "Duplicate comparison cases")
    }

    @Test
    fun `classpath and directory catalogs match every frozen model including nested facts`() {
        val expected = DataModelCatalog(DataModelFixtures.ALL.map(DataModelFixtures::expected))
        val catalog = DataModelCatalogLoader.loadFromClasspath()

        assertEquals(expected, catalog)
        assertEquals(expected, DataModelCatalogLoader.loadDirectory(root))
        assertEquals(
            expected.vehicles.size,
            catalog.vehicles
                .map { it.id }
                .distinct()
                .size,
        )
        expected.vehicles.forEach { car ->
            assertEquals(car, assertNotNull(catalog.byId(car.id), car.sourcePath))
        }
        expected.vehicles.groupBy { it.make }.forEach { (make, cars) ->
            assertEquals(cars, catalog.byMake(make.uppercase()), "$make lookup")
        }
        assertEquals(null, catalog.byId("missing-model"))
        assertTrue(catalog.byMake("missing-make").isEmpty())
    }

    @Test
    fun `duplicate vehicle ids are rejected`() {
        val dir = createTempDirectory("datamodel")
        try {
            val json = """{"model": "Brand Car", "trims": []}"""
            dir.resolve("a.json").writeText(json)
            dir.resolve("b.json").writeText(json)

            val failure = assertFailsWith<DataModelParseException> { DataModelCatalogLoader.loadDirectory(dir) }
            assertTrue("brand-car" in failure.message!!)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
