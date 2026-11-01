package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.elapsedMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

sealed interface PdfExtractionOutcome {
    /** Valid extraction written to [file]; [softViolations] are informational. */
    data class Written(
        val file: Path,
        val extraction: PdfExtractionFile,
        val usage: GeminiUsage,
        val latencyMillis: Long,
        val softViolations: List<Violation>,
        val metadata: WorkflowMetadata? = null,
    ) : PdfExtractionOutcome

    /** [file] existed and the caller declined to overwrite it; Gemini was not called. */
    data class Declined(
        val file: Path,
    ) : PdfExtractionOutcome

    /**
     * Nothing was written to `<stem>.json`. [rawPath] is `<stem>.raw.json` when the model returned text.
     * [kind] is [FailureKind.INVALID_RESPONSE] for hard requirement violations.
     */
    data class Failed(
        val kind: FailureKind,
        val message: String,
        val violations: List<Violation> = emptyList(),
        val rawPath: Path? = null,
        val usage: GeminiUsage? = null,
        val metadata: WorkflowMetadata? = null,
    ) : PdfExtractionOutcome
}

/**
 * One PDF -> validated `<stem>.json` extraction envelope in [PdfExtractionOutcome.Written.file]'s directory.
 *
 * Order: read + PDF gate -> overwrite check (before any paid call) -> model lookup -> Gemini ->
 * hard requirements R1-R13/R18 -> atomic write. Never writes `<stem>.json` for invalid output.
 */
class PdfExtractionService(
    private val client: PdfExtractionClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun extract(
        pdf: Path,
        outputDir: Path,
        overwrite: suspend (Path) -> Boolean,
    ): PdfExtractionOutcome {
        val started = System.nanoTime()
        val stages = mutableListOf<StageMetrics>()
        var stage = GeminiPdfClient.STAGE
        var stageStarted = started
        var request: RequestMetrics? = null
        var httpStatus: Int? = null

        suspend fun run(): PdfExtractionOutcome {
            val bytes =
                try {
                    if (!pdf.isRegularFile()) return PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "PDF not found: $pdf")
                    withContext(Dispatchers.IO) { pdf.readBytes() }
                } catch (e: IOException) {
                    return PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "Cannot read $pdf: ${e.message}")
                }
            val stem = pdf.nameWithoutExtension
            val target = outputDir.resolve("$stem.json")
            val rawTarget = outputDir.resolve("$stem.raw.json")

            return try {
                GeminiPdfClient.checkPdf(bytes)
                if (target.exists() && !overwrite(target)) return PdfExtractionOutcome.Declined(target)

                stage = "Model metadata"
                stageStarted = System.nanoTime()
                val maxOutputTokens = minOf(GEMINI_PDF_MAX_OUTPUT_TOKENS, client.outputTokenLimit())
                stages += StageMetrics(stage, "SUCCEEDED", elapsedMillis(stageStarted))
                stage = GeminiPdfClient.STAGE
                stageStarted = System.nanoTime()
                val result =
                    client.extract(
                        bytes,
                        PdfExtractionPrompts.systemPrompt(),
                        PdfExtractionPrompts.userPrompt(),
                        ExtractedVehicle.jsonSchema,
                        maxOutputTokens,
                    )
                request = result.metrics
                val report = ExtractionRequirements.validate(result.text, result.finishReason)
                val vehicle = report.vehicle
                if (!report.passed || vehicle == null) {
                    return PdfExtractionOutcome.Failed(
                        FailureKind.INVALID_RESPONSE,
                        "Model output violates ${report.hardViolations.size} hard requirement(s); $target was not written.",
                        report.violations,
                        writeRaw(rawTarget, result.text),
                        result.usage,
                    )
                }
                val extraction =
                    PdfExtractionFile(
                        provenance =
                            ExtractionProvenance(
                                pdfFileName = pdf.name,
                                pdfSha256 = sha256(bytes),
                                model = result.modelVersion ?: client.modelId,
                                extractedAt = Instant.now(clock).toString(),
                                inputTokens = result.usage.promptTokens,
                                outputTokens =
                                    result.usage.outputTokens.takeIf {
                                        result.usage.candidatesTokens != null || result.usage.thoughtsTokens != null
                                    },
                            ),
                        vehicle = vehicle,
                    )
                writeAtomically(target, PdfExtractionFile.encode(extraction))
                PdfExtractionOutcome.Written(target, extraction, result.usage, result.latencyMillis, report.softViolations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenRouterWorkflowException) {
                request = e.requestMetrics ?: request
                httpStatus = e.diagnostics?.httpStatus
                PdfExtractionOutcome.Failed(e.kind, e.message ?: e.kind.name, rawPath = e.rawOutput?.let { writeRaw(rawTarget, it) })
            } catch (e: IOException) {
                PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "Cannot write to $outputDir: ${e.message}")
            }
        }

        val outcome = run()
        if (outcome is PdfExtractionOutcome.Declined) return outcome
        val failure = (outcome as? PdfExtractionOutcome.Failed)?.kind
        stages +=
            StageMetrics(
                stage,
                if (failure == null) "SUCCEEDED" else "FAILED",
                elapsedMillis(stageStarted),
                failure?.name,
                request,
                request?.httpStatus ?: httpStatus,
            )
        val metadata =
            WorkflowMetadata(
                elapsedMillis = elapsedMillis(started),
                stages = stages.toList(),
                notes =
                    "Metadata only; null means unavailable. ${client.metadataNotes} " +
                        "For native PDF requests, inputUtf8Bytes includes PDF bytes plus UTF-8 prompts and schema, " +
                        "not base64 or HTTP overhead. " +
                        "Output tokens include candidates plus thinking; " +
                        "reasoning and cached input are subsets, not additional totals. " +
                        "Request time includes serialization, transport and response processing; " +
                        "output tokens per second is whole-request throughput, not generation speed. " +
                        "TTFT, generation speed, cache-write tokens and cost remain unavailable unless reported.",
            )
        return when (outcome) {
            is PdfExtractionOutcome.Written -> outcome.copy(metadata = metadata)
            is PdfExtractionOutcome.Failed -> outcome.copy(metadata = metadata)
            is PdfExtractionOutcome.Declined -> outcome
        }
    }

    private suspend fun writeRaw(
        rawTarget: Path,
        text: String,
    ): Path? =
        text.takeIf { it.isNotBlank() }?.let {
            withContext(Dispatchers.IO) {
                rawTarget.parent?.createDirectories()
                rawTarget.writeText(it)
            }
            rawTarget
        }

    private suspend fun writeAtomically(
        target: Path,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val dir = target.toAbsolutePath().parent.createDirectories()
        val temp = dir.resolve(".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeText(content)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
