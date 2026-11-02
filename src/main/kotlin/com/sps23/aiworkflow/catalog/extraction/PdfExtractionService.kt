package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.logging.WorkspaceLogs
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.elapsedMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
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
        val rawText: String? = null,
        val model: String? = null,
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
    private val logs: WorkspaceLogs? = null,
) {
    suspend fun extract(
        pdf: Path,
        outputDir: Path,
        overwrite: suspend (Path) -> Boolean,
    ): PdfExtractionOutcome {
        val bytes =
            try {
                if (!pdf.isRegularFile()) return PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "PDF not found: $pdf")
                withContext(Dispatchers.IO) { pdf.readBytes() }
            } catch (e: IOException) {
                return PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "Cannot read $pdf: ${e.message}")
            }
        return extractSnapshot(bytes, pdf.fileName?.toString() ?: "", outputDir, overwrite, persistRawResponse = true)
    }

    /**
     * Extracts a captured byte snapshot. Desktop callers persist model text in their diagnostic
     * envelope; CLI callers retain the legacy verbatim raw sidecar through [extract].
     */
    suspend fun extractSnapshot(
        pdfBytes: ByteArray,
        pdfFileName: String,
        outputDir: Path,
        overwrite: suspend (Path) -> Boolean,
        persistRawResponse: Boolean = false,
    ): PdfExtractionOutcome {
        val safeName =
            try {
                pdfFileName.takeIf {
                    it.isNotBlank() && it == Path.of(it).fileName.toString() &&
                        !it.contains('/') && !it.contains('\\') && it.endsWith(".pdf", ignoreCase = true)
                }
            } catch (_: java.nio.file.InvalidPathException) {
                null
            } ?: return PdfExtractionOutcome.Failed(FailureKind.CONFIGURATION, "Invalid PDF filename: $pdfFileName")
        val bytes = pdfBytes.copyOf()
        return extractSnapshotBytes(bytes, safeName, outputDir, overwrite, persistRawResponse)
    }

    private suspend fun extractSnapshotBytes(
        bytes: ByteArray,
        pdfFileName: String,
        outputDir: Path,
        overwrite: suspend (Path) -> Boolean,
        persistRawResponse: Boolean,
    ): PdfExtractionOutcome =
        if (logs == null) {
            extractLoggedBytes(bytes, pdfFileName, outputDir, overwrite, persistRawResponse, null)
        } else {
            logs.record(
                "extractor",
                "PDF extraction",
                buildJsonObject {
                    put("requestedModel", client.modelId)
                    put("pdfFileName", pdfFileName)
                    put("pdfSha256", sha256(bytes))
                    put("pdfBytes", bytes.size)
                    put("outputDirectory", outputDir.toString())
                    put("systemPrompt", PdfExtractionPrompts.systemPrompt())
                    put("userPrompt", PdfExtractionPrompts.userPrompt())
                    put("responseSchema", ExtractedVehicle.jsonSchema)
                    put("inputNotes", client.metadataNotes)
                },
            ) { operation ->
                extractLoggedBytes(bytes, pdfFileName, outputDir, overwrite, persistRawResponse, operation)
            }
        }

    private suspend fun extractLoggedBytes(
        bytes: ByteArray,
        pdfFileName: String,
        outputDir: Path,
        overwrite: suspend (Path) -> Boolean,
        persistRawResponse: Boolean,
        operation: WorkspaceLogs.Operation?,
    ): PdfExtractionOutcome {
        val started = System.nanoTime()
        val stages = mutableListOf<StageMetrics>()
        var stage = GeminiPdfClient.STAGE
        var stageStarted = started
        var request: RequestMetrics? = null
        var httpStatus: Int? = null
        var generated: GeminiPdfResult? = null

        suspend fun run(): PdfExtractionOutcome {
            val stem = pdfFileName.substringBeforeLast('.', pdfFileName)
            val target = outputDir.resolve("$stem.json")
            val rawTarget = outputDir.resolve("$stem.raw.json")

            return try {
                GeminiPdfClient.checkPdf(bytes)
                if (target.exists() && !overwrite(target)) return PdfExtractionOutcome.Declined(target)

                stage = "Model metadata"
                stageStarted = System.nanoTime()
                val maxOutputTokens = minOf(GEMINI_PDF_MAX_OUTPUT_TOKENS, client.outputTokenLimit())
                operation?.event(
                    "MODEL_READY",
                    buildJsonObject {
                        put("requestedModel", client.modelId)
                        put("effectiveOutputTokens", maxOutputTokens)
                    },
                )
                stages += StageMetrics(stage, "SUCCEEDED", elapsedMillis(stageStarted))
                stage = GeminiPdfClient.STAGE
                stageStarted = System.nanoTime()
                val result =
                    client.extractLogged(
                        bytes,
                        PdfExtractionPrompts.systemPrompt(),
                        PdfExtractionPrompts.userPrompt(),
                        ExtractedVehicle.jsonSchema,
                        maxOutputTokens,
                        operation,
                    )
                generated = result
                operation?.response(result.text)
                request = result.metrics
                val report = ExtractionRequirements.validate(result.text, result.finishReason)
                val vehicle = report.vehicle
                if (!report.passed || vehicle == null) {
                    return PdfExtractionOutcome.Failed(
                        FailureKind.INVALID_RESPONSE,
                        "Model output violates ${report.hardViolations.size} hard requirement(s); $target was not written.",
                        report.violations,
                        rawPath = if (persistRawResponse) writeRaw(rawTarget, result.text) else null,
                        rawText = result.text,
                        model = result.modelVersion ?: client.modelId,
                        usage = result.usage,
                    )
                }
                val extraction =
                    PdfExtractionFile(
                        provenance =
                            ExtractionProvenance(
                                pdfFileName = pdfFileName,
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
                e.rawOutput?.let { operation?.response(it) }
                operation?.event(
                    "PROVIDER_ERROR",
                    buildJsonObject {
                        put("message", e.message)
                        put("failureKind", e.kind.name)
                        put("stackTrace", e.stackTraceToString())
                        put("httpStatus", e.diagnostics?.httpStatus)
                        put("providerMessage", e.diagnostics?.providerMessage)
                        put("providerCode", e.diagnostics?.providerCode)
                        put("providerName", e.diagnostics?.providerName)
                        put("upstreamCode", e.diagnostics?.upstreamCode)
                        put("upstreamMessage", e.diagnostics?.upstreamMessage)
                        put("requestId", e.diagnostics?.requestId)
                        put("requestMetrics", e.requestMetrics?.let { WorkspaceLogs.detailJson.encodeToJsonElement(it) } ?: JsonNull)
                    },
                )
                request = e.requestMetrics ?: request
                httpStatus = e.diagnostics?.httpStatus
                val rawText = e.rawOutput
                PdfExtractionOutcome.Failed(
                    e.kind,
                    e.message ?: e.kind.name,
                    rawPath = if (persistRawResponse) rawText?.let { writeRaw(rawTarget, it) } else null,
                    rawText = rawText,
                    model = client.modelId,
                )
            } catch (e: IOException) {
                val rawText = generated?.text
                PdfExtractionOutcome.Failed(
                    FailureKind.CONFIGURATION,
                    "Cannot write to $outputDir: ${e.message}",
                    rawText = rawText,
                    model = generated?.modelVersion ?: client.modelId,
                    usage = generated?.usage,
                )
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
        operation?.event(
            "RESULT",
            buildJsonObject {
                put("performanceMetadata", WorkspaceLogs.detailJson.encodeToJsonElement(metadata))
                put("responseModel", generated?.modelVersion)
                put("finishReason", generated?.finishReason)
                when (outcome) {
                    is PdfExtractionOutcome.Written -> {
                        put("outputPath", outcome.file.toString())
                        put("extractionJson", WorkspaceLogs.parseJson(PdfExtractionFile.encode(outcome.extraction)))
                        put("warnings", buildJsonArray { outcome.softViolations.forEach { add("${it.rule}: ${it.message}") } })
                    }

                    is PdfExtractionOutcome.Failed -> {
                        put("failureKind", outcome.kind.name)
                        put("message", outcome.message)
                        put("violations", buildJsonArray { outcome.violations.forEach { add("${it.severity} ${it.rule}: ${it.message}") } })
                    }

                    is PdfExtractionOutcome.Declined -> {
                        Unit
                    }
                }
            },
        )
        if (outcome is PdfExtractionOutcome.Failed) {
            operation?.failure(outcome.message, buildJsonObject { put("failureKind", outcome.kind.name) })
        }
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
