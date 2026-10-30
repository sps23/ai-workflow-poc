package com.sps23.aiworkflow.recommendation

import com.sps23.aiworkflow.openrouter.OPENROUTER_BASE_URL
import com.sps23.aiworkflow.openrouter.OpenRouterCarWorkflow
import com.sps23.aiworkflow.openrouter.OpenRouterConfig
import com.sps23.aiworkflow.openrouter.OpenRouterWorkflowException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.system.exitProcess

const val PROTOTYPE_DISCLAIMER =
    "AI-generated prototype advice. Verify every fact against the catalog and source documents before relying on it."

/** Runs one prompt; returns the process exit code. Output goes to the given streams. */
fun runCarPrompt(
    args: List<String>,
    environment: Map<String, String?>,
    out: PrintStream,
    err: PrintStream,
    baseUrl: String = OPENROUTER_BASE_URL,
    catalogJson: () -> String = { DataModelCatalogContext.loadBundled().promptJson },
): Int {
    val prompt = args.joinToString(" ").trim()
    if (prompt.isEmpty()) {
        err.println("Error: provide a prompt, e.g. ./gradlew carPrompt --args='Suggest a seven-seat car'")
        return 2
    }
    return try {
        val config = OpenRouterConfig.fromEnvironment(environment, baseUrl)
        val answer = runBlocking { OpenRouterCarWorkflow(config, catalogJson()).answer(prompt) }
        out.println(answer.selection.toReadableText())
        out.println()
        out.println(
            "Model: ${answer.responseModelId ?: "${answer.requestedModelId} (requested; response model ID not reported)"}",
        )
        out.println(PROTOTYPE_DISCLAIMER)
        0
    } catch (e: CancellationException) {
        throw e
    } catch (e: OpenRouterWorkflowException) {
        err.println("Error [${e.kind}]: ${e.message}")
        1
    } catch (e: Exception) {
        err.println("Error: ${e.javaClass.simpleName}: ${e.message}")
        1
    }
}

fun main(args: Array<String>) {
    val code = runCarPrompt(args.toList(), System.getenv(), System.out, System.err)
    if (code != 0) exitProcess(code)
}
