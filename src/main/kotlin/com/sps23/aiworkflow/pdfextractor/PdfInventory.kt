package com.sps23.aiworkflow.pdfextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

@Serializable
data class PdfInventoryManifest(
    val schemaVersion: Int = 1,
    val sourceRoot: String,
    val outputRoot: String,
    val documents: List<PdfInventoryEntry>,
)

@Serializable
data class PdfInventoryEntry(
    val relativePath: String,
    val fileName: String,
    val make: String,
    val fileSizeBytes: Long?,
    val sha256: String?,
    val pageCount: Int?,
    val contentType: PdfContentType,
    val outputRelativePath: String,
    val processingStatus: PdfProcessingStatus,
    val diagnostic: String? = null,
)

@Serializable
enum class PdfContentType {
    TEXT_BASED,
    TEXT_AND_IMAGES,
    IMAGE_ONLY,
    NO_EXTRACTABLE_CONTENT,
    UNKNOWN,
}

@Serializable
enum class PdfProcessingStatus {
    QUEUED,
    NEEDS_REVIEW,
    FAILED,
}

object PdfInventory {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    fun createManifest(
        inputRoot: Path,
        outputRoot: Path,
    ): PdfInventoryManifest {
        require(Files.isDirectory(inputRoot)) { "PDF input directory does not exist: $inputRoot" }
        val normalizedInputRoot = inputRoot.toAbsolutePath().normalize()
        val outputRootName = outputRoot.fileName?.toString() ?: "car-pricelists-extracted"
        val documents =
            Files.walk(normalizedInputRoot).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension.equals("pdf", ignoreCase = true) }
                    .sorted()
                    .map { file -> inspect(normalizedInputRoot, file, outputRootName) }
                    .toList()
            }
        ensureUniqueOutputPaths(documents)
        return PdfInventoryManifest(
            sourceRoot = inputRoot.fileName?.toString() ?: inputRoot.name,
            outputRoot = outputRootName,
            documents = documents,
        )
    }

    fun writeManifest(
        manifest: PdfInventoryManifest,
        destination: Path,
    ) {
        val normalizedDestination = destination.toAbsolutePath().normalize()
        val parent = normalizedDestination.parent ?: error("Manifest destination must have a parent directory")
        Files.createDirectories(parent)
        val temporaryFile = Files.createTempFile(parent, "${normalizedDestination.fileName}.", ".tmp")
        try {
            Files.writeString(temporaryFile, json.encodeToString(manifest) + "\n")
            try {
                Files.move(
                    temporaryFile,
                    normalizedDestination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, normalizedDestination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    fun encodeManifest(manifest: PdfInventoryManifest): String = json.encodeToString(manifest)

    private fun inspect(
        inputRoot: Path,
        file: Path,
        outputRootName: String,
    ): PdfInventoryEntry {
        val relativePath = inputRoot.relativize(file).toString().replace(file.fileSystem.separator, "/")
        val make =
            file.parent
                ?.let { inputRoot.relativize(it).toString().replace(file.fileSystem.separator, "/") }
                .orEmpty()
        val outputRelativePath = outputRelativePath(outputRootName, make, file)
        var fileSize: Long? = null
        var sha256: String? = null
        return try {
            fileSize = Files.size(file)
            sha256 = sha256(file)
            Loader.loadPDF(file.toFile()).use { document ->
                val hasText = PDFTextStripper().getText(document).any { !it.isWhitespace() }
                val hasImages =
                    document.pages.any { page ->
                        page.resources?.let { resources ->
                            containsImage(resources, Collections.newSetFromMap(IdentityHashMap()))
                        } ?: false
                    }
                val contentType =
                    when {
                        hasText && hasImages -> PdfContentType.TEXT_AND_IMAGES
                        hasText -> PdfContentType.TEXT_BASED
                        hasImages -> PdfContentType.IMAGE_ONLY
                        else -> PdfContentType.NO_EXTRACTABLE_CONTENT
                    }
                PdfInventoryEntry(
                    relativePath = relativePath,
                    fileName = file.fileName.toString(),
                    make = make,
                    fileSizeBytes = fileSize,
                    sha256 = sha256,
                    pageCount = document.numberOfPages,
                    contentType = contentType,
                    outputRelativePath = outputRelativePath,
                    processingStatus =
                        if (hasText) PdfProcessingStatus.QUEUED else PdfProcessingStatus.NEEDS_REVIEW,
                )
            }
        } catch (exception: Exception) {
            PdfInventoryEntry(
                relativePath = relativePath,
                fileName = file.fileName.toString(),
                make = make,
                fileSizeBytes = fileSize,
                sha256 = sha256,
                pageCount = null,
                contentType = PdfContentType.UNKNOWN,
                outputRelativePath = outputRelativePath,
                processingStatus = PdfProcessingStatus.FAILED,
                diagnostic = exception::class.simpleName ?: "PDF processing error",
            )
        }
    }

    private fun containsImage(
        resources: org.apache.pdfbox.pdmodel.PDResources,
        visitedForms: MutableSet<PDFormXObject>,
    ): Boolean =
        resources.xObjectNames.any { name ->
            when (val xObject = resources.getXObject(name)) {
                is PDImageXObject -> {
                    true
                }

                is PDFormXObject -> {
                    visitedForms.add(xObject) &&
                        xObject.resources?.let { containsImage(it, visitedForms) } == true
                }

                else -> {
                    false
                }
            }
        }

    private fun outputRelativePath(
        outputRootName: String,
        make: String,
        file: Path,
    ): String {
        val safeStem =
            file.nameWithoutExtension
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-', '.', '_')
                .ifEmpty { "document" }
        val relativeName = file.parent.relativize(file).toString()
        val suffix = sha256(relativeName.toByteArray()).take(12)
        return listOf(outputRootName, make, "$safeStem-$suffix.json").joinToString("/")
    }

    private fun ensureUniqueOutputPaths(documents: List<PdfInventoryEntry>) {
        val duplicates =
            documents
                .groupBy { it.outputRelativePath.lowercase() }
                .filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "PDF output path collision(s): ${duplicates.keys.sorted().joinToString()}"
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
}

object PdfInventoryCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) {
            "Usage: PdfInventoryCli <pdf-input-directory> <manifest-json-path>"
        }
        val inputRoot = Path.of(args[0])
        val manifestPath = Path.of(args[1])
        val outputRoot = manifestPath.parent ?: error("Manifest path must include a parent directory")
        val manifest = PdfInventory.createManifest(inputRoot, outputRoot)
        PdfInventory.writeManifest(manifest, manifestPath)
        val failed = manifest.documents.count { it.processingStatus == PdfProcessingStatus.FAILED }
        val needsReview = manifest.documents.count { it.processingStatus == PdfProcessingStatus.NEEDS_REVIEW }
        println(
            "PDF inventory: ${manifest.documents.size} documents, $needsReview needs review, " +
                "$failed failed; wrote $manifestPath",
        )
    }
}
