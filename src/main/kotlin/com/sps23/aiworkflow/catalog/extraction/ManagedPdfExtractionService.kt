package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.workspace.WorkspaceBusyException
import com.sps23.aiworkflow.workspace.WorkspaceMutationGuard
import com.sps23.aiworkflow.workspace.WorkspacePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

sealed interface ManagedPdfExtractionOutcome {
    data class Written(
        val pdfPath: Path,
        val extraction: PdfExtractionOutcome.Written,
        val warnings: List<String> = emptyList(),
    ) : ManagedPdfExtractionOutcome

    data class Declined(
        val pdfPath: Path,
    ) : ManagedPdfExtractionOutcome

    data class Failed(
        val kind: FailureKind,
        val message: String,
        val pdfPath: Path? = null,
        val extractionPath: Path? = null,
        val diagnosticPath: Path? = null,
        val previousExtractionAvailable: Boolean = false,
        val violations: List<Violation> = emptyList(),
        val metadata: WorkflowMetadata? = null,
        val rawText: String? = null,
    ) : ManagedPdfExtractionOutcome
}

@Serializable
data class PdfExtractionDiagnostic(
    val kind: String = "pdf-extraction-diagnostic",
    val schemaVersion: Int = 1,
    val provenance: DiagnosticProvenance,
    val failure: DiagnosticFailure,
    val rawText: String,
) {
    companion object {
        private val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
                explicitNulls = false
            }

        fun encode(diagnostic: PdfExtractionDiagnostic): String = json.encodeToString(diagnostic) + "\n"
    }
}

@Serializable
data class DiagnosticProvenance(
    val pdfFileName: String,
    val pdfSha256: String,
    val model: String,
    val extractedAt: String,
)

@Serializable
data class DiagnosticFailure(
    val kind: String,
    val message: String,
    val violations: List<DiagnosticViolation>,
)

@Serializable
data class DiagnosticViolation(
    val rule: String,
    val severity: String,
    val message: String,
)

class ManagedPdfExtractionService(
    private val paths: WorkspacePaths,
    private val extractionService: PdfExtractionService,
    private val mutationGuard: WorkspaceMutationGuard = WorkspaceMutationGuard(),
    private val clock: Clock = Clock.systemUTC(),
    private val logs: com.sps23.aiworkflow.logging.WorkspaceLogs =
        com.sps23.aiworkflow.logging
            .WorkspaceLogs(paths),
) {
    suspend fun extract(
        selectedPdf: Path,
        confirmReextract: suspend (Path) -> Boolean,
    ): ManagedPdfExtractionOutcome {
        logs.info("PDF import/extraction requested: $selectedPdf")
        val outcome =
            try {
                mutationGuard.withMutationLockSuspending(paths) {
                    extractLocked(selectedPdf, confirmReextract)
                }
            } catch (exception: CancellationException) {
                logs.info("PDF import/extraction cancelled: $selectedPdf")
                throw exception
            } catch (exception: WorkspaceBusyException) {
                ManagedPdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, exception.message ?: "Workspace is busy.")
            } catch (exception: IOException) {
                ManagedPdfExtractionOutcome.Failed(
                    FailureKind.CONFIGURATION,
                    exception.message ?: "Workspace operation failed.",
                )
            }
        when (outcome) {
            is ManagedPdfExtractionOutcome.Failed -> {
                logs.error("PDF import/extraction failed: ${outcome.message}")
            }

            is ManagedPdfExtractionOutcome.Declined -> {
                logs.info("PDF re-extraction declined: ${outcome.pdfPath}")
            }

            is ManagedPdfExtractionOutcome.Written -> {
                logs.info("PDF extraction saved: ${outcome.extraction.file}; imported PDF: ${outcome.pdfPath}")
                outcome.warnings.forEach { logs.error(it) }
            }
        }
        return outcome
    }

    private suspend fun extractLocked(
        selectedPdf: Path,
        confirmReextract: suspend (Path) -> Boolean,
    ): ManagedPdfExtractionOutcome {
        val snapshot =
            try {
                withContext(Dispatchers.IO) {
                    if (!Files.isRegularFile(selectedPdf)) throw IOException("Selected PDF is not a regular file: $selectedPdf")
                    if (selectedPdf.fileName?.toString()?.endsWith(".pdf", ignoreCase = true) != true) {
                        throw IOException("Selected file must have a .pdf extension: $selectedPdf")
                    }
                    if (Files.size(selectedPdf) > GEMINI_PDF_MAX_BYTES) {
                        throw IOException("PDF exceeds the ${GEMINI_PDF_MAX_BYTES / (1024 * 1024)} MB size limit.")
                    }
                    Files.readAllBytes(selectedPdf)
                }
            } catch (exception: IOException) {
                return ManagedPdfExtractionOutcome.Failed(
                    FailureKind.CONFIGURATION,
                    "Cannot read selected PDF: ${exception.message}",
                )
            }
        try {
            GeminiPdfClient.checkPdf(snapshot)
        } catch (exception: com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException) {
            return ManagedPdfExtractionOutcome.Failed(exception.kind, exception.message ?: exception.kind.name)
        }
        val hash = PdfExtractionService.sha256(snapshot)
        val existing =
            try {
                withContext(Dispatchers.IO) { findIdenticalPdf(hash, snapshot.size.toLong()) }
            } catch (exception: IOException) {
                return ManagedPdfExtractionOutcome.Failed(
                    FailureKind.CONFIGURATION,
                    "Cannot inspect workspace PDFs: ${exception.message}",
                )
            }
        val imported =
            if (existing != null) {
                if (!confirmReextract(existing)) return ManagedPdfExtractionOutcome.Declined(existing)
                existing
            } else {
                try {
                    withContext(Dispatchers.IO) { importSnapshot(selectedPdf.fileName.toString(), snapshot) }
                } catch (exception: IOException) {
                    return ManagedPdfExtractionOutcome.Failed(
                        FailureKind.CONFIGURATION,
                        "Cannot import PDF into workspace: ${exception.message}",
                    )
                }
            }
        val extractionPath = imported.resolveSibling("${imported.fileName.toString().substringBeforeLast('.')}.json")
        val diagnosticPath =
            imported.resolveSibling("${imported.fileName.toString().substringBeforeLast('.')}.raw.json")
        try {
            ensureNoSymlink(imported, extractionPath, diagnosticPath)
        } catch (exception: IOException) {
            return ManagedPdfExtractionOutcome.Failed(
                FailureKind.CONFIGURATION,
                exception.message ?: "Workspace artifact path is unsafe.",
                imported,
                extractionPath,
            )
        }
        val hadSuccessfulExtraction = Files.isRegularFile(extractionPath, LinkOption.NOFOLLOW_LINKS)
        val outcome =
            extractionService.extractSnapshot(
                pdfBytes = snapshot,
                pdfFileName = imported.fileName.toString(),
                outputDir = imported.parent,
                overwrite = { target -> existing != null && target == extractionPath },
                persistRawResponse = false,
            )
        return when (outcome) {
            is PdfExtractionOutcome.Written -> {
                val warnings = mutableListOf<String>()
                try {
                    withContext(Dispatchers.IO) {
                        if (Files.isSymbolicLink(diagnosticPath)) {
                            throw IOException("Raw diagnostic is a symbolic link: $diagnosticPath")
                        }
                        Files.deleteIfExists(diagnosticPath)
                    }
                } catch (exception: IOException) {
                    warnings += "Extraction succeeded, but obsolete diagnostic could not be removed: ${exception.message}"
                }
                ManagedPdfExtractionOutcome.Written(imported, outcome, warnings)
            }

            is PdfExtractionOutcome.Declined -> {
                ManagedPdfExtractionOutcome.Declined(imported)
            }

            is PdfExtractionOutcome.Failed -> {
                var savedDiagnostic: Path? = null
                var persistenceFailure: String? = null
                val rawText = outcome.rawText
                if (!rawText.isNullOrBlank()) {
                    try {
                        withContext(Dispatchers.IO) {
                            writeDiagnostic(
                                diagnosticPath,
                                PdfExtractionDiagnostic(
                                    provenance =
                                        DiagnosticProvenance(
                                            pdfFileName = imported.fileName.toString(),
                                            pdfSha256 = hash,
                                            model = outcome.model ?: "unknown",
                                            extractedAt = Instant.now(clock).toString(),
                                        ),
                                    failure =
                                        DiagnosticFailure(
                                            kind = outcome.kind.name,
                                            message = outcome.message,
                                            violations =
                                                outcome.violations.map {
                                                    DiagnosticViolation(it.rule, it.severity.name, it.message)
                                                },
                                        ),
                                    rawText = rawText,
                                ),
                            )
                        }
                        savedDiagnostic = diagnosticPath
                    } catch (exception: IOException) {
                        persistenceFailure = " Cannot save raw diagnostic: ${exception.message}"
                    }
                }
                ManagedPdfExtractionOutcome.Failed(
                    kind = outcome.kind,
                    message = outcome.message + (persistenceFailure ?: ""),
                    pdfPath = imported,
                    extractionPath = extractionPath,
                    diagnosticPath = savedDiagnostic,
                    previousExtractionAvailable = hadSuccessfulExtraction,
                    violations = outcome.violations,
                    metadata = outcome.metadata,
                    rawText = rawText,
                )
            }
        }
    }

    private fun findIdenticalPdf(
        hash: String,
        size: Long,
    ): Path? {
        val inputRoot = paths.input.toRealPath()
        return Files.walk(paths.input).use { entries ->
            entries
                .filter { candidate ->
                    !Files.isSymbolicLink(candidate) &&
                        Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                        candidate.fileName.toString().endsWith(".pdf", ignoreCase = true) &&
                        Files.size(candidate) == size
                }.map { it.toRealPath() }
                .sorted(Comparator.comparing { inputRoot.relativize(it).toString() })
                .filter { candidate ->
                    if (!candidate.startsWith(inputRoot)) throw IOException("Workspace PDF resolves outside input/: $candidate")
                    sha256(Files.readAllBytes(candidate)) == hash
                }.findFirst()
                .orElse(null)
        }
    }

    private fun importSnapshot(
        originalName: String,
        bytes: ByteArray,
    ): Path {
        val inputRoot = paths.input.toRealPath()
        val occupiedNames =
            Files.walk(paths.input).use { entries ->
                entries
                    .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }
        val originalStem = originalName.substringBeforeLast('.', originalName).ifBlank { "brochure" }
        val extension = originalName.substringAfterLast('.', "pdf")
        val fileName =
            generateSequence(1) { it + 1 }
                .map { suffix -> if (suffix == 1) "$originalStem.$extension" else "$originalStem-$suffix.$extension" }
                .first { candidate ->
                    val stem = candidate.substringBeforeLast('.')
                    candidate !in occupiedNames && "$stem.json" !in occupiedNames && "$stem.raw.json" !in occupiedNames
                }
        val target = inputRoot.resolve(fileName)
        val temporary = Files.createTempFile(inputRoot, ".pdf-import-", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun ensureNoSymlink(vararg files: Path) {
        files.forEach { path ->
            if (Files.isSymbolicLink(path)) throw IOException("Workspace artifact path is a symbolic link: $path")
        }
    }

    private fun writeDiagnostic(
        target: Path,
        diagnostic: PdfExtractionDiagnostic,
    ) {
        val temporary = Files.createTempFile(target.parent, ".raw-diagnostic-", ".tmp")
        try {
            Files.writeString(
                temporary,
                PdfExtractionDiagnostic.encode(diagnostic),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
