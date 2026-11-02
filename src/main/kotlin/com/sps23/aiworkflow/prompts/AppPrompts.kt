package com.sps23.aiworkflow.prompts

object AppPrompts {
    val translation: String = load("/prompts/request-translation.md")
    val recommendation: String = load("/prompts/recommendation.md")
    val extractionSystem: String = load("/pdf-extraction/system-prompt.md")
    val extractionModel: String = load("/pdf-extraction/model-definition.md")
    val extractionTask: String = load("/pdf-extraction/task.md")
    val extractionTextTemplate: String = load("/pdf-extraction/text-input.md")

    /** Accessing the object initializes every prompt before the desktop window opens. */
    fun initialize() = Unit

    internal fun load(path: String): String {
        val resource = checkNotNull(AppPrompts::class.java.getResource(path)) { "Missing prompt resource $path" }
        val text = resource.readText(Charsets.UTF_8).removePrefix("\uFEFF").trimEnd()
        check(text.isNotBlank()) { "Empty prompt resource $path" }
        return text
    }
}
