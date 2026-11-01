package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractedVehicle
import com.sps23.aiworkflow.catalog.extraction.ExtractionProvenance
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionPrompts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Promoted extraction envelope built from the model-definition example vehicle. */
internal object ExtractionFixtures {
    val vehicle: ExtractedVehicle =
        PdfExtractionPrompts.modelDefinition().let { def ->
            val example = def.substring(def.indexOf("{", def.indexOf("## Example")), def.lastIndexOf("}") + 1)
            ExtractedVehicle.strictJson.decodeFromString(ExtractedVehicle.serializer(), example)
        }

    val file =
        PdfExtractionFile(
            provenance = ExtractionProvenance("brochure.pdf", "ab".repeat(32), "gemini-test", "2026-10-31T12:00:00Z", 1, 2),
            vehicle = vehicle,
        )

    val text: String = PdfExtractionFile.encode(file)

    fun edited(edit: (JsonObject) -> JsonObject): String = edit(Json.parseToJsonElement(text).jsonObject).toString()
}

class CatalogSourceParserTest {
    @Test
    fun `extraction file maps its vehicle and ignores provenance`() {
        val car = CatalogSourceParser.parse(ExtractionFixtures.text, "acme/brochure.json")

        assertEquals(ExtractionFixtures.vehicle.toCarDataModel("acme/brochure.json"), car)
        assertEquals("acme/brochure.json", car.sourcePath)
        assertEquals(null, car.sourcePdfPath)
    }

    @Test
    fun `source-shape JSON still goes through CarDataModelParser`() {
        val text = """{"model":"Acme Roadster","trims":[]}"""
        assertEquals(CarDataModelParser.parse(text, "acme/roadster.json"), CatalogSourceParser.parse(text, "acme/roadster.json"))
    }

    @Test
    fun `BOM is tolerated for extraction files`() {
        assertEquals(ExtractionFixtures.vehicle.make, CatalogSourceParser.parse("\uFEFF" + ExtractionFixtures.text, "a.json").make)
    }

    @Test
    fun `unknown or malformed schemaVersion fails clearly`() {
        listOf(JsonPrimitive(2), JsonPrimitive("1"), JsonPrimitive(1.5)).forEach { version ->
            val text = ExtractionFixtures.edited { JsonObject(it + ("schemaVersion" to version)) }
            val error = assertFailsWith<DataModelParseException> { CatalogSourceParser.parse(text, "acme/x.json") }
            assertTrue("acme/x.json: unsupported schemaVersion $version" in error.message!!, error.message)
            assertTrue("schemaVersion 1" in error.message!!)
        }
    }

    @Test
    fun `unknown keys and missing vehicle fail strict decoding`() {
        val unknown = ExtractionFixtures.edited { JsonObject(it + ("extra" to JsonPrimitive(true))) }
        val missing = ExtractionFixtures.edited { JsonObject(it - "vehicle") }
        listOf(unknown, missing).forEach { text ->
            val error = assertFailsWith<DataModelParseException> { CatalogSourceParser.parse(text, "acme/x.json") }
            assertTrue("acme/x.json: invalid extraction file" in error.message!!, error.message)
        }
    }

    @Test
    fun `hand-edited vehicle breaking a hard rule fails with the rule list`() {
        val broken = ExtractionFixtures.vehicle.copy(make = " ", trims = emptyList())
        val text = PdfExtractionFile.encode(ExtractionFixtures.file.copy(vehicle = broken))

        val error = assertFailsWith<DataModelParseException> { CatalogSourceParser.parse(text, "acme/x.json") }

        assertTrue("acme/x.json: extraction file violates" in error.message!!, error.message)
        assertTrue("  R5: " in error.message!!)
        assertTrue("  R7: " in error.message!!)
    }

    @Test
    fun `loader reads a mixed folder of source-shape and extraction files`() {
        val root = Files.createTempDirectory("catalog-source-mixed")
        try {
            val brand = root.resolve("acme").createDirectories()
            brand.resolve("roadster.json").writeText("""{"model":"Other Roadster","trims":[]}""")
            brand.resolve("brochure.json").writeText(ExtractionFixtures.text)

            val catalog = DataModelCatalogLoader.loadDirectory(root)

            assertEquals(
                listOf("acme/brochure.json", "acme/roadster.json"),
                catalog.vehicles.map { it.sourcePath },
            )
            assertEquals(ExtractionFixtures.vehicle.model, catalog.vehicles.first().model)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
