package com.sps23.aiworkflow.openrouter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import kotlin.system.exitProcess

/** Standalone one-call smoke prompt: no car catalog or translation. */
fun runGoogleAiPrompt(
    args: List<String>,
    environment: Map<String, String?>,
    out: PrintStream,
    err: PrintStream,
    baseUrl: String = GOOGLE_AI_BASE_URL,
): Int {
    val prompt = args.joinToString(" ").trim()
    if (prompt.isBlank()) {
        err.println("Error: provide a prompt, e.g. ./gradlew googleAiPrompt --args='Hello!'")
        return 2
    }
    return try {
        val config = GoogleAiConfig.fromEnvironment(environment, baseUrl = baseUrl)
        val completion = runBlocking { GoogleAiClient(config).complete(userMessage = prompt) }
        out.println(completion.text)
        out.println("Model: ${completion.responseModelId ?: completion.requestedModelId}")
        0
    } catch (e: CancellationException) {
        throw e
    } catch (e: OpenRouterWorkflowException) {
        err.println("Error [${e.kind}]: ${e.message}")
        1
    } catch (e: Exception) {
        err.println("Error: ${e.javaClass.simpleName}: Google AI prompt failed.")
        1
    }
}

object GoogleAiPromptCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = runGoogleAiPrompt(args.toList(), System.getenv(), System.out, System.err)
        if (code != 0) exitProcess(code)
    }
}
