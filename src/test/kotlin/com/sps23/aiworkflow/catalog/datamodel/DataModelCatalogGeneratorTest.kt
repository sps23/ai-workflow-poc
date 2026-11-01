package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataModelCatalogGeneratorTest {
    @Test
    @Ignore("TODO: Update the bundled-catalog expectations and re-enable this test.")
    fun `bundled car JSON files are normalized and linked to same-basename PDFs`() {
        val outputDirectory = Files.createTempDirectory("data-model-catalog")
        try {
            val output = outputDirectory.resolve("data-model-catalog.json")
            val logs = mutableListOf<String>()
            val result =
                DataModelCatalogGenerator.generate(
                    sourceRoot = DataModelFixtures.pricelistRoot(),
                    outputPath = output,
                    log = logs::add,
                )

            assertEquals(4, result.jsonFilesRead)
            assertEquals(4, result.pdfFilesLinked)
            assertTrue(result.outputBytes > 0)
            val artifact = Json.decodeFromString<DataModelCatalogArtifact>(output.toFile().readText())
            assertEquals(DATA_MODEL_CATALOG_SCHEMA_VERSION, artifact.schemaVersion)
            assertEquals(
                mapOf(
                    "cupra-raval" to "cupra/20260820_CUPRA_Ireland_Product_Guide_Raval.pdf",
                    "peugeot-5008" to "peugeot/5008-spec-sheet.pdf",
                    "skoda-peaq" to "skoda/Peaq_MY27_PG_August_CW48_2026.pdf",
                    "toyota-corolla-cross" to "toyota/Toyota Corolla Cross - Brochure.pdf",
                ),
                artifact.vehicles.associate { it.id to it.sourcePdfPath },
            )
            assertEquals("2026-09", artifact.vehicles.single { it.id == "cupra-raval" }.effectivePeriod)
            assertTrue(logs.any { it == "JSON files read: 4" })
            assertTrue(logs.any { it == "PDFs linked: 4" })
            assertTrue(logs.any { it.startsWith("Vehicles in memory: 4") })
        } finally {
            outputDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing matching PDF fails without creating output`() {
        val directory = Files.createTempDirectory("data-model-no-pdf")
        try {
            val source = directory.resolve("cars").createDirectories()
            writeDataModel(source.resolve("roadster.json"))
            val output = directory.resolve("output/catalog.json")

            val exception =
                assertFailsWith<DataModelParseException> {
                    DataModelCatalogGenerator.generate(source, output, log = {})
                }

            assertTrue("expected exactly one sibling PDF named roadster.pdf" in exception.message!!)
            assertTrue("found 0" in exception.message!!)
            assertFalse(Files.exists(output))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `similar but differently named PDFs do not match and preserve the previous output`() {
        val directory = Files.createTempDirectory("data-model-similar-pdf")
        try {
            val source = directory.resolve("cars").createDirectories()
            writeDataModel(source.resolve("roadster.json"))
            source.resolve("2026-roadster.pdf").writeText("fixture")
            source.resolve("roadster-brochure.pdf").writeText("fixture")
            val output = directory.resolve("catalog.json")
            output.writeText("""{"previous":"catalog"}""")

            val exception =
                assertFailsWith<DataModelParseException> {
                    DataModelCatalogGenerator.generate(source, output, log = {})
                }

            assertTrue("found 0" in exception.message!!)
            assertEquals("""{"previous":"catalog"}""", output.toFile().readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `JSON links only to an identical sibling PDF basename`() {
        val directory = Files.createTempDirectory("data-model-exact-pdf")
        try {
            val source = directory.resolve("cars").createDirectories()
            writeDataModel(source.resolve("roadster.json"))
            source.resolve("roadster.pdf").writeText("fixture")
            source.resolve("roadster-brochure.pdf").writeText("fixture")
            val output = directory.resolve("catalog.json")

            val result = DataModelCatalogGenerator.generate(source, output, log = {})

            assertEquals(1, result.jsonFilesRead)
            assertEquals(1, result.pdfFilesLinked)
            val artifact = Json.decodeFromString<DataModelCatalogArtifact>(output.toFile().readText())
            assertEquals("roadster.pdf", artifact.vehicles.single().sourcePdfPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful generation overwrites the existing catalog file`() {
        val directory = Files.createTempDirectory("data-model-overwrite")
        try {
            val source = directory.resolve("cars").createDirectories()
            writeDataModel(source.resolve("roadster.json"))
            source.resolve("roadster.pdf").writeText("fixture")
            val output = directory.resolve("catalog.json")
            output.writeText("""{"old":"catalog"}""")

            DataModelCatalogGenerator.generate(source, output, log = {})

            val artifact = Json.decodeFromString<DataModelCatalogArtifact>(output.toFile().readText())
            assertEquals(listOf("acme-roadster"), artifact.vehicles.map { it.id })
            assertEquals("roadster.pdf", artifact.vehicles.single().sourcePdfPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mixed folder links source-shape and promoted extraction files to their PDFs`() {
        val directory = Files.createTempDirectory("data-model-mixed")
        try {
            val source = directory.resolve("cars").createDirectories()
            writeDataModel(source.resolve("roadster.json"))
            source.resolve("roadster.pdf").writeText("fixture")
            val brand = source.resolve("example").createDirectories()
            brand.resolve("brochure.json").writeText(ExtractionFixtures.text)
            brand.resolve("brochure.pdf").writeText("fixture")
            val output = directory.resolve("catalog.json")
            val logs = mutableListOf<String>()

            val result = DataModelCatalogGenerator.generate(source, output, log = logs::add)

            assertEquals(2, result.jsonFilesRead)
            assertEquals(2, result.pdfFilesLinked)
            val artifact = Json.decodeFromString<DataModelCatalogArtifact>(output.toFile().readText())
            val extracted = ExtractionFixtures.vehicle.toCarDataModel("example/brochure.json", "example/brochure.pdf")
            assertEquals(
                mapOf("acme-roadster" to "roadster.pdf", extracted.id to "example/brochure.pdf"),
                artifact.vehicles.associate { it.id to it.sourcePdfPath },
            )
            assertEquals(DataModelCatalogArtifact.from(DataModelCatalog(listOf(extracted))).vehicles.single(), artifact.vehicles.first())
            assertTrue("LINK example/brochure.json -> example/brochure.pdf" in logs)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unsupported schemaVersion aborts generation and keeps the previous output`() {
        val directory = Files.createTempDirectory("data-model-bad-version")
        try {
            val source = directory.resolve("cars").createDirectories()
            source.resolve("brochure.json").writeText(ExtractionFixtures.text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
            source.resolve("brochure.pdf").writeText("fixture")
            val output = directory.resolve("catalog.json")
            output.writeText("""{"previous":"catalog"}""")

            val exception = assertFailsWith<DataModelParseException> { DataModelCatalogGenerator.generate(source, output, log = {}) }

            assertTrue("brochure.json: unsupported schemaVersion 99" in exception.message!!, exception.message)
            assertEquals("""{"previous":"catalog"}""", output.toFile().readText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun writeDataModel(path: java.nio.file.Path) {
        path.writeText("""{"model":"Acme Roadster","trims":[]}""")
    }
}
