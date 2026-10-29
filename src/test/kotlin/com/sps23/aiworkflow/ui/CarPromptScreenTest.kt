package com.sps23.aiworkflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.renderComposeScene
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class CarPromptScreenTest {
    @Test
    fun `native screen renders populated empty and malformed answers offline`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                listOf(FakeOpenRouterServer.CAR_OUTPUT, """{"cars":[]}""", "not JSON").forEach { raw ->
                    val controller =
                        CarPromptController { prompt, model, _, translated ->
                            translated(LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                            CarAnswer(raw, model, null)
                        }
                    controller.submit("Family car with electric seats")
                    renderComposeScene(width = 900, height = 800) {
                        MaterialTheme {
                            CarPromptScreen(controller)
                        }
                    }.use { image ->
                        assertEquals(900, image.width)
                        assertEquals(800, image.height)
                    }
                }
            }
        }
}
