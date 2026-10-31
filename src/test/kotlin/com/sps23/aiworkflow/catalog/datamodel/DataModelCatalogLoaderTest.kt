package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataModelCatalogLoaderTest {
    private val root = DataModelFixtures.pricelistRoot()
    private val expectedModels =
        mapOf(
            "cupra/20260727_CUPRA_Ireland_Product_Guide_Leon_PA.json" to "cupra-leon-leon-sportstourer",
            "cupra/20260820_CUPRA_Ireland_Product_Guide_New_Born_PA.json" to "cupra-born",
            DataModelFixtures.CUPRA_RAVAL to "cupra-raval",
            "cupra/20270727_CUPRA_Ireland_Product_Guide_Formentor_PA.json" to "cupra-formentor",
            "dacia/dacia bigster.json" to "dacia-bigster",
            "dacia/dacia duster.json" to "dacia-duster-iii",
            "dacia/dacia jogger.json" to "dacia-jogger",
            "dacia/dacia sandero stepway.json" to "dacia-sandero-stepway",
            "dacia/dacia sandero.json" to "dacia-sandero",
            "peugeot/208-spec-sheet.json" to "peugeot-208",
            "peugeot/408-spec-sheet.json" to "peugeot-408",
            DataModelFixtures.PEUGEOT_5008 to "peugeot-5008",
            "peugeot/new-3008-spec-sheet.json" to "peugeot-3008",
            "skoda/Epiq_MY27_PG_September_CW48_2026.json" to "skoda-epiq",
            "skoda/Kodiaq_MY27_PG_January_CW01_2027.json" to "skoda-kodiaq",
            DataModelFixtures.SKODA_PEAQ to "skoda-peaq",
            "skoda/Superb_MY27_PG_Janury_CW01_2027.json" to "skoda-superb",
            DataModelFixtures.TOYOTA_COROLLA_CROSS to "toyota-corolla-cross",
            "toyota/Toyota RAV4 - Hybrid Brochure.json" to "toyota-rav4-hybrid",
            "toyota/Toyota RAV4 - PHEV Brochure.json" to "toyota-rav4-plug-in-hybrid",
            "toyota/Toyota Yaris - Hybrid Brochure.json" to "toyota-yaris-hybrid",
            "vw/Golf_MY27.json" to "volkswagen-golf-my27",
            "vw/ID7_MY27.json" to "volkswagen-id-7-id-7-tourer-my27",
            "vw/Tayron_MY27.json" to "volkswagen-tayron-my27",
            "vw/Tiguan_MY27.json" to "volkswagen-tiguan-my27",
        )

    @Test
    fun `every model specification file is covered by the catalog tests`() {
        val discovered = DataModelCatalogLoader.findDataModelFiles(root).map { DataModelCatalogLoader.relativePath(root, it) }

        assertEquals(expectedModels.keys.sorted(), discovered.sorted(), "Update coverage when model files are added or removed")
    }

    @Test
    fun `classpath and directory catalogs load every model with identical specifications`() {
        val catalog = DataModelCatalogLoader.loadFromClasspath()

        assertEquals(
            expectedModels,
            catalog.vehicles.associate { it.sourcePath to it.id },
        )
        assertEquals(expectedModels.size, catalog.vehicles.size)
        assertEquals(
            catalog.vehicles.size,
            catalog.vehicles
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(catalog, DataModelCatalogLoader.loadDirectory(root))

        expectedModels.forEach { (path, id) ->
            assertEquals(path, assertNotNull(catalog.byId(id), id).sourcePath)
        }
        catalog.vehicles.groupBy { it.make }.forEach { (make, vehicles) ->
            assertEquals(vehicles, catalog.byMake(make.uppercase()), "$make lookup")
        }
        assertEquals(null, catalog.byId("missing-model"))
        assertTrue(catalog.byMake("missing-make").isEmpty())
    }

    @Test
    fun `every loaded model preserves its published core structure and raw schema fields`() {
        DataModelCatalogLoader.loadDirectory(root).vehicles.forEach { car ->
            val label = car.sourcePath
            val source = Json.parseToJsonElement(root.resolve(label).readText()).jsonObject
            assertEquals(source.getValue("model").jsonPrimitive.content, car.title, "$label title")
            assertEquals(source.getValue("effective_date").jsonPrimitive.content, car.effectiveDate, "$label effective date")
            assertNotNull(car.effectivePeriod, "$label effective period")
            assertTrue(car.make.isNotBlank() && car.model.isNotBlank(), "$label identity")

            val sourceTrims = source["trims"]?.jsonArray.orEmpty()
            if ("trims" in source) assertTrue(sourceTrims.isNotEmpty(), "$label published trims")
            assertEquals(sourceTrims.size, car.trims.size, "$label trims")
            sourceTrims.zip(car.trims).forEach { (element, trim) ->
                val published = element.jsonObject
                val trimLabel = "$label ${trim.name}"
                assertEquals(published.getValue("trim_level").jsonPrimitive.content, trim.name, trimLabel)
                val pricing = published["pricing"]?.jsonArray.orEmpty()
                assertEquals(pricing.size, trim.variants.size, "$trimLabel variants")
                pricing.zip(trim.variants).forEach { (row, variant) ->
                    val fields = row.jsonObject
                    assertEquals(
                        (fields["variant"] ?: fields.getValue("powertrain")).jsonPrimitive.content,
                        variant.name,
                        "$trimLabel variant name",
                    )
                    assertEquals(trim.name, variant.trimName, "$trimLabel variant trim")
                    assertTrue(variant.prices.isNotEmpty(), "$trimLabel ${variant.name} prices")
                    assertEquals(
                        fields.filterKeys { it in variantPriceFields }.mapValues { it.value.jsonPrimitive.content },
                        variant.prices.associate { priceField(it.basis) to it.rawText },
                        "$trimLabel ${variant.name} all published prices",
                    )
                    assertAdditionalPreserved(fields, variant.additional, "$trimLabel ${variant.name}")
                }
                assertEquals(
                    published["standard_equipment"]?.let(::equipmentItems).orEmpty(),
                    trim.allEquipmentItems,
                    "$trimLabel equipment",
                )
                assertEquals(
                    published.filterKeys { it !in setOf("trim_level", "pricing", "standard_equipment") }.mapValues { render(it.value) },
                    trim.additional,
                    "$trimLabel raw highlights, seating and body styles",
                )
            }

            val technical = source["technical_specifications"]?.jsonObject
            val powertrains = technical?.get("powertrains")?.jsonArray.orEmpty()
            if (technical != null) assertTrue(powertrains.isNotEmpty(), "$label published powertrains")
            assertEquals(powertrains.size, car.technicalSpecifications.powertrains.size, "$label technical powertrains")
            powertrains.zip(car.technicalSpecifications.powertrains).forEach { (element, spec) ->
                val fields = element.jsonObject
                val name = fields["version"] ?: fields["name"] ?: fields["engine_version"]
                assertEquals(assertNotNull(name, "$label required powertrain name").jsonPrimitive.content, spec.name, label)
                assertTrue(spec.name.isNotBlank(), "$label powertrain name")
                assertAdditionalPreserved(fields, spec.additional, "$label ${spec.name}")
            }
            technical?.get("dimensions")?.jsonObject?.let { dimensions ->
                assertNotNull(car.technicalSpecifications.dimensions.lengthMm, "$label published length")
                assertAdditionalPreserved(dimensions, car.technicalSpecifications.dimensions.additional, "$label dimensions")
            }
            assertEquals(
                source.filterKeys { it !in normalizedRootFields }.mapValues { render(it.value) },
                car.additional,
                "$label raw top-level specs",
            )
        }
    }

    @Test
    fun `every published option and colour is retained`() {
        DataModelCatalogLoader.loadDirectory(root).vehicles.forEach { car ->
            val source = Json.parseToJsonElement(root.resolve(car.sourcePath).readText()).jsonObject
            val published =
                source
                    .filterKeys { it in normalizedRootFields - setOf("model", "effective_date", "trims", "technical_specifications") }
                    .flatMap { (key, value) ->
                        when {
                            key == "optional_equipment" -> {
                                value.jsonArray.flatMap { it.jsonObject.getValue("options").jsonArray }
                            }

                            key == "color_options" && value is JsonObject -> {
                                value.values.flatMap { it.jsonArray }
                            }

                            else -> {
                                value.jsonArray.toList()
                            }
                        }
                    }.map { it.jsonObject }
                    .sortedBy(::optionName)
            val options = car.options.sortedBy { it.name }

            assertEquals(published.map(::optionName), options.map { it.name }, "${car.sourcePath} options and colours")
            published.zip(options).forEach { (fields, option) ->
                assertEquals(fields["code"]?.jsonPrimitive?.content, option.code, "${car.sourcePath} ${option.name} code")
                assertAdditionalPreserved(fields, option.additional, "${car.sourcePath} ${option.name}")
            }
        }
    }

    @Test
    fun `Toyota brochure specs and grades remain available as raw data`() {
        val vehicles = DataModelCatalogLoader.loadDirectory(root).vehicles.filter { it.make == "Toyota" }

        assertEquals(4, vehicles.size)
        vehicles.forEach { car ->
            val label = car.sourcePath
            assertTrue(Json.parseToJsonElement(car.additional.getValue("powertrains")).jsonArray.isNotEmpty(), "$label powertrains")
            assertTrue(
                Json.parseToJsonElement(car.additional.getValue("grades_and_equipment")).jsonArray.isNotEmpty(),
                "$label grades",
            )
            assertTrue(
                Json.parseToJsonElement(car.additional.getValue("dimensions_weights")).jsonObject.isNotEmpty(),
                "$label dimensions",
            )
        }
    }

    @Test
    fun `VW highlights and all six ID7 body style price rows remain available`() {
        val catalog = DataModelCatalogLoader.loadDirectory(root)
        val volkswagens = catalog.byMake("Volkswagen")
        assertEquals(4, volkswagens.size)
        volkswagens.forEach { car ->
            car.trims.forEach { trim ->
                assertTrue(
                    Json.parseToJsonElement(trim.additional.getValue("highlights")).jsonArray.isNotEmpty(),
                    "${car.sourcePath} ${trim.name} highlights",
                )
            }
        }
        val id7 = assertNotNull(catalog.byId("volkswagen-id-7-id-7-tourer-my27"))
        assertEquals(3, id7.trims.size)
        id7.trims.forEach { trim ->
            val bodyStyles = Json.parseToJsonElement(trim.additional.getValue("body_styles")).jsonArray
            assertEquals(
                listOf("Fastback", "Tourer"),
                bodyStyles.map {
                    it.jsonObject
                        .getValue("body_style")
                        .jsonPrimitive.content
                },
            )
            bodyStyles.forEach { row ->
                listOf("model_code", "powertrain", "rrp", "rotr_price", "range_wltp_km").forEach { key ->
                    assertTrue(
                        row.jsonObject
                            .getValue(key)
                            .jsonPrimitive.content
                            .isNotBlank(),
                        "${trim.name} $key",
                    )
                }
            }
        }
    }

    private val normalizedRootFields =
        setOf(
            "model",
            "effective_date",
            "trims",
            "technical_specifications",
            "optional_equipment",
            "wheels_and_tyres",
            "interior_options",
            "design_selections",
            "color_options",
            "optional_packages_and_extras",
            "optional_packages",
            "single_options",
            "accessory_packs",
        )

    private val variantPriceFields =
        PriceBasis.entries
            .filter { it != PriceBasis.OPTION }
            .map(::priceField)
            .toSet()

    private fun optionName(fields: JsonObject): String =
        assertNotNull(fields["name"] ?: fields["description"] ?: fields["pack_name"] ?: fields["trim_name"]).jsonPrimitive.content

    private fun assertAdditionalPreserved(
        source: JsonObject,
        additional: Map<String, String>,
        label: String,
    ) {
        additional.forEach { (key, value) ->
            assertEquals(render(source.getValue(key)), value, "$label raw $key")
        }
    }

    private fun render(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()

    private fun equipmentItems(element: JsonElement): List<String> =
        when (element) {
            is JsonObject -> element.values.flatMap(::equipmentItems)
            is JsonArray -> element.map(::render)
            else -> listOf(render(element))
        }

    private fun priceField(basis: PriceBasis): String =
        when (basis) {
            PriceBasis.PRICE -> "price"
            PriceBasis.RRP -> "rrp"
            PriceBasis.RRP_FROM -> "rrp_from"
            PriceBasis.RRP_EXCLUDING_GRANTS -> "rrp_excl_grants"
            PriceBasis.RRP_INCLUDING_GRANTS -> "rrp_incl_grants"
            PriceBasis.ON_THE_ROAD -> "rotr_price"
            PriceBasis.VRT_REBATE -> "vrt_rebate"
            PriceBasis.SEAI_GRANT -> "seai_grant"
            PriceBasis.ANNUAL_ROAD_TAX -> "annual_road_tax"
            PriceBasis.OPTION -> error("Option price is not a trim variant price")
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
