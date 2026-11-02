package com.sps23.aiworkflow.catalog.datamodel

import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACTION_SCHEMA_VERSION
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionFile
import com.sps23.aiworkflow.logging.WorkspaceLogs
import com.sps23.aiworkflow.recommendation.DataModelCatalogContext
import com.sps23.aiworkflow.workspace.WorkspaceBusyException
import com.sps23.aiworkflow.workspace.WorkspaceMutationGuard
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

enum class WorkspaceCatalogSourceType {
    DATA_MODEL,
    EXTRACTION,
    DIAGNOSTIC,
    LEGACY_RAW,
    INVALID,
}

class WorkspaceCatalogSourceRow internal constructor(
    val sourcePath: String,
    val linkedPdfPath: String?,
    val sourceType: WorkspaceCatalogSourceType,
    val included: Boolean,
    val errors: List<String>,
    internal val sourceHash: String?,
    internal val pdfHash: String?,
    internal val vehicle: CarDataModel?,
)

class WorkspaceCatalogInventory internal constructor(
    val rows: List<WorkspaceCatalogSourceRow>,
    internal val inputRoot: Path,
) {
    val includedCount: Int get() = rows.count { it.included }
    val excludedCount: Int get() = rows.count { !it.included }
    val canBuild: Boolean get() = includedCount > 0
    val errors: List<String> get() = rows.flatMap { row -> row.errors.map { "${row.sourcePath}: $it" } }
}

sealed interface WorkspaceCatalogBuildOutcome {
    data class Built(
        val catalogPath: Path,
        val vehicleCount: Int,
        val includedSources: List<String>,
        val context: DataModelCatalogContext,
    ) : WorkspaceCatalogBuildOutcome

    data object Declined : WorkspaceCatalogBuildOutcome

    data class Failed(
        val errors: List<String>,
        val catalogPath: Path,
    ) : WorkspaceCatalogBuildOutcome
}

class WorkspaceCatalogBuildService(
    private val paths: WorkspacePaths,
    private val mutationGuard: WorkspaceMutationGuard = WorkspaceMutationGuard(),
) {
    private val outputJson =
        Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }

    suspend fun refresh(): WorkspaceCatalogInventory =
        withContext(Dispatchers.IO) {
            val inputRoot =
                try {
                    paths.input.toAbsolutePath().normalize().also {
                        if (!Files.isDirectory(it.toRealPath())) throw IOException("Workspace input path is not a directory: $it")
                    }
                } catch (exception: IOException) {
                    throw IOException("Workspace input directory is unavailable: ${paths.input}", exception)
                }
            val jsonFiles =
                Files.walk(paths.input).use { entries ->
                    entries
                        .filter { isJsonFile(it) }
                        .sorted(compareBy { relativePath(inputRoot, it) })
                        .toList()
                }
            WorkspaceCatalogInventory(jsonFiles.map { inspectSource(inputRoot, it) }, inputRoot)
        }

    suspend fun build(
        inventory: WorkspaceCatalogInventory,
        replaceExistingConfirmed: Boolean,
    ): WorkspaceCatalogBuildOutcome =
        WorkspaceLogs(paths, WorkspaceLogs.credentials(System.getenv())).record(
            "builder",
            "Build catalog",
            buildJsonObject {
                put("inputDirectory", paths.input.toString())
                put("outputPath", paths.data.resolve("data-model-catalog.json").toString())
                put("replaceExistingConfirmed", replaceExistingConfirmed)
                put("model", kotlinx.serialization.json.JsonNull)
                put(
                    "sources",
                    buildJsonArray {
                        inventory.rows.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("sourcePath", row.sourcePath)
                                    put("linkedPdfPath", row.linkedPdfPath)
                                    put("sourceType", row.sourceType.name)
                                    put("included", row.included)
                                    put("sourceSha256", row.sourceHash)
                                    put("pdfSha256", row.pdfHash)
                                    put("errors", buildJsonArray { row.errors.forEach { add(it) } })
                                },
                            )
                        }
                    },
                )
            },
        ) { operation ->
            val result = buildWithLog(inventory, replaceExistingConfirmed, operation)
            operation.event(
                "RESULT",
                buildJsonObject {
                    when (result) {
                        is WorkspaceCatalogBuildOutcome.Built -> {
                            put("outcome", "BUILT")
                            put("catalogPath", result.catalogPath.toString())
                            put("vehicleCount", result.vehicleCount)
                            put("catalogJson", WorkspaceLogs.parseJson(result.context.promptJson))
                        }

                        is WorkspaceCatalogBuildOutcome.Failed -> {
                            put("outcome", "FAILED")
                            put("errors", buildJsonArray { result.errors.forEach { add(it) } })
                        }

                        WorkspaceCatalogBuildOutcome.Declined -> {
                            put("outcome", "DECLINED")
                        }
                    }
                },
            )
            if (result is WorkspaceCatalogBuildOutcome.Failed) {
                operation.failure(
                    result.errors.joinToString("\n"),
                    buildJsonObject {
                        put("errors", buildJsonArray { result.errors.forEach { add(it) } })
                    },
                )
            }
            result
        }

    private suspend fun buildWithLog(
        inventory: WorkspaceCatalogInventory,
        replaceExistingConfirmed: Boolean,
        operation: WorkspaceLogs.Operation,
    ): WorkspaceCatalogBuildOutcome {
        val target = paths.data.resolve("data-model-catalog.json")
        try {
            return mutationGuard.withMutationLockSuspending(paths) {
                buildLocked(inventory, target, replaceExistingConfirmed, operation)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: WorkspaceBusyException) {
            return WorkspaceCatalogBuildOutcome.Failed(listOf(exception.message ?: "Workspace is busy."), target)
        } catch (exception: IOException) {
            return WorkspaceCatalogBuildOutcome.Failed(
                listOf(exception.message ?: "Workspace catalog build failed."),
                target,
            )
        }
    }

    private suspend fun buildLocked(
        inventory: WorkspaceCatalogInventory,
        target: Path,
        replaceExistingConfirmed: Boolean,
        operation: WorkspaceLogs.Operation,
    ): WorkspaceCatalogBuildOutcome =
        withContext(Dispatchers.IO) {
            if (inventory.inputRoot != paths.input.toAbsolutePath().normalize()) {
                return@withContext WorkspaceCatalogBuildOutcome.Failed(
                    listOf("Workspace input directory changed; refresh the source inventory."),
                    target,
                )
            }
            val targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (targetExists && !replaceExistingConfirmed) return@withContext WorkspaceCatalogBuildOutcome.Declined
            if (Files.isSymbolicLink(target)) {
                return@withContext WorkspaceCatalogBuildOutcome.Failed(
                    listOf("Catalog output must not be a symbolic link: $target"),
                    target,
                )
            }
            if (!inventory.canBuild) {
                return@withContext WorkspaceCatalogBuildOutcome.Failed(
                    listOf("No catalog source files are included."),
                    target,
                )
            }
            if (inventory.errors.isNotEmpty()) {
                return@withContext WorkspaceCatalogBuildOutcome.Failed(
                    inventory.errors,
                    target,
                )
            }

            val included = inventory.rows.filter { it.included }
            val snapshotErrors = mutableListOf<String>()
            val cars = mutableListOf<CarDataModel>()
            included.forEach { row ->
                try {
                    val jsonPath = resolveInside(inventory.inputRoot, row.sourcePath)
                    val jsonBytes = Files.readAllBytes(jsonPath)
                    operation.event(
                        "SOURCE_SNAPSHOT",
                        buildJsonObject {
                            put("sourcePath", row.sourcePath)
                            put("sourceJson", WorkspaceLogs.parseJson(String(jsonBytes, StandardCharsets.UTF_8)))
                        },
                    )
                    if (sha256(jsonBytes) != row.sourceHash) {
                        snapshotErrors += "${row.sourcePath}: source changed after refresh; refresh and retry."
                    } else {
                        val pdfPath = requireNotNull(row.linkedPdfPath)
                        val pdfBytes = Files.readAllBytes(resolveInside(inventory.inputRoot, pdfPath))
                        if (sha256(pdfBytes) != row.pdfHash) {
                            snapshotErrors += "${row.sourcePath}: linked PDF changed after refresh; refresh and retry."
                        } else {
                            cars += requireNotNull(row.vehicle)
                        }
                    }
                } catch (exception: IOException) {
                    snapshotErrors += "${row.sourcePath}: cannot read source snapshot: ${exception.message}"
                }
            }
            if (snapshotErrors.isNotEmpty()) {
                return@withContext WorkspaceCatalogBuildOutcome.Failed(snapshotErrors, target)
            }

            val duplicateIds = cars.groupBy { it.id }.filterValues { it.size > 1 }
            if (duplicateIds.isNotEmpty()) {
                val duplicates =
                    duplicateIds.entries.map { (id, duplicates) ->
                        "Duplicate vehicle id '$id': ${duplicates.joinToString { it.sourcePath }}"
                    }
                return@withContext WorkspaceCatalogBuildOutcome.Failed(duplicates, target)
            }
            val artifact =
                try {
                    DataModelCatalogArtifact.from(DataModelCatalog(cars))
                } catch (exception: IllegalArgumentException) {
                    return@withContext WorkspaceCatalogBuildOutcome.Failed(
                        listOf("Catalog source linking failed: ${exception.message}"),
                        target,
                    )
                }
            val validatedContext =
                try {
                    DataModelCatalogContext.fromWorkspaceArtifact(artifact, paths.input)
                } catch (exception: IllegalArgumentException) {
                    return@withContext WorkspaceCatalogBuildOutcome.Failed(
                        listOf("Catalog cannot be loaded by Assistant: ${exception.message}"),
                        target,
                    )
                }
            val changes = verifySnapshotUnchanged(inventory, included)
            if (changes.isNotEmpty()) return@withContext WorkspaceCatalogBuildOutcome.Failed(changes, target)

            val serialized = outputJson.encodeToString(DataModelCatalogArtifact.serializer(), artifact) + "\n"
            operation.event(
                "CATALOG_CANDIDATE",
                buildJsonObject {
                    put("catalogJson", WorkspaceLogs.parseJson(serialized))
                    put("outputPath", target.toString())
                },
            )
            try {
                writeStrictlyAtomically(target, serialized)
                WorkspaceCatalogBuildOutcome.Built(
                    catalogPath = target,
                    vehicleCount = artifact.vehicles.size,
                    includedSources = included.map { it.sourcePath },
                    context = validatedContext,
                )
            } catch (exception: IOException) {
                WorkspaceCatalogBuildOutcome.Failed(
                    listOf("Cannot publish workspace catalog at $target: ${exception.message}"),
                    target,
                )
            } catch (exception: IllegalStateException) {
                WorkspaceCatalogBuildOutcome.Failed(
                    listOf("Published workspace catalog could not be activated: ${exception.message}"),
                    target,
                )
            }
        }

    private fun inspectSource(
        inputRoot: Path,
        jsonPath: Path,
    ): WorkspaceCatalogSourceRow {
        val relativeJson = relativePath(inputRoot, jsonPath)
        if (relativeJson.endsWith(".raw.json", ignoreCase = true)) {
            return WorkspaceCatalogSourceRow(
                relativeJson,
                null,
                WorkspaceCatalogSourceType.LEGACY_RAW,
                false,
                emptyList(),
                null,
                null,
                null,
            )
        }
        if (Files.isSymbolicLink(jsonPath)) {
            return invalidRow(relativeJson, "JSON source is a symbolic link.")
        }
        if (!Files.isRegularFile(jsonPath, LinkOption.NOFOLLOW_LINKS)) {
            return invalidRow(
                relativeJson,
                "JSON source is not a regular file.",
            )
        }

        return try {
            val jsonBytes = Files.readAllBytes(jsonPath)
            val text = jsonBytes.toString(Charsets.UTF_8)
            val root =
                try {
                    Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
                } catch (exception: SerializationException) {
                    return invalidRow(relativeJson, "Invalid JSON.")
                }
            if (root == null) return invalidRow(relativeJson, "JSON root must be an object.")
            if ((root["kind"] as? JsonPrimitive)?.contentOrNull == "pdf-extraction-diagnostic") {
                return WorkspaceCatalogSourceRow(
                    relativeJson,
                    null,
                    WorkspaceCatalogSourceType.DIAGNOSTIC,
                    false,
                    emptyList(),
                    sha256(jsonBytes),
                    null,
                    null,
                )
            }

            val isExtraction = "schemaVersion" in root
            val vehicle = CatalogSourceParser.parse(text, relativeJson)
            val linkedPath =
                if (isExtraction) extractionPdf(inputRoot, jsonPath, text) else legacyPdf(inputRoot, jsonPath)
            val pdfBytes = Files.readAllBytes(linkedPath)
            validatePdf(linkedPath, pdfBytes)
            val relativePdf = relativePath(inputRoot, linkedPath)
            WorkspaceCatalogSourceRow(
                sourcePath = relativeJson,
                linkedPdfPath = relativePdf,
                sourceType = if (isExtraction) WorkspaceCatalogSourceType.EXTRACTION else WorkspaceCatalogSourceType.DATA_MODEL,
                included = true,
                errors = emptyList(),
                sourceHash = sha256(jsonBytes),
                pdfHash = sha256(pdfBytes),
                vehicle = vehicle.copy(sourcePath = relativeJson, sourcePdfPath = relativePdf),
            )
        } catch (exception: DataModelParseException) {
            invalidRow(relativeJson, exception.message ?: "Invalid catalog source.")
        } catch (exception: IOException) {
            invalidRow(relativeJson, exception.message ?: "Cannot read catalog source.")
        } catch (exception: SerializationException) {
            invalidRow(relativeJson, "Invalid extraction provenance: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            invalidRow(relativeJson, exception.message ?: "Invalid catalog source.")
        }
    }

    private fun extractionPdf(
        inputRoot: Path,
        jsonPath: Path,
        text: String,
    ): Path {
        val extraction =
            try {
                PdfExtractionFile.decode(text)
            } catch (exception: SerializationException) {
                throw DataModelParseException(
                    "${relativePath(inputRoot, jsonPath)}: invalid extraction envelope.",
                    exception,
                )
            }
        if (extraction.schemaVersion != PDF_EXTRACTION_SCHEMA_VERSION) {
            throw DataModelParseException(
                "${
                    relativePath(
                        inputRoot,
                        jsonPath,
                    )
                }: unsupported extraction schema version.",
            )
        }
        val fileName = extraction.provenance.pdfFileName
        if (fileName.isBlank() || fileName != Path.of(fileName).fileName.toString() ||
            fileName.contains('/') || fileName.contains('\\') || fileName.contains(':') ||
            !fileName.endsWith(".pdf", ignoreCase = true)
        ) {
            throw DataModelParseException("${relativePath(inputRoot, jsonPath)}: unsafe provenance PDF filename.")
        }
        val hash = extraction.provenance.pdfSha256
        if (!hash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            throw DataModelParseException("${relativePath(inputRoot, jsonPath)}: invalid provenance PDF SHA-256.")
        }
        val pdf = resolveSiblingInside(inputRoot, jsonPath.parent, fileName)
        val bytes = Files.readAllBytes(pdf)
        validatePdf(pdf, bytes)
        if (!sha256(bytes).equals(hash, ignoreCase = true)) {
            throw DataModelParseException(
                "${
                    relativePath(
                        inputRoot,
                        jsonPath,
                    )
                }: provenance PDF hash does not match $fileName.",
            )
        }
        return pdf
    }

    private fun legacyPdf(
        inputRoot: Path,
        jsonPath: Path,
    ): Path {
        val stem = jsonPath.fileName.toString().substringBeforeLast('.', jsonPath.fileName.toString())
        val matches =
            Files.list(jsonPath.parent).use { entries ->
                entries
                    .filter { it.fileName.toString().endsWith(".pdf", ignoreCase = true) }
                    .filter { it.fileName.toString().substringBeforeLast('.', it.fileName.toString()) == stem }
                    .toList()
            }
        if (matches.size != 1) {
            throw DataModelParseException(
                "${
                    relativePath(
                        inputRoot,
                        jsonPath,
                    )
                }: expected exactly one sibling PDF named $stem.pdf; found ${matches.size}.",
            )
        }
        val pdf = matches.single()
        if (Files.isSymbolicLink(pdf)) {
            val resolved = pdf.toRealPath()
            if (!resolved.startsWith(inputRoot.toRealPath())) {
                throw DataModelParseException("${relativePath(inputRoot, jsonPath)}: linked PDF escapes input/.")
            }
            return pdf
        }
        return pdf
    }

    private fun verifySnapshotUnchanged(
        inventory: WorkspaceCatalogInventory,
        included: List<WorkspaceCatalogSourceRow>,
    ): List<String> {
        val errors = mutableListOf<String>()
        included.forEach { row ->
            try {
                val json = Files.readAllBytes(resolveInside(inventory.inputRoot, row.sourcePath))
                val pdf = Files.readAllBytes(resolveInside(inventory.inputRoot, requireNotNull(row.linkedPdfPath)))
                if (sha256(json) != row.sourceHash || sha256(pdf) != row.pdfHash) {
                    errors += "${row.sourcePath}: source changed during catalog build; refresh and retry."
                }
            } catch (exception: IOException) {
                errors += "${row.sourcePath}: source changed during catalog build: ${exception.message}"
            }
        }
        return errors
    }

    private fun resolveSiblingInside(
        inputRoot: Path,
        parent: Path,
        fileName: String,
    ): Path {
        val root = inputRoot.toRealPath()
        val candidate = parent.resolve(fileName).normalize()
        if (!candidate.startsWith(inputRoot)) throw IOException("PDF path escapes input/: $candidate")
        if (!Files.isRegularFile(candidate)) throw IOException("Linked PDF is missing or not a regular file: $candidate")
        val resolved = candidate.toRealPath()
        if (!resolved.startsWith(root)) throw IOException("Linked PDF resolves outside input/: $candidate")
        return candidate
    }

    private fun resolveInside(
        inputRoot: Path,
        relativePath: String,
    ): Path {
        val relative = Path.of(relativePath)
        if (relative.isAbsolute || relativePath.contains('\\') || relativePath.contains(':')) {
            throw IOException("Unsafe workspace-relative path: $relativePath")
        }
        val candidate = inputRoot.resolve(relative).normalize()
        if (!candidate.startsWith(inputRoot)) throw IOException("Path escapes workspace input/: $relativePath")
        if (!Files.isRegularFile(candidate)) throw IOException("Workspace source is missing: $candidate")
        val resolved = candidate.toRealPath()
        if (!resolved.startsWith(inputRoot.toRealPath())) throw IOException("Workspace source resolves outside input/: $candidate")
        return resolved
    }

    private fun validatePdf(
        path: Path,
        bytes: ByteArray,
    ) {
        if (!Files.isRegularFile(path)) throw IOException("Linked PDF is not a regular file: $path")
        if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) {
            throw DataModelParseException("Linked source is not a valid PDF: $path")
        }
    }

    private fun writeStrictlyAtomically(
        target: Path,
        content: String,
    ) {
        Files.createDirectories(target.parent)
        val temporary = target.parent.resolve(".catalog-${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (exception: AtomicMoveNotSupportedException) {
                throw IOException("Atomic catalog replacement is not supported on this filesystem.", exception)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isJsonFile(path: Path): Boolean =
        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            path.fileName.toString().endsWith(".json", ignoreCase = true) &&
            !path.fileName.toString().endsWith(".tmp", ignoreCase = true) &&
            !path.fileName.toString().contains(".tmp.")

    private fun invalidRow(
        sourcePath: String,
        message: String,
    ) = WorkspaceCatalogSourceRow(
        sourcePath,
        null,
        WorkspaceCatalogSourceType.INVALID,
        true,
        listOf(message),
        null,
        null,
        null,
    )

    private fun relativePath(
        root: Path,
        path: Path,
    ): String = Normalizer.normalize(root.relativize(path).joinToString("/"), Normalizer.Form.NFC)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
