package com.sps23.aiworkflow.catalog.extraction

/** Prompt resources shared by every PDF extraction caller (integration test, later CLI/UI). */
object PdfExtractionPrompts {
    const val SYSTEM_PROMPT_RESOURCE = "/pdf-extraction/system-prompt.md"
    const val MODEL_DEFINITION_RESOURCE = "/pdf-extraction/model-definition.md"

    fun systemPrompt(): String = resource(SYSTEM_PROMPT_RESOURCE)

    fun modelDefinition(): String = resource(MODEL_DEFINITION_RESOURCE)

    /** User turn sent next to the PDF: model definition context plus the task. */
    fun userPrompt(): String =
        modelDefinition().trimEnd() +
            "\n\n# Task\n\nExtract the vehicle described in the attached PDF into one ExtractedVehicle JSON object. " +
            "Return only the JSON object."

    private fun resource(path: String): String =
        checkNotNull(PdfExtractionPrompts::class.java.getResource(path)) { "Missing prompt resource $path" }
            .readText()
            .removePrefix("\uFEFF")
}
