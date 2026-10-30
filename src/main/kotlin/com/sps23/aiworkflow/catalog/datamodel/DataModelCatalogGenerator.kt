package com.sps23.aiworkflow.catalog.datamodel

import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

data class DataModelCatalogGenerationResult(
    val jsonFilesRead: Int,
    val pdfFilesLinked: Int,
    val outputPath: Path,
    val outputBytes: Int,
)

/** Parses every JSON file and links it to a PDF with the identical basename in the same folder. */
object DataModelCatalogGenerator {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }

    fun generate(
        sourceRoot: Path,
        outputPath: Path,
        log: (String) -> Unit = ::println,
    ): DataModelCatalogGenerationResult {
        require(sourceRoot.isDirectory()) { "Source directory does not exist: $sourceRoot" }
        val sourceFiles = findFiles(sourceRoot, "json")
        if (sourceFiles.isEmpty()) {
            throw DataModelParseException("No JSON files found under $sourceRoot")
        }
        val pdfFiles = findFiles(sourceRoot, "pdf")
        log("Source root: $sourceRoot")
        log("Discovered ${sourceFiles.size} JSON file(s) and ${pdfFiles.size} PDF file(s).")

        var filesRead = 0
        val linkedVehicles = mutableListOf<CarDataModel>()
        val failures = mutableListOf<String>()
        sourceFiles.forEach { jsonPath ->
            val relativeJsonPath = DataModelCatalogLoader.relativePath(sourceRoot, jsonPath)
            try {
                val jsonText = jsonPath.readText()
                filesRead++
                val car = CarDataModelParser.parse(jsonText, relativeJsonPath)
                val matchedPdf = matchPdf(sourceRoot, jsonPath, pdfFiles)
                val relativePdfPath = DataModelCatalogLoader.relativePath(sourceRoot, matchedPdf)
                linkedVehicles += car.copy(sourcePdfPath = relativePdfPath)
                log("READ $relativeJsonPath")
                log("LINK $relativeJsonPath -> $relativePdfPath")
            } catch (exception: DataModelParseException) {
                failures += "$relativeJsonPath: ${exception.message}"
                log("ERROR $relativeJsonPath: ${exception.message}")
            } catch (exception: IOException) {
                failures += "$relativeJsonPath: ${exception.message ?: exception.javaClass.simpleName}"
                log("ERROR $relativeJsonPath: ${exception.message ?: exception.javaClass.simpleName}")
            }
        }
        if (failures.isNotEmpty()) {
            log("Catalog generation aborted: ${failures.size} file(s) failed; existing output was not changed.")
            throw DataModelParseException("Catalog generation failed:\n${failures.joinToString("\n")}")
        }

        val catalog = DataModelCatalog(linkedVehicles)
        val outputText = json.encodeToString(DataModelCatalogArtifact.serializer(), DataModelCatalogArtifact.from(catalog)) + "\n"
        writeAtomically(outputPath, outputText)
        val outputBytes = outputText.toByteArray(StandardCharsets.UTF_8).size
        log("JSON files read: $filesRead")
        log("PDFs linked: ${linkedVehicles.size}")
        log("Vehicles in memory: ${catalog.vehicles.size}")
        log("Output: $outputPath ($outputBytes UTF-8 bytes)")
        return DataModelCatalogGenerationResult(filesRead, linkedVehicles.size, outputPath, outputBytes)
    }

    private fun findFiles(
        root: Path,
        extension: String,
    ): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .asSequence()
                .filter { it.isRegularFile() && it.extension.equals(extension, ignoreCase = true) }
                .sortedBy { DataModelCatalogLoader.relativePath(root, it) }
                .toList()
        }

    private fun matchPdf(
        root: Path,
        jsonPath: Path,
        pdfFiles: List<Path>,
    ): Path {
        val jsonStem = jsonPath.name.substringBeforeLast('.', jsonPath.name)

        val matches =
            pdfFiles.filter { pdfPath ->
                pdfPath.parent == jsonPath.parent &&
                    pdfPath.name.substringBeforeLast('.', pdfPath.name) == jsonStem
            }
        if (matches.size != 1) {
            val found = matches.ifEmpty { emptyList() }.joinToString { DataModelCatalogLoader.relativePath(root, it) }
            throw DataModelParseException(
                "${DataModelCatalogLoader.relativePath(root, jsonPath)}: expected exactly one sibling PDF named " +
                    "$jsonStem.pdf; found ${matches.size}" +
                    if (found.isEmpty()) "" else ": $found",
            )
        }
        return matches.single()
    }

    private fun writeAtomically(
        outputPath: Path,
        content: String,
    ) {
        val absoluteOutput = outputPath.toAbsolutePath().normalize()
        val parent = absoluteOutput.parent ?: throw IOException("Output path has no parent: $outputPath")
        Files.createDirectories(parent)
        val prefix = absoluteOutput.fileName.toString().padStart(3, '_')
        val temporary = Files.createTempFile(parent, prefix, ".tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, absoluteOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absoluteOutput, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                Files.move(temporary, absoluteOutput, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
