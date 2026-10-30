package com.sps23.aiworkflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.renderComposeScene
import com.sps23.aiworkflow.openrouter.CarAnswer
import com.sps23.aiworkflow.openrouter.FakeOpenRouterServer
import com.sps23.aiworkflow.openrouter.LlmApi
import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.TokenUsage
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.recommendation.LlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class CarPromptScreenTest {
    @Test
    fun `native screen renders populated empty and malformed answers offline for remote and local APIs`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val cases =
                    listOf(
                        FakeOpenRouterServer.CAR_OUTPUT,
                        FakeOpenRouterServer.MULTI_MODEL_OUTPUT,
                        """{"cars":[]}""",
                        "not JSON",
                    ).flatMap { raw ->
                        listOf(LlmApi.OPENROUTER, LlmApi.LM_STUDIO).map { raw to it }
                    }
                cases.forEach { (raw, api) ->
                    val controller =
                        CarPromptController { prompt, _, model, _, translated ->
                            translated(LlmRequest(prompt, emptyList(), emptyList(), emptyList(), listOf(prompt)))
                            CarAnswer(raw, model, null, metadata = WorkflowMetadata(elapsedMillis = 1, stages = emptyList()))
                        }
                    controller.selectApi(api)
                    if (api == LlmApi.OPENROUTER) controller.selectModel("qwen/qwen3.5-flash-02-23")
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

    @Test
    fun `expanded and collapsed debug panels render complete missing and failed metrics offline`() =
        runBlocking {
            withContext(Dispatchers.Swing) {
                val request =
                    RequestMetrics(
                        api = "LM Studio",
                        requestedModelId = "local/model",
                        inputUtf8Bytes = 1000,
                        estimatedInputTokens = 1012,
                        requestedOutputTokens = 32768,
                        effectiveOutputTokens = 2000,
                        elapsedMillis = 100,
                        usage = TokenUsage(100, 40, 140, 30, 80, 5),
                        completionTokensPerSecondOverRequest = 400.0,
                    )
                listOf(
                    WorkflowMetadata(
                        elapsedMillis = 100,
                        stages =
                            listOf(
                                StageMetrics("Model metadata", "SUCCEEDED", 10),
                                StageMetrics("Request translation", "SUCCEEDED", 50, request = request),
                                StageMetrics("Car recommendation", "SUCCEEDED", 100, request = request),
                            ),
                    ),
                    WorkflowMetadata(
                        elapsedMillis = 100,
                        stages = listOf(StageMetrics("Car recommendation", "FAILED", 100, "TIMEOUT")),
                    ),
                    WorkflowMetadata(elapsedMillis = 100, stages = listOf(StageMetrics("Request translation", "REUSED", 0))),
                ).forEach { metadata ->
                    listOf(false, true).forEach { expanded ->
                        listOf(600, 900).forEach { width ->
                            renderComposeScene(width = width, height = 2000) {
                                MaterialTheme { DebugMetadataPanel(metadata, initiallyExpanded = expanded) }
                            }.use { image ->
                                assertEquals(width, image.width)
                                assertEquals(2000, image.height)
                            }
                        }
                    }
                }
            }
        }
}
