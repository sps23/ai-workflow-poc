package com.sps23.aiworkflow.prompts

import com.sps23.aiworkflow.catalog.extraction.PdfExtractionPrompts
import com.sps23.aiworkflow.openrouter.SYSTEM_INSTRUCTIONS
import com.sps23.aiworkflow.recommendation.REQUEST_TRANSLATION_INSTRUCTIONS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppPromptsTest {
    @Test
    fun `startup loads packaged UTF8 Markdown resources and reuses cached strings`() {
        AppPrompts.initialize()
        assertSame(REQUEST_TRANSLATION_INSTRUCTIONS, AppPrompts.translation)
        assertSame(SYSTEM_INSTRUCTIONS, AppPrompts.recommendation)
        assertSame(PdfExtractionPrompts.systemPrompt(), AppPrompts.extractionSystem)
        assertSame(PdfExtractionPrompts.modelDefinition(), AppPrompts.extractionModel)
        val paths =
            mapOf(
                "/prompts/request-translation.md" to AppPrompts.translation,
                "/prompts/recommendation.md" to AppPrompts.recommendation,
                PdfExtractionPrompts.SYSTEM_PROMPT_RESOURCE to AppPrompts.extractionSystem,
                PdfExtractionPrompts.MODEL_DEFINITION_RESOURCE to AppPrompts.extractionModel,
                "/pdf-extraction/task.md" to AppPrompts.extractionTask,
                "/pdf-extraction/text-input.md" to AppPrompts.extractionTextTemplate,
            )
        paths.forEach { (path, text) ->
            val resource = checkNotNull(javaClass.getResource(path))
            assertEquals(resource.readText(Charsets.UTF_8).removePrefix("\uFEFF").trimEnd(), text)
            assertTrue(text.isNotBlank())
        }
        assertEquals(AppPrompts.extractionModel + "\n\n" + AppPrompts.extractionTask, PdfExtractionPrompts.userPrompt())
    }

    @Test
    fun `missing prompt resource fails with its path instead of empty fallback`() {
        val error = assertFailsWith<IllegalStateException> { AppPrompts.load("/prompts/missing.md") }
        assertEquals("Missing prompt resource /prompts/missing.md", error.message)
    }
}
