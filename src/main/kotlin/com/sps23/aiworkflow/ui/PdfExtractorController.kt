package com.sps23.aiworkflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sps23.aiworkflow.catalog.extraction.GeminiPdfClient
import com.sps23.aiworkflow.catalog.extraction.GeminiPdfConfig
import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACT_DEFAULT_OUTPUT_DIR
import com.sps23.aiworkflow.catalog.extraction.PDF_EXTRACT_OUTPUT_DIR_VARIABLE
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionOutcome
import com.sps23.aiworkflow.catalog.extraction.PdfExtractionService
import com.sps23.aiworkflow.catalog.extraction.Severity
import com.sps23.aiworkflow.catalog.extraction.TextPdfClient
import com.sps23.aiworkflow.catalog.extraction.Violation
import com.sps23.aiworkflow.openrouter.FailureKind
import com.sps23.aiworkflow.openrouter.GoogleAiConfig
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.OpenAiConfig
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import com.sps23.aiworkflow.openrouter.chatBackendFor
import com.sps23.aiworkflow.openrouter.elapsedMillis
import com.sps23.aiworkflow.openrouter.fetchGoogleAiModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.readText

sealed interface PdfExtractorState {
    data object Idle : PdfExtractorState

    data class Extracting(
        val pdfFileName: String,
        val modelId: String,
    ) : PdfExtractorState

    data class Done(
        val outcome: PdfExtractionOutcome.Written,
    ) : PdfExtractorState

    /** The user kept the existing [file]; Gemini was not called. */
    data class Declined(
        val file: Path,
    ) : PdfExtractorState

    data class Failed(
        val kind: FailureKind,
        val message: String,
        val hardViolations: List<Violation> = emptyList(),
        val rawPath: Path? = null,
        val rawText: String? = null,
    ) : PdfExtractorState
}

/** Runs one extraction; [confirmOverwrite] is asked before any paid call if the target exists. */
typealias PdfExtractAction = suspend (
    pdf: Path,
    outputDir: Path,
    apiKey: String,
    modelId: String,
    confirmOverwrite: suspend (Path) -> Boolean,
) -> PdfExtractionOutcome

private val defaultExtract: PdfExtractAction = { pdf, outputDir, apiKey, modelId, confirm ->
    PdfExtractionService(GeminiPdfClient(GeminiPdfConfig(apiKey, modelId))).extract(pdf, outputDir, confirm)
}

typealias PdfTextExtractAction = suspend (
    pdf: Path,
    outputDir: Path,
    api: LlmApi,
    modelId: String,
    apiKey: String?,
    baseUrl: String,
    confirmOverwrite: suspend (Path) -> Boolean,
) -> PdfExtractionOutcome

/** State for the PDF Extractor tab: one PDF -> `<outputDir>/<stem>.json` via [PdfExtractionService]. */
class PdfExtractorController(
    environment: Map<String, String?> = System.getenv(),
    private val extractAction: PdfExtractAction = defaultExtract,
    discoverModels: suspend (String) -> List<GoogleAiModel> = { fetchGoogleAiModels(it) },
    private val resolveKey: (String) -> String = { GoogleAiConfig.apiKey(environment, it) },
    private val textExtractAction: PdfTextExtractAction = { pdf, dir, api, model, key, url, confirm ->
        val backend = chatBackendFor(api, model, key, environment, url)
        PdfExtractionService(TextPdfClient(model, backend)).extract(pdf, dir, confirm)
    },
) {
    val providerSelection =
        CarPromptController(
            discoverGoogleModels = discoverModels,
            resolveGoogleKey = resolveKey,
            resolveOpenAiKey = { OpenAiConfig.apiKey(environment, it) },
        ) { _, _, _, _, _, _ -> error("PDF provider controls do not run car recommendations.") }

    var state: PdfExtractorState by mutableStateOf(PdfExtractorState.Idle)
        private set
    val extracting: Boolean get() = state is PdfExtractorState.Extracting
    var metadata: WorkflowMetadata? by mutableStateOf(null)
        private set
    val metadataJson: String? get() = metadata?.let(WorkflowMetadataCodec::encode)

    var pdfPath: String by mutableStateOf("")
        private set
    var outputDir: String by mutableStateOf(
        environment[PDF_EXTRACT_OUTPUT_DIR_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() } ?: PDF_EXTRACT_DEFAULT_OUTPUT_DIR,
    )
        private set
    val apiKey: String get() = providerSelection.googleApiKey
    val modelId: String get() = providerSelection.selectedModel
    val models: List<GoogleAiModel> get() = providerSelection.googleModels
    val modelsLoading: Boolean get() = providerSelection.googleModelsLoading
    val modelsError: String? get() = providerSelection.googleModelsError

    /** Non-null while the overwrite dialog is open. */
    var pendingOverwrite: Path? by mutableStateOf(null)
        private set
    private var overwriteAnswer: CompletableDeferred<Boolean>? = null

    val selectedModel: GoogleAiModel? get() = models.firstOrNull { it.id == modelId.trim().removePrefix("models/") }
    val canExtract: Boolean
        get() =
            !extracting && pdfPath.isNotBlank() && outputDir.isNotBlank() &&
                (providerSelection.selectedApi.isLocal || modelId.isNotBlank())

    fun updatePdfPath(value: String) {
        if (!extracting && value != pdfPath) {
            pdfPath = value
            metadata = null
        }
    }

    fun updateOutputDir(value: String) {
        if (!extracting) outputDir = value
    }

    fun selectModel(value: String) {
        if (!extracting) providerSelection.selectModel(value)
    }

    fun updateApiKey(value: String) {
        if (!extracting) providerSelection.updateGoogleApiKey(value)
    }

    suspend fun loadModels(refresh: Boolean = false) {
        if (!extracting) providerSelection.loadGoogleModels(refresh)
    }

    fun answerOverwrite(replace: Boolean) {
        overwriteAnswer?.complete(replace)
    }

    fun reset() {
        if (!extracting) {
            state = PdfExtractorState.Idle
            metadata = null
        }
    }

    suspend fun extract() {
        if (!canExtract) return
        metadata = null
        val started = System.nanoTime()

        fun failedMetadata(
            kind: FailureKind,
            exception: OpenRouterWorkflowException? = null,
        ): WorkflowMetadata =
            exception?.metadata ?: WorkflowMetadata(
                elapsedMillis = elapsedMillis(started),
                stages =
                    listOf(
                        StageMetrics(
                            GeminiPdfClient.STAGE,
                            "FAILED",
                            elapsedMillis(started),
                            kind.name,
                            exception?.requestMetrics,
                            exception?.diagnostics?.httpStatus,
                        ),
                    ),
                notes = "Metadata only; null means unavailable. No PDF token estimate is available.",
            )

        val api = providerSelection.selectedApi
        val model = if (api == LlmApi.GOOGLE_AI) modelId.trim().removePrefix("models/") else modelId.trim()
        val (pdf, dir) =
            try {
                Path.of(pdfPath.trim()) to Path.of(outputDir.trim())
            } catch (e: InvalidPathException) {
                metadata = failedMetadata(FailureKind.CONFIGURATION)
                state = PdfExtractorState.Failed(FailureKind.CONFIGURATION, "Invalid path: ${e.message}")
                return
            }
        state = PdfExtractorState.Extracting(pdf.fileName?.toString() ?: pdfPath, model)
        providerSelection.controlsLocked = true
        state =
            try {
                val outcome =
                    if (api == LlmApi.GOOGLE_AI) {
                        extractAction(pdf, dir, resolveKey(apiKey), model, ::confirmOverwrite)
                    } else {
                        val key =
                            when {
                                api.isLocal -> providerSelection.localApiKey
                                api == LlmApi.OPENAI -> providerSelection.openAiApiKey
                                api == LlmApi.MANUAL -> providerSelection.manualApiKey
                                else -> ""
                            }.takeIf { it.isNotBlank() }
                        textExtractAction(pdf, dir, api, model, key, providerSelection.manualBaseUrl, ::confirmOverwrite)
                    }
                when (outcome) {
                    is PdfExtractionOutcome.Written -> {
                        metadata = outcome.metadata
                        PdfExtractorState.Done(outcome)
                    }

                    is PdfExtractionOutcome.Declined -> {
                        PdfExtractorState.Declined(outcome.file)
                    }

                    is PdfExtractionOutcome.Failed -> {
                        metadata = outcome.metadata
                        PdfExtractorState.Failed(
                            outcome.kind,
                            outcome.message,
                            outcome.violations.filter { it.severity == Severity.HARD },
                            outcome.rawPath,
                            outcome.rawPath?.let { readRaw(it) },
                        )
                    }
                }
            } catch (e: CancellationException) {
                state = PdfExtractorState.Idle
                throw e
            } catch (e: OpenRouterWorkflowException) {
                metadata = failedMetadata(e.kind, e)
                PdfExtractorState.Failed(e.kind, e.message ?: e.kind.name)
            } catch (e: Exception) {
                metadata = failedMetadata(FailureKind.PROVIDER)
                PdfExtractorState.Failed(FailureKind.PROVIDER, "PDF extraction failed: ${e.javaClass.simpleName}")
            } finally {
                providerSelection.controlsLocked = false
                pendingOverwrite = null
                overwriteAnswer = null
            }
    }

    private suspend fun confirmOverwrite(target: Path): Boolean {
        val answer = CompletableDeferred<Boolean>()
        overwriteAnswer = answer
        pendingOverwrite = target
        return try {
            answer.await()
        } finally {
            pendingOverwrite = null
            overwriteAnswer = null
        }
    }

    private suspend fun readRaw(path: Path): String? =
        try {
            withContext(Dispatchers.IO) { path.readText() }
        } catch (_: IOException) {
            null
        }
}
