package com.sps23.aiworkflow.recommendation

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
    @Test
    fun `UI context uses normalized car data with deterministic variant ids and linked PDFs`() {
        val context = DataModelCatalogContext.loadBundled()
        val root = Json.parseToJsonElement(context.promptJson).jsonObject
        val vehicles = root.getValue("vehicles").jsonArray

        assertEquals(4, vehicles.size)
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
                        .jsonPrimitive.content == "Urban"
                }.jsonObject
        val firstVariant =
            urban
                .getValue("configurations")
                .jsonArray
                .first()
                .jsonObject
        val configurationId = firstVariant.getValue("configurationId").jsonPrimitive.content
        assertEquals("cupra-raval-urban-raval-urban-116hp-v1", configurationId)
        assertTrue("RRP_EXCLUDING_GRANTS" in firstVariant.toString())
        assertEquals(cupra.getValue("sourcePdfPath").jsonPrimitive.content, context.sourcePdfPath(configurationId))
        assertTrue(context.promptJson.toByteArray().size < 250_868)
        assertFalse("documentId:page" in context.promptJson)
    }

    @Test
    fun `source PDF lookup uses bundled registry and rejects unknown configuration`() {
        val context = DataModelCatalogContext.loadBundled()
        val store = DataModelPdfStore(context)
        val (filename, bytes) = store.read("cupra-raval-urban-raval-urban-116hp-v1")

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
