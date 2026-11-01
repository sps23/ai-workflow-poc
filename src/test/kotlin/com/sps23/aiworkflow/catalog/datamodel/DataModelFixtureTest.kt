package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionProvenance
import com.sps23.aiworkflow.catalog.extraction.ExtractionRequirements
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionService
import com.sps23.aiworkflow.catalog.extraction.Severity
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class DataModelFixtureTest(
    private val sourcePath: String,
) {
    @Test
    fun `parsed model matches the frozen normalized fixture`() {
        assertEquals(DataModelFixtures.expected(sourcePath), DataModelFixtures.load(sourcePath), sourcePath)
    }

    @Test
    fun `fixture has valid identity and preserves every normalized field through serialization`() {
        val expected = DataModelFixtures.expected(sourcePath)
        assertEquals(sourcePath, expected.sourcePath)
        assertTrue(expected.id.isNotBlank() && expected.title.isNotBlank(), sourcePath)
        assertTrue(expected.make.isNotBlank() && expected.model.isNotBlank(), sourcePath)
        assertNotNull(expected.effectivePeriod, sourcePath)
        val frozen = FrozenDataModel.from(expected)
        val encoded = DataModelFixtures.json.encodeToString(FrozenDataModel.serializer(), frozen)
        assertEquals(
            expected,
            DataModelFixtures.json.decodeFromString(FrozenDataModel.serializer(), encoded).toModel(),
            sourcePath,
        )
    }

    @Test
    fun `fixture meets every production vehicle validation rule for catalog promotion`() {
        val fixture = DataModelFixtures.frozen(sourcePath)
        val violations = ExtractionRequirements.validateVehicle(fixture.vehicle).filter { it.severity == Severity.HARD }
        assertTrue(
            violations.isEmpty(),
            "$sourcePath violates catalog promotion rules:\n" + violations.joinToString("\n") { "${it.rule}: ${it.message}" },
        )
        assertEquals(fixture.toModel(), fixture.vehicle.toCarDataModel(sourcePath), "$sourcePath code-owned identity")
    }

    @Test
    fun `fixture generates a catalog with correct PDF linkage and unchanged vehicle facts`() {
        val fixture = DataModelFixtures.frozen(sourcePath)
        val directory = createTempDirectory("fixture-catalog-generation")
        try {
            val root = directory.resolve("sources").createDirectories()
            val source = root.resolve(sourcePath)
            source.parent.createDirectories()
            val pdfPath = sourcePath.substringBeforeLast('.') + ".pdf"
            val pdf = root.resolve(pdfPath)
            val bytes = "%PDF-1.4\n%%EOF\n".toByteArray()
            pdf.writeBytes(bytes)
            val extraction =
                PdfExtractionFile(
                    provenance =
                        ExtractionProvenance(
                            pdfFileName = pdf.fileName.toString(),
                            pdfSha256 = PdfExtractionService.sha256(bytes),
                            model = "offline-fixture",
                            extractedAt = "2026-11-01T00:00:00Z",
                            inputTokens = null,
                            outputTokens = null,
                        ),
                    vehicle = fixture.vehicle,
                )
            source.writeText(PdfExtractionFile.encode(extraction))
            val output = directory.resolve("output/data-model-catalog.json")
            val result = DataModelCatalogGenerator.generate(root, output, log = {})
            assertEquals(1, result.jsonFilesRead, sourcePath)
            assertEquals(1, result.pdfFilesLinked, sourcePath)
            assertEquals(output.readText().toByteArray().size, result.outputBytes, sourcePath)
            val artifact = Json.decodeFromString<DataModelCatalogArtifact>(output.readText())
            val expected =
                DataModelCatalogArtifact.from(
                    DataModelCatalog(listOf(fixture.toModel().copy(sourcePdfPath = pdfPath))),
                )
            assertEquals(expected, artifact, "$sourcePath generated catalog")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<String>> =
            DataModelFixtures.fixturePaths().also { require(it.isNotEmpty()) { "No Data Model test fixtures found." } }.map { arrayOf(it) }
    }
}
