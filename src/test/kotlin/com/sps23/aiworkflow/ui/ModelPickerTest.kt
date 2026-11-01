package com.sps23.aiworkflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.use
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.ModelLimits
import com.sps23.aiworkflow.openrouter.OpenRouterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPickerTest {
    private val choices = (1..25).map { ModelChoice("model-$it", "Display $it") }

    @Test
    fun `Google presets stay ordered available offline and deduplicated after discovery`() {
        val fetched =
            listOf(
                GoogleAiModel("gemini-3.8-flash", "Discovered Flash", 1000, 1000),
                GoogleAiModel("gemini-other", "Other Gemini", 1000, 1000),
                GoogleAiModel(DEFAULT_GOOGLE_UI_MODEL, "Discovered Lite", 1000, 1000),
            )
        assertEquals(GOOGLE_MODEL_PRESETS, googleModelChoices(emptyList()))
        val merged = googleModelChoices(fetched)
        assertEquals(
            listOf("Gemini 3.5 Flash-Lite (medium)", "Gemini 3.1 Flash-Lite (low)", "Gemini 3.8 Flash (high)", "Other Gemini"),
            merged.map { it.name },
        )
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        assertTrue(merged.all { it.tier == ModelPriceTier.UNKNOWN })
        val search = ModelPickerState(merged)
        search.search("low")
        assertEquals("gemini-3.1-flash-lite", search.highlighted()?.id)
    }

    @Test
    fun `initial batch contains exactly ten models and further batches reach the full catalog`() {
        val state = ModelPickerState(choices)
        assertEquals(25, state.matches.size)
        assertEquals(choices.take(10), state.visible)
        state.loadMore()
        assertEquals(20, state.visible.size)
        state.loadMore()
        assertEquals(choices, state.visible)
        state.loadMore()
        assertEquals(25, state.visible.size)
    }

    @Test
    fun `search covers unseen IDs and display names and resets paging without changing selection`() {
        val controller = CarPromptController { _, _, _, _, _, _ -> error("No inference expected") }
        controller.selectModel("model-1")
        val state = ModelPickerState(choices)
        state.loadMore()
        state.search("DISPLAY 25")
        assertEquals(listOf(choices.last()), state.visible)
        assertEquals(10, state.visibleCount)
        assertEquals("model-1", controller.selectedModel)
        state.search("MODEL-24")
        assertEquals(listOf(choices[23]), state.visible)
        state.search("")
        assertEquals(choices.take(10), state.visible)
        controller.selectModel(state.highlighted()!!.id)
        assertEquals("model-1", controller.selectedModel)
        val reopened = ModelPickerState(choices)
        assertEquals(25, reopened.matches.size)
        assertEquals("", reopened.query)
    }

    @Test
    fun `keyboard navigation reveals next batch and bounds selection safely`() {
        val state = ModelPickerState(choices)
        state.move(-1)
        assertEquals("model-1", state.highlighted()?.id)
        repeat(10) { state.move(1) }
        assertEquals("model-11", state.highlighted()?.id)
        assertEquals(20, state.visible.size)
        repeat(50) { state.move(1) }
        assertEquals("model-25", state.highlighted()?.id)
        state.search("missing")
        state.move(1)
        assertNull(state.highlighted())
        state.search("Display 22")
        assertEquals("model-22", state.highlighted()?.id)
    }

    @Test
    fun `price tiers use exact output boundaries and all-charge free flag`() {
        fun choice(
            output: String,
            free: Boolean = false,
        ): ModelChoice = OpenRouterModel("vendor/model", "Model", free, "0.0000025", output, ModelLimits(100_000, 8192)).toModelChoice()
        assertEquals(ModelPriceTier.FREE, choice("0", free = true).tier)
        assertEquals(ModelPriceTier.LOW, choice("0").tier)
        assertEquals(ModelPriceTier.LOW, choice("0.000001").tier)
        assertEquals(ModelPriceTier.MEDIUM, choice("0.000001000001").tier)
        assertEquals(ModelPriceTier.MEDIUM, choice("0.00001").tier)
        assertEquals(ModelPriceTier.HIGH, choice("0.000010000001").tier)
        assertEquals("USD / 1M tokens: input 2.5; output 10", choice("0.00001").priceText)
        assertEquals(ModelPriceTier.UNKNOWN, ModelChoice("openai/id").tier)
        assertEquals("Price unavailable", ModelChoice("gemini/id").priceText)
    }

    @Test
    @OptIn(InternalComposeUiApi::class)
    fun `focused popup consumes keyboard navigation selects beyond first batch and dismisses`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                var selected: String? = null
                var dismissed = false
                ImageComposeScene(width = 900, height = 800) {
                    MaterialTheme { ModelPickerPopup(choices, onDismiss = { dismissed = true }, onSelect = { selected = it }) }
                }.use { scene ->
                    scene.render().close()
                    scene.render().close()
                    repeat(10) {
                        assertTrue(scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown)))
                        scene.render().close()
                    }
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
                    assertEquals("model-11", selected)

                    fun type(character: Char) {
                        scene.sendKeyEvent(
                            KeyEvent(
                                Key.Unknown,
                                KeyEventType.Unknown,
                                codePoint = character.code,
                                nativeEvent =
                                    java.awt.event.KeyEvent(
                                        java.awt.Canvas(),
                                        java.awt.event.KeyEvent.KEY_TYPED,
                                        0,
                                        0,
                                        java.awt.event.KeyEvent.VK_UNDEFINED,
                                        character,
                                    ),
                            ),
                        )
                    }
                    type('2')
                    scene.render().close()
                    type('5')
                    scene.render().close()
                    assertEquals("model-11", selected)
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
                    assertEquals("model-25", selected)
                    assertTrue(scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
                    assertTrue(dismissed)
                }
            }
        }

    @Test
    fun `search popup renders full and empty catalogs offline`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                listOf(choices, emptyList()).forEach { catalog ->
                    renderComposeScene(width = 900, height = 800) {
                        MaterialTheme { ModelPickerPopup(catalog, onDismiss = {}, onSelect = {}) }
                    }.use { image -> assertEquals(900, image.width) }
                }
            }
        }
}
