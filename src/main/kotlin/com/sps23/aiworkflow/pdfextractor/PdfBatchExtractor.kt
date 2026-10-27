package com.sps23.aiworkflow.pdfextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.name

interface PdfDocumentParser {
    val name: String
    val version: String

    fun parse(source: PdfSource): PdfExtractionArtifact
}

data class PdfSource(
    val path: Path,
    val relativePath: String,
    val fileName: String,
    val make: String,
    val sha256: String,
    val fileSizeBytes: Long,
)

class PdfBoxDocumentParser : PdfDocumentParser {
    override val name: String = "Apache PDFBox"
    override val version: String =
        PDDocument::class.java.`package`.implementationVersion
            ?: "unknown"

    override fun parse(source: PdfSource): PdfExtractionArtifact =
        Loader.loadPDF(source.path.toFile()).use { document ->
            require(document.numberOfPages > 0) { "PDF contains no pages" }
            val stripper = PDFTextStripper()
            val pages =
                (1..document.numberOfPages).map { pageNumber ->
                    stripper.setStartPage(pageNumber)
                    stripper.setEndPage(pageNumber)
                    PdfArtifactPage(
                        pageNumber = pageNumber,
                        text = stripper.getText(document).removeSuffix("\u000c"),
                    )
                }
            val hasText = pages.any { page -> page.text.any { !it.isWhitespace() } }
            val diagnostics =
                buildList {
                    add(
                        PdfExtractionDiagnostic(
                            severity = DiagnosticSeverity.WARNING,
                            message = "General page text was extracted; table geometry and feature-matrix cells were not extracted.",
                        ),
                    )
                    if (!hasText) {
                        add(
                            PdfExtractionDiagnostic(
                                severity = DiagnosticSeverity.WARNING,
                                message = "No extractable text was found; scanned or image-only content requires review.",
                            ),
                        )
                    }
                }
            PdfExtractionArtifact(
                source =
                    PdfArtifactSource(
                        documentId = source.relativePath,
                        relativePath = source.relativePath,
                        fileName = source.fileName,
                        make = source.make,
                        modelCoverage = emptyList(),
                        sha256 = source.sha256,
                        fileSizeBytes = source.fileSizeBytes,
                        metadata = document.documentInformation.toMetadata(),
                    ),
                extraction =
                    PdfExtractionSummary(
                        parserName = name,
                        parserVersion = version,
                        pageCount = document.numberOfPages,
                        status = PdfExtractionStatus.NEEDS_REVIEW,
                        diagnostics = diagnostics,
                    ),
                pages = pages,
            )
        }

    private fun org.apache.pdfbox.pdmodel.PDDocumentInformation.toMetadata(): Map<String, String> =
        buildMap {
            putIfPresent("title", title)
            putIfPresent("author", author)
            putIfPresent("subject", subject)
            putIfPresent("keywords", keywords)
            putIfPresent("creator", creator)
            putIfPresent("producer", producer)
            creationDate?.let { put("creationDate", it.toInstant().toString()) }
            modificationDate?.let { put("modificationDate", it.toInstant().toString()) }
        }.toSortedMap()

    private fun MutableMap<String, String>.putIfPresent(
        key: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank()) put(key, value)
    }
}

@Serializable
data class PdfBatchManifest(
    val schemaVersion: Int = 1,
    val sourceRoot: String,
    val outputRoot: String,
    val parserName: String,
    val parserVersion: String,
    val documents: List<PdfBatchEntry>,
)

@Serializable
data class PdfBatchEntry(
    val relativePath: String,
    val sha256: String?,
    val outputRelativePath: String,
    val status: PdfBatchStatus,
    val diagnostic: String? = null,
)

@Serializable
enum class PdfBatchStatus {
    EXTRACTED,
    NEEDS_REVIEW,
    UNCHANGED,
    FAILED,
}

data class PdfBatchSummary(
    val discovered: Int,
    val extracted: Int,
    val unchanged: Int,
    val failed: Int,
    val unsupportedOrScanned: Int,
    val manifestPath: Path,
) {
    fun print() {
        println(
            "PDF batch: discovered=$discovered, extracted=$extracted, unchanged=$unchanged, " +
                "failed=$failed, unsupported/scanned=$unsupportedOrScanned; manifest=$manifestPath",
        )
    }
}

data class PdfBatchResult(
    val manifest: PdfBatchManifest,
    val summary: PdfBatchSummary,
)

class PdfBatchExtractor(
    private val inputRoot: Path,
    private val outputRoot: Path,
    private val parser: PdfDocumentParser,
) {
    private val manifestJson =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun run(): PdfBatchResult {
        require(Files.isDirectory(inputRoot)) { "PDF input directory does not exist: $inputRoot" }
        val normalizedInputRoot = inputRoot.toAbsolutePath().normalize()
        val normalizedOutputRoot = outputRoot.toAbsolutePath().normalize()
        Files.createDirectories(normalizedOutputRoot)

        val inventory = PdfInventory.createManifest(normalizedInputRoot, normalizedOutputRoot)
        val outputPaths = inventory.documents.associateWith(::outputPath)
        ensureUniqueOutputPaths(outputPaths.values.toList())
        val entries =
            inventory.documents.map { inventoryEntry ->
                val destination = outputPaths.getValue(inventoryEntry)
                processDocument(normalizedInputRoot, inventoryEntry, destination)
            }
        val manifest =
            PdfBatchManifest(
                sourceRoot = inventory.sourceRoot,
                outputRoot = normalizedOutputRoot.fileName?.toString() ?: normalizedOutputRoot.name,
                parserName = parser.name,
                parserVersion = parser.version,
                documents = entries,
            )
        val manifestPath = normalizedOutputRoot.resolve("batch-manifest.json")
        writeAtomically(manifestPath, encodeManifest(manifest))
        val summary =
            PdfBatchSummary(
                discovered = entries.size,
                extracted = entries.count { it.status == PdfBatchStatus.EXTRACTED || it.status == PdfBatchStatus.NEEDS_REVIEW },
                unchanged = entries.count { it.status == PdfBatchStatus.UNCHANGED },
                failed = entries.count { it.status == PdfBatchStatus.FAILED },
                unsupportedOrScanned =
                    inventory.documents.count {
                        it.contentType == PdfContentType.IMAGE_ONLY ||
                            it.contentType == PdfContentType.NO_EXTRACTABLE_CONTENT
                    },
                manifestPath = manifestPath,
            )
        return PdfBatchResult(manifest, summary)
    }

    private fun processDocument(
        normalizedInputRoot: Path,
        entry: PdfInventoryEntry,
        destination: Path,
    ): PdfBatchEntry {
        if (entry.processingStatus == PdfProcessingStatus.FAILED) {
            return entry.toBatchEntry(PdfBatchStatus.FAILED, entry.diagnostic ?: "PDF inventory failed")
        }
        val sha256 = entry.sha256
        val fileSizeBytes = entry.fileSizeBytes
        if (sha256 == null || fileSizeBytes == null) {
            return entry.toBatchEntry(PdfBatchStatus.FAILED, "Could not compute source size and SHA-256")
        }
        val priorOutput = reusableOutput(destination, entry, sha256, fileSizeBytes)
        if (priorOutput.reusable) {
            return entry.toBatchEntry(PdfBatchStatus.UNCHANGED)
        }

        return try {
            val sourcePath = normalizedInputRoot.resolve(entry.relativePath).normalize()
            require(
                sourcePath.startsWith(normalizedInputRoot) &&
                    Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS),
            ) {
                "Source path is not a regular file beneath the configured input directory"
            }
            val source =
                PdfSource(
                    path = sourcePath,
                    relativePath = entry.relativePath,
                    fileName = entry.fileName,
                    make = entry.make,
                    sha256 = sha256,
                    fileSizeBytes = fileSizeBytes,
                )
            val artifact = parser.parse(source)
            validateParserResult(artifact, source)
            writeAtomically(destination, PdfExtractionArtifactCodec.encode(artifact))
            val status =
                if (artifact.extraction.status == PdfExtractionStatus.NEEDS_REVIEW) {
                    PdfBatchStatus.NEEDS_REVIEW
                } else {
                    PdfBatchStatus.EXTRACTED
                }
            entry.toBatchEntry(status)
        } catch (exception: Exception) {
            val details = mutableListOf<String>()
            priorOutput.reason?.let { details += it }
            details += "${exception::class.simpleName}: ${exception.message ?: "No additional details"}"
            entry.toBatchEntry(PdfBatchStatus.FAILED, details.joinToString("; "))
        }
    }

    private fun reusableOutput(
        destination: Path,
        entry: PdfInventoryEntry,
        sha256: String,
        fileSizeBytes: Long,
    ): ReusableOutput {
        if (!Files.exists(destination)) return ReusableOutput(reusable = false)
        return try {
            val artifact = PdfExtractionArtifactCodec.decode(Files.readString(destination))
            val compatible =
                artifact.schemaVersion == PDF_EXTRACTION_SCHEMA_VERSION &&
                    artifact.source.documentId == entry.relativePath &&
                    artifact.source.relativePath == entry.relativePath &&
                    artifact.source.fileName == entry.fileName &&
                    artifact.source.make == entry.make &&
                    artifact.source.sha256 == sha256 &&
                    artifact.source.fileSizeBytes == fileSizeBytes &&
                    artifact.extraction.parserName == parser.name &&
                    artifact.extraction.parserVersion == parser.version &&
                    artifact.extraction.status != PdfExtractionStatus.FAILED
            if (compatible) {
                ReusableOutput(reusable = true)
            } else {
                ReusableOutput(
                    reusable = false,
                    reason = "Existing artifact hash, schema, or parser version is incompatible",
                )
            }
        } catch (exception: Exception) {
            ReusableOutput(
                reusable = false,
                reason = "Existing artifact could not be reused: ${exception.message ?: exception::class.simpleName}",
            )
        }
    }

    private fun validateParserResult(
        artifact: PdfExtractionArtifact,
        source: PdfSource,
    ) {
        PdfExtractionArtifactValidator.validate(artifact)
        require(artifact.extraction.status != PdfExtractionStatus.FAILED) {
            "Parser returned a failed extraction"
        }
        require(
            artifact.source.documentId == source.relativePath &&
                artifact.source.relativePath == source.relativePath &&
                artifact.source.fileName == source.fileName &&
                artifact.source.make == source.make &&
                artifact.source.sha256 == source.sha256 &&
                artifact.source.fileSizeBytes == source.fileSizeBytes,
        ) { "Parser artifact source identity does not match the discovered PDF" }
        require(artifact.extraction.parserName == parser.name && artifact.extraction.parserVersion == parser.version) {
            "Parser artifact identity does not match the configured parser"
        }
    }

    private fun outputPath(entry: PdfInventoryEntry): Path {
        val outputRootName =
            outputRoot
                .toAbsolutePath()
                .normalize()
                .fileName
                ?.toString()
                ?: error("Output directory must have a filename")
        val prefix = "$outputRootName/"
        require(entry.outputRelativePath.startsWith(prefix)) {
            "Inventory output path is not beneath the configured output directory: ${entry.outputRelativePath}"
        }
        val relativePath = entry.outputRelativePath.removePrefix(prefix)
        val normalizedPath =
            outputRoot
                .toAbsolutePath()
                .normalize()
                .resolve(relativePath)
                .normalize()
        require(normalizedPath.startsWith(outputRoot.toAbsolutePath().normalize())) {
            "Generated output path escapes the configured output directory"
        }
        return normalizedPath
    }

    private fun ensureUniqueOutputPaths(paths: List<Path>) {
        val duplicates =
            paths
                .map { it.toString().lowercase(Locale.ROOT) }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
        require(duplicates.isEmpty()) {
            "PDF output path collision(s): ${duplicates.keys.sorted().joinToString()}"
        }
    }

    private fun PdfInventoryEntry.toBatchEntry(
        status: PdfBatchStatus,
        diagnostic: String? = null,
    ) = PdfBatchEntry(
        relativePath = relativePath,
        sha256 = sha256,
        outputRelativePath = outputRelativePath.substringAfter('/'),
        status = status,
        diagnostic = diagnostic,
    )

    private fun encodeManifest(manifest: PdfBatchManifest): String = manifestJson.encodeToString(manifest) + "\n"

    private fun writeAtomically(
        destination: Path,
        content: String,
    ) {
        val parent = destination.parent ?: error("Output path must have a parent directory")
        val normalizedOutputRoot = outputRoot.toAbsolutePath().normalize()
        require(destination.toAbsolutePath().normalize().startsWith(normalizedOutputRoot)) {
            "Output path escapes the configured output directory"
        }
        var current = normalizedOutputRoot
        val relativeParent = normalizedOutputRoot.relativize(parent.toAbsolutePath().normalize())
        relativeParent.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "Output directory contains a symbolic link: $current"
            }
        }
        Files.createDirectories(parent)
        require(parent.toRealPath().startsWith(normalizedOutputRoot.toRealPath())) {
            "Output directory resolves outside the configured output root"
        }
        val temporaryFile = Files.createTempFile(parent, "${destination.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporaryFile,
                content,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(
                    temporaryFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private data class ReusableOutput(
        val reusable: Boolean,
        val reason: String? = null,
    )
}

object PdfBatchExtractorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: PdfBatchExtractorCli <pdf-input-directory> <output-directory>"
        }
        val result =
            PdfBatchExtractor(
                inputRoot = Path.of(args[0]),
                outputRoot = Path.of(args[1]),
                parser = PdfBoxDocumentParser(),
            ).run()
        result.summary.print()
        check(result.summary.failed == 0) {
            "PDF batch completed with ${result.summary.failed} failed document(s); see ${result.summary.manifestPath}"
        }
    }
}
