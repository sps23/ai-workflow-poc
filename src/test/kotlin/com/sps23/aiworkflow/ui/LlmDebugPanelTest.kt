package com.sps23.aiworkflow.ui

import com.sps23.aiworkflow.openrouter.RequestMetrics
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.TokenUsage
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmDebugPanelTest {
    private val request =
        RequestMetrics(
            api = "LM Studio",
            requestedModelId = "local/model",
            responseModelId = "actual/model",
            inputUtf8Bytes = 1000,
            estimatedInputTokens = 512,
            requestedOutputTokens = 32768,
            effectiveOutputTokens = 2000,
            contextLength = 10000,
            modelMaxOutputTokens = 4000,
            elapsedMillis = 2500,
            usage = TokenUsage(100, 40, 140, 30, 80, 5),
            completionTokensPerSecondOverRequest = 16.0,
            reportedGenerationTokensPerSecond = 20.0,
            reportedTimeToFirstTokenSeconds = 0.5,
        )

    @Test
    fun `each request has four logical tables preserving every token and timing field`() {
        val metadata =
            WorkflowMetadata(
                elapsedMillis = 5000,
                stages =
                    listOf(
                        StageMetrics("Model metadata", "SUCCEEDED", 100),
                        StageMetrics("Request translation", "SUCCEEDED", 2600, request = request),
                        StageMetrics("Car recommendation", "SUCCEEDED", 2300, request = request.copy(usage = TokenUsage(200, 80, 280))),
                    ),
            )
        val tables = debugRequestTables(metadata)
        assertEquals(listOf("First request: Translation", "Second request: Car recommendation"), tables.map { it.title })
        assertEquals(
            listOf(
                "SUCCEEDED",
                "unavailable",
                "LM Studio",
                "local/model",
                "actual/model",
                "100",
                "40",
                "140",
                "30",
                "80",
                "5",
                "512",
                "2000",
                "32768",
                "10000",
                "4000",
                "2600",
                "2500",
                "16.0",
                "20.0",
                "0.5",
            ),
            tables
                .first()
                .tables
                .flatMap { it.columns }
                .map { it.second },
        )
        assertEquals(
            "200",
            tables
                .last()
                .tables
                .flatMap { it.columns }
                .toMap()["Input tokens"],
        )
        val expectedGroups =
            listOf(
                "Request details" to listOf("Outcome", "Failure", "API", "Requested model", "Response model"),
                "Token usage" to
                    listOf(
                        "Input tokens",
                        "Output tokens",
                        "Total tokens",
                        "Reasoning tokens",
                        "Cached input tokens",
                        "Cache-write tokens",
                    ),
                "Budgets & limits" to
                    listOf(
                        "Estimated input tokens",
                        "Output token budget",
                        "Target output tokens",
                        "Context limit (tokens)",
                        "Model output limit (tokens)",
                    ),
                "Timing & speed" to
                    listOf(
                        "Stage time (ms)",
                        "Request time (ms)",
                        "Output tokens / second (whole request)",
                        "Generation tokens / second (reported)",
                        "Time to first token (seconds, reported)",
                    ),
            )
        tables.forEach { request ->
            assertEquals(expectedGroups, request.tables.map { it.title to it.columns.map { column -> column.first } })
            assertEquals(
                21,
                request.tables
                    .flatMap { it.columns }
                    .map { it.first }
                    .distinct()
                    .size,
            )
        }
        val readable = readableDebugData(metadata)
        assertTrue(readable.startsWith("Workflow elapsed: 5000 ms\nModel metadata: SUCCEEDED, 100 ms\n"))
        tables.forEach { request ->
            assertTrue(readable.contains(request.title))
            request.tables.forEach { table ->
                assertTrue(readable.contains(table.title))
                assertTrue(readable.contains(table.columns.joinToString("\t") { it.first }))
                assertTrue(readable.contains(table.columns.joinToString("\t") { it.second }))
            }
        }
        assertFalse(readable.contains("completionTokensPerSecondOverRequest"))
        assertTrue(readable.contains("Output tokens / second (whole request)"))
    }

    @Test
    fun `reused failed and missing requests keep unavailable values without inventing usage`() {
        val metadata =
            WorkflowMetadata(
                elapsedMillis = 100,
                stages =
                    listOf(
                        StageMetrics("Request translation", "REUSED", 0),
                        StageMetrics("Car recommendation", "FAILED", 100, "TIMEOUT"),
                    ),
            )
        val tables = debugRequestTables(metadata)
        val translation =
            tables
                .first()
                .tables
                .flatMap { it.columns }
                .toMap()
        assertEquals("REUSED", translation["Outcome"])
        assertEquals("0", translation["Stage time (ms)"])
        assertEquals("unavailable", translation["Request time (ms)"])
        assertEquals("unavailable", translation["Output tokens"])
        assertEquals(
            "TIMEOUT",
            tables
                .last()
                .tables
                .flatMap { it.columns }
                .toMap()["Failure"],
        )
        assertEquals(
            "unavailable",
            tables
                .last()
                .tables
                .flatMap { it.columns }
                .toMap()["Output tokens / second (whole request)"],
        )

        val partial =
            metadata.copy(
                stages =
                    listOf(
                        StageMetrics(
                            "Car recommendation",
                            "FAILED",
                            100,
                            request = request.copy(usage = TokenUsage(completionTokens = 0)),
                        ),
                    ),
            )
        val partialValues =
            debugRequestTables(partial)
                .single()
                .tables
                .flatMap { it.columns }
                .toMap()
        assertEquals("0", partialValues["Output tokens"])
        assertEquals("unavailable", partialValues["Input tokens"])
        assertEquals("unavailable", partialValues["Total tokens"])
    }

    @Test
    fun `readable projection leaves metadata JSON unchanged and keeps spreadsheet cells aligned`() {
        val metadata =
            WorkflowMetadata(
                elapsedMillis = 100,
                stages = listOf(StageMetrics("Request translation", "SUCCEEDED", 100, request = request.copy(api = "Local\tAPI\nname\r"))),
            )
        val before = WorkflowMetadataCodec.encode(metadata)
        val readable = readableDebugData(metadata)
        assertTrue(readable.contains("Local API name "))
        val lines = readable.lines()
        debugRequestTables(metadata).single().tables.forEach { table ->
            val index = lines.indexOf(table.title)
            assertEquals(table.columns.size, lines[index + 1].split('\t').size)
            assertEquals(table.columns.size, lines[index + 2].split('\t').size)
        }
        assertEquals(before, WorkflowMetadataCodec.encode(metadata))
    }
}
