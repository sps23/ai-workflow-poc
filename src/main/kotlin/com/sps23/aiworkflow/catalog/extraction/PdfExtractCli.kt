package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.openrouter.GOOGLE_AI_API_KEY_VARIABLE
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_BASE_URL
import com.sps23.aiworkflow.openrouter.GOOGLE_AI_MODEL_VARIABLE
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

const val PDF_EXTRACT_OUTPUT_DIR_VARIABLE = "PDF_EXTRACT_OUTPUT_DIR"
const val PDF_EXTRACT_DEFAULT_OUTPUT_DIR = "extracted-catalog"

/** Exit codes of [runPdfExtract]. */
object PdfExtractExit {
    const val WRITTEN = 0
    const val FAILED = 1
    const val USAGE = 2
    const val EXISTS = 3
}

private const val USAGE = "Usage: ./gradlew extractPdf --args='<pdf> [outputDir] [--overwrite]'"

/**
 * One PDF -> `<outputDir>/<stem>.json` via [PdfExtractionService].
 * Output dir: argument -> [PDF_EXTRACT_OUTPUT_DIR_VARIABLE] -> [PDF_EXTRACT_DEFAULT_OUTPUT_DIR].
 * Never prompts: an existing file is kept unless `--overwrite` is given.
 */
fun runPdfExtract(
    args: List<String>,
    environment: Map<String, String?>,
    out: PrintStream,
    err: PrintStream,
    baseUrl: String = GOOGLE_AI_BASE_URL,
    clientFactory: (GeminiPdfConfig) -> PdfExtractionClient = ::GeminiPdfClient,
): Int {
    val overwrite = "--overwrite" in args
    val unknownFlags = args.filter { it.startsWith("--") && it != "--overwrite" }
    val positional = args.filterNot { it.startsWith("--") }
    if (unknownFlags.isNotEmpty() || positional.size !in 1..2 || positional.any { it.isBlank() }) {
        if (unknownFlags.isNotEmpty()) err.println("Error: unknown option(s) ${unknownFlags.joinToString()}.")
        err.println(USAGE)
        return PdfExtractExit.USAGE
    }
    val (pdf, outputDir) =
        try {
            val dir =
                positional.getOrNull(1)
                    ?: environment[PDF_EXTRACT_OUTPUT_DIR_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: PDF_EXTRACT_DEFAULT_OUTPUT_DIR
            Path.of(positional[0]) to Path.of(dir)
        } catch (e: InvalidPathException) {
            err.println("Error: invalid path: ${e.message}")
            return PdfExtractExit.USAGE
        }
    val apiKey = environment[GOOGLE_AI_API_KEY_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() }
    if (apiKey == null) {
        err.println("Error [CONFIGURATION]: set $GOOGLE_AI_API_KEY_VARIABLE.")
        return PdfExtractExit.USAGE
    }
    val modelId = environment[GOOGLE_AI_MODEL_VARIABLE]?.trim()?.takeIf { it.isNotEmpty() } ?: GEMINI_PDF_DEFAULT_MODEL

    return try {
        val client = clientFactory(GeminiPdfConfig(apiKey, modelId, baseUrl))
        out.println("Extracting ${pdf.fileName} with ${client.modelId} (this can take a few minutes)...")
        val outcome = runBlocking { PdfExtractionService(client).extract(pdf, outputDir) { overwrite } }
        report(outcome, out, err)
    } catch (e: CancellationException) {
        throw e
    } catch (e: OpenRouterWorkflowException) {
        err.println("Error [${e.kind}]: ${e.message}")
        PdfExtractExit.FAILED
    } catch (e: Exception) {
        err.println("Error: ${e.javaClass.simpleName}: PDF extraction failed.")
        PdfExtractExit.FAILED
    }
}

private fun report(
    outcome: PdfExtractionOutcome,
    out: PrintStream,
    err: PrintStream,
): Int =
    when (outcome) {
        is PdfExtractionOutcome.Written -> {
            val vehicle = outcome.extraction.vehicle
            out.println("Wrote ${outcome.file.toAbsolutePath()}")
            out.println(
                "Vehicle: ${vehicle.make} ${vehicle.model} (${vehicle.trims.size} trims, ${vehicle.trims.sumOf {
                    it.variants.size
                }} variants)",
            )
            out.println("Model: ${outcome.extraction.provenance.model}")
            out.println("Tokens: ${tokens(outcome.usage)}")
            out.println("Latency: ${outcome.latencyMillis} ms")
            outcome.softViolations.forEach { out.println("Warning ${it.rule}: ${it.message}") }
            PdfExtractExit.WRITTEN
        }

        is PdfExtractionOutcome.Declined -> {
            err.println("Error: ${outcome.file.toAbsolutePath()} already exists; rerun with --overwrite to replace it.")
            PdfExtractExit.EXISTS
        }

        is PdfExtractionOutcome.Failed -> {
            err.println("Error [${outcome.kind}]: ${outcome.message}")
            outcome.violations.filter { it.severity == Severity.HARD }.forEach { err.println("  ${it.rule}: ${it.message}") }
            outcome.rawPath?.let { err.println("Raw model output saved to ${it.toAbsolutePath()}") }
            outcome.usage?.let { err.println("Tokens: ${tokens(it)}") }
            PdfExtractExit.FAILED
        }
    }

private fun tokens(usage: GeminiUsage): String =
    "input ${usage.promptTokens ?: "n/a"}, output ${usage.outputTokens} (thinking ${usage.thoughtsTokens ?: 0}), " +
        "total ${usage.totalTokens ?: "n/a"}"

object PdfExtractCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = runPdfExtract(args.toList(), System.getenv(), System.out, System.err)
        if (code != 0) exitProcess(code)
    }
}
