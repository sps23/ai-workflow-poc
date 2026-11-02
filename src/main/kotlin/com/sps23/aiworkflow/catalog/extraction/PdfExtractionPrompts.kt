package com.sps23.aiworkflow.catalog.extraction

import com.sps23.aiworkflow.prompts.AppPrompts

/** Cached prompt resources shared by all PDF extraction callers. */
object PdfExtractionPrompts {
    const val SYSTEM_PROMPT_RESOURCE = "/pdf-extraction/system-prompt.md"
    const val MODEL_DEFINITION_RESOURCE = "/pdf-extraction/model-definition.md"

    fun systemPrompt(): String = AppPrompts.extractionSystem

    fun modelDefinition(): String = AppPrompts.extractionModel

    /** User turn sent next to the PDF: model definition context plus the task. */
    fun userPrompt(): String = modelDefinition() + "\n\n" + AppPrompts.extractionTask
}
