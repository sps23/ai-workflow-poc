package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.ExtractionProvenance
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionService
import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceCatalogBuildServiceTest {
    private val directory = Files.createTempDirectory("workspace-catalog-build-test")
    private val paths = WorkspacePaths.at(directory.resolve("workspace"))
    private val service = WorkspaceCatalogBuildService(paths)
    private val pdfBytes = "%PDF-1.7 workspace fixture".toByteArray()

    init {
        Files.createDirectories(paths.input)
        Files.createDirectories(paths.data)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(directory).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `inventory includes sources and explicitly excludes raw diagnostics`() =
        runBlocking {
            writeLegacySource("brand/car.json")
            paths.input.resolve("brand/legacy.raw.json").writeText("not json")
            paths.input.resolve("brand/provider-output.json").writeText("""{"kind":"pdf-extraction-diagnostic"}""")
            paths.input.resolve("brand/broken.json").writeText("not json")

            val inventory = service.refresh()

            assertEquals(
                listOf("brand/broken.json", "brand/car.json", "brand/legacy.raw.json", "brand/provider-output.json"),
                inventory.rows.map { it.sourcePath },
            )
            assertEquals(2, inventory.includedCount)
            assertEquals(2, inventory.excludedCount)
            assertEquals(WorkspaceCatalogSourceType.INVALID, inventory.rows.first().sourceType)
            assertEquals(WorkspaceCatalogSourceType.LEGACY_RAW, inventory.rows[2].sourceType)
            assertEquals(WorkspaceCatalogSourceType.DIAGNOSTIC, inventory.rows[3].sourceType)
            assertEquals(listOf("brand/broken.json: Invalid JSON."), inventory.errors)
        }

    @Test
    fun `build publishes a validated catalog with input-relative paths`() =
        runBlocking {
            writeLegacySource("brand/car.json")
            val inventory = service.refresh()

            val built =
                assertIs<WorkspaceCatalogBuildOutcome.Built>(service.build(inventory, replaceExistingConfirmed = false))

            assertEquals(paths.data.resolve("data-model-catalog.json"), built.catalogPath)
            assertEquals(listOf("brand/car.json"), built.includedSources)
            assertEquals(1, built.vehicleCount)
            val artifact = DataModelCatalogFixtures.loadArtifact(built.catalogPath)
            assertEquals("brand/car.json", artifact.vehicles.single().sourcePath)
            assertEquals("brand/car.pdf", artifact.vehicles.single().sourcePdfPath)
            assertEquals(built.context.promptJson, DataModelCatalogContext.loadWorkspace(paths).promptJson)
            val log =
                paths.logs
                    .listDirectoryEntries("*_builder.log")
                    .single()
                    .readText()
            assertTrue("SOURCE_SNAPSHOT" in log)
            assertTrue("sourceJson" in log)
            assertTrue("catalogJson" in log)
            assertTrue("sourceSha256" in log)
            assertTrue("\"event\":\"COMPLETED\"" in log.lines().filter { it.isNotBlank() }.last())
        }

    @Test
    fun `overwrite requires confirmation and leaves the previous catalog intact`() =
        runBlocking {
            writeLegacySource("brand/car.json")
            val target = paths.data.resolve("data-model-catalog.json").also { it.writeText("previous") }
            val inventory = service.refresh()

            assertEquals(
                WorkspaceCatalogBuildOutcome.Declined,
                service.build(inventory, replaceExistingConfirmed = false),
            )

            assertEquals("previous", target.readText())
        }

    @Test
    fun `changed input after refresh aborts publication and preserves the prior catalog`() =
        runBlocking {
            val source = writeLegacySource("brand/car.json")
            val target = paths.data.resolve("data-model-catalog.json").also { it.writeText("previous") }
            val inventory = service.refresh()
            source.writeText(source.readText() + "\n")

            val failed =
                assertIs<WorkspaceCatalogBuildOutcome.Failed>(service.build(inventory, replaceExistingConfirmed = true))

            assertTrue(failed.errors.any { it.contains("changed after refresh") })
            val log =
                paths.logs
                    .listDirectoryEntries("*_builder.log")
                    .single()
                    .readText()
            assertTrue("changed after refresh" in log)
            assertTrue("\"event\":\"FAILED\"" in log.lines().filter { it.isNotBlank() }.last())
            assertEquals("previous", target.readText())
            assertFalse(Files.list(paths.data).use { entries -> entries.anyMatch { it.fileName.toString().endsWith(".tmp") } })
        }

    @Test
    fun `extraction provenance must name a safe sibling PDF with matching hash`() =
        runBlocking {
            val vehicle = DataModelFixtures.frozen(DataModelFixtures.CUPRA_RAVAL).vehicle
            val jsonPath = paths.input.resolve("nested/extraction.json")
            Files.createDirectories(jsonPath.parent)
            val pdfPath = jsonPath.resolveSibling("original.pdf").also { it.writeBytes(pdfBytes) }
            val extraction =
                PdfExtractionFile(
                    provenance =
                        ExtractionProvenance(
                            pdfFileName = pdfPath.fileName.toString(),
                            pdfSha256 = PdfExtractionService.sha256(pdfBytes),
                            model = "test",
                            extractedAt = Instant.parse("2026-11-01T00:00:00Z").toString(),
                            inputTokens = null,
                            outputTokens = null,
                        ),
                    vehicle = vehicle,
                )
            jsonPath.writeText(PdfExtractionFile.encode(extraction))

            assertTrue(
                service
                    .refresh()
                    .rows
                    .single()
                    .errors
                    .isEmpty(),
            )
            pdfPath.writeBytes("%PDF-1.7 changed".toByteArray())
            val changedHashRow = service.refresh().rows.single()
            assertTrue(changedHashRow.errors.single().contains("hash does not match"))

            val unsafe =
                extraction.copy(provenance = extraction.provenance.copy(pdfFileName = "../outside.pdf"))
            jsonPath.writeText(PdfExtractionFile.encode(unsafe))
            assertTrue(
                service
                    .refresh()
                    .rows
                    .single()
                    .errors
                    .single()
                    .contains("unsafe provenance"),
            )
        }

    @Test
    fun `duplicate vehicle ids block publication`() =
        runBlocking {
            writeLegacySource("brand/car.json")
            writeLegacySource("other/copy.json")

            val inventory = service.refresh()
            val result =
                assertIs<WorkspaceCatalogBuildOutcome.Failed>(
                    service.build(
                        inventory,
                        replaceExistingConfirmed = false,
                    ),
                )

            assertTrue(result.errors.single().contains("Duplicate vehicle id"))
            assertFalse(Files.exists(result.catalogPath))
        }

    private fun writeLegacySource(relativePath: String): Path {
        val source = paths.input.resolve(relativePath)
        Files.createDirectories(source.parent)
        val fixture = DataModelFixtures.pricelistRoot().resolve(DataModelFixtures.CUPRA_RAVAL)
        source.writeText(Files.readString(fixture))
        source.resolveSibling(source.fileName.toString().substringBeforeLast('.') + ".pdf").writeBytes(pdfBytes)
        return source
    }

    private object DataModelCatalogFixtures {
        fun loadArtifact(path: Path): DataModelCatalogArtifact =
            DataModelCatalogArtifact.serializer().let { serializer ->
                DataModelFixtures.json.decodeFromString(serializer, Files.readString(path))
            }
    }
}
