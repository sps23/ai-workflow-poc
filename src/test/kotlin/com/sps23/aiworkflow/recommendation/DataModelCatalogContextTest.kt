package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.catalog.datamodel.DataModelFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataModelCatalogContextTest {
    companion object {
        private val json = Json { explicitNulls = false }
    }

    @Test
    fun `plus trims have distinct stable configuration ids without changing other trim ids`() {
        val artifact = DataModelFixtures.artifact()
        val context = DataModelCatalogContext.fromArtifact(artifact)
        val prompt = json.decodeFromString<DataModelPromptCatalog>(context.promptJson)
        val configurations = prompt.vehicles.flatMap { it.trims }.flatMap { it.configurations }
        assertEquals(artifact.vehicles.sumOf { car -> car.trims.sumOf { it.variants.size } }, configurations.size)
        assertEquals(configurations.size, configurations.map { it.configurationId }.distinct().size)
        val kodiaq = prompt.vehicles.single { it.id == "skoda-kodiaq" }
        val selection =
            kodiaq.trims
                .single { it.name == "Selection" }
                .configurations
                .first()
                .configurationId
        val plus =
            kodiaq.trims
                .single { it.name == "Selection +" }
                .configurations
                .first()
                .configurationId
        assertEquals("skoda-kodiaq-selection-1-5tsi-150hp-dsg-mhev", selection)
        assertEquals("skoda-kodiaq-selection-plus-1-5tsi-150hp-dsg-mhev", plus)
        assertEquals(kodiaq.sourcePdfPath, context.sourcePdfPath(selection))
        assertEquals(kodiaq.sourcePdfPath, context.sourcePdfPath(plus))
    }

    @Test
    fun `duplicate derived configuration ids remain explicit failures`() {
        val artifact = DataModelFixtures.artifact()
        val car = artifact.vehicles.first()
        val duplicate = car.copy(trims = listOf(car.trims.first(), car.trims.first()))
        assertFailsWith<IllegalArgumentException> {
            DataModelCatalogContext.fromArtifact(artifact.copy(vehicles = listOf(duplicate)))
        }
    }

    @Test
    fun `artifact loading rejects unsupported schemas empty catalogs and unsafe PDF paths`() {
        val artifact = DataModelFixtures.artifact()
        assertFailsWith<IllegalArgumentException> {
            DataModelCatalogContext.fromArtifact(artifact.copy(schemaVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            DataModelCatalogContext.fromArtifact(artifact.copy(vehicles = emptyList()))
        }
        listOf("../outside.pdf", "/absolute.pdf", "cars\\outside.pdf", "cars/document.json").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                DataModelCatalogContext.fromArtifact(
                    artifact.copy(vehicles = listOf(artifact.vehicles.first().copy(sourcePdfPath = path))),
                )
            }
        }
    }

    @Test
    fun `UI context uses normalized car data with deterministic variant ids and linked PDFs`() {
        val context = DataModelCatalogContext.fromArtifact(DataModelFixtures.artifact())
        val root = Json.parseToJsonElement(context.promptJson).jsonObject
        val vehicles = root.getValue("vehicles").jsonArray

        assertEquals(DataModelFixtures.fixturePaths().size, vehicles.size)
        val cupra =
            vehicles
                .single {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "cupra-raval"
                }.jsonObject
        assertEquals("cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.pdf", cupra.getValue("sourcePdfPath").jsonPrimitive.content)
        val urban =
            cupra
                .getValue("trims")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "Urban V1"
                }.jsonObject
        val firstVariant =
            urban
                .getValue("configurations")
                .jsonArray
                .first()
                .jsonObject
        val configurationId = firstVariant.getValue("configurationId").jsonPrimitive.content
        assertEquals("cupra-raval-urban-v1-raval-urban-37kwh-116hp", configurationId)
        assertTrue("PRICE" in firstVariant.toString())
        assertEquals(cupra.getValue("sourcePdfPath").jsonPrimitive.content, context.sourcePdfPath(configurationId))
        assertEquals(context.promptJson, DataModelCatalogContext.fromArtifact(DataModelFixtures.artifact()).promptJson)
        assertFalse("documentId:page" in context.promptJson)
    }

    @Test
    fun `source PDF lookup uses bundled registry and rejects unknown configuration`() {
        val context = DataModelCatalogContext.fromArtifact(DataModelFixtures.artifact())
        val store = DataModelPdfStore(context)
        val (filename, bytes) = store.read("cupra-raval-urban-v1-raval-urban-37kwh-116hp")

        assertEquals("20260820_CUPRA_Ireland_Product_Guide_Raval.pdf", filename)
        assertEquals("%PDF-", bytes.take(5).toByteArray().toString(Charsets.US_ASCII))
        assertFailsWith<java.io.IOException> { store.read("model-authored-path") }
    }

    @Test
    fun `prompt context contains no page citations for document-only source links`() {
        val root = Json.parseToJsonElement(DataModelCatalogContext.loadBundled().promptJson).jsonObject
        val vehicles = root.getValue("vehicles").jsonArray
        val options =
            vehicles.flatMap { vehicle ->
                vehicle.jsonObject.getValue("options").jsonArray
            }

        assertTrue(options.isNotEmpty())
        options.forEach { option ->
            assertFalse("sources" in option.jsonObject)
        }
        assertNotNull(vehicles.first().jsonObject["sourcePdfPath"])
    }
}
