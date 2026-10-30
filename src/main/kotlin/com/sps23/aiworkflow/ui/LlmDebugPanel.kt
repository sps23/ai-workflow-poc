package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sps23.aiworkflow.openrouter.StageMetrics
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec

internal data class DebugRequestTable(
    val title: String,
    val columns: List<Pair<String, String>>,
)

internal data class DebugRequestTables(
    val title: String,
    val tables: List<DebugRequestTable>,
)

internal fun debugRequestTables(metadata: WorkflowMetadata): List<DebugRequestTables> =
    metadata.stages.filter { it.stage != "Model metadata" }.map { stage ->
        val request = stage.request
        val usage = request?.usage

        fun table(
            title: String,
            vararg values: Pair<String, Any?>,
        ) = DebugRequestTable(title, values.map { (label, value) -> label to (value?.toString() ?: "unavailable") })

        val tables =
            listOf(
                table(
                    "Request details",
                    "Outcome" to stage.outcome,
                    "Failure" to stage.failureKind,
                    "API" to request?.api,
                    "Requested model" to request?.requestedModelId,
                    "Response model" to request?.responseModelId,
                ),
                table(
                    "Token usage",
                    "Input tokens" to usage?.promptTokens,
                    "Output tokens" to usage?.completionTokens,
                    "Total tokens" to usage?.totalTokens,
                    "Reasoning tokens" to usage?.reasoningTokens,
                    "Cached input tokens" to usage?.cachedPromptTokens,
                    "Cache-write tokens" to usage?.cacheWriteTokens,
                ),
                table(
                    "Budgets & limits",
                    "Estimated input tokens" to request?.estimatedInputTokens,
                    "Output token budget" to request?.effectiveOutputTokens,
                    "Target output tokens" to request?.requestedOutputTokens,
                    "Context limit (tokens)" to request?.contextLength,
                    "Model output limit (tokens)" to request?.modelMaxOutputTokens,
                ),
                table(
                    "Timing & speed",
                    "Stage time (ms)" to stage.elapsedMillis,
                    "Request time (ms)" to request?.elapsedMillis,
                    "Output tokens / second (whole request)" to request?.completionTokensPerSecondOverRequest,
                    "Generation tokens / second (reported)" to request?.reportedGenerationTokensPerSecond,
                    "Time to first token (seconds, reported)" to request?.reportedTimeToFirstTokenSeconds,
                ),
            )
        DebugRequestTables(
            title =
                when (stage.stage) {
                    "Request translation" -> "First request: Translation"
                    "Car recommendation" -> "Second request: Car recommendation"
                    else -> stage.stage
                },
            tables = tables,
        )
    }

private fun StageMetrics.readableSummary(): String = "$stage: $outcome, $elapsedMillis ms"

internal fun readableDebugData(metadata: WorkflowMetadata): String =
    buildString {
        appendLine("Workflow elapsed: ${metadata.elapsedMillis} ms")
        metadata.stages.filter { it.stage == "Model metadata" }.forEach { appendLine(it.readableSummary()) }
        debugRequestTables(metadata).forEach { request ->
            appendLine()
            appendLine(request.title)
            request.tables.forEach { table ->
                appendLine()
                appendLine(table.title)
                appendLine(table.columns.joinToString("\t") { it.first })
                appendLine(
                    table.columns.joinToString("\t") {
                        it.second
                            .replace('\t', ' ')
                            .replace('\r', ' ')
                            .replace('\n', ' ')
                    },
                )
            }
        }
    }

@Composable
private fun RequestMetricsTable(table: DebugRequestTable) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(table.title, style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        table.columns.forEach { (label, _) -> DebugTableCell(label, Modifier.weight(1f)) }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    table.columns.forEach { (_, value) -> DebugTableCell(value, Modifier.weight(1f)) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DebugTableCell(
    value: String,
    modifier: Modifier,
) {
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Default,
        modifier = modifier.padding(8.dp),
    )
}

@Composable
internal fun DebugMetadataPanel(
    metadata: WorkflowMetadata,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(metadata) { mutableStateOf(initiallyExpanded) }
    val json = remember(metadata) { WorkflowMetadataCodec.encode(metadata) }
    val tables = remember(metadata) { debugRequestTables(metadata) }
    val readable = remember(metadata) { readableDebugData(metadata) }
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }) {
        Text("DEBUG / Performance metadata ${if (expanded) "(collapse)" else "(expand)"}")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workflow elapsed: ${metadata.elapsedMillis} ms")
            metadata.stages.filter { it.stage == "Model metadata" }.forEach { stage ->
                Text(stage.readableSummary(), style = MaterialTheme.typography.titleSmall)
            }
            tables.forEach { request ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.title, style = MaterialTheme.typography.titleMedium)
                    request.tables.forEach { RequestMetricsTable(it) }
                }
            }
            CopyButton("Copy readable debug data", readable)
            JsonExportButtons(
                json = json,
                fileName = "llm-metadata.json",
                contentLabel = "performance metadata",
                downloadLabel = "Download metadata JSON",
                copyLabel = "Copy metadata JSON",
                description =
                    "Metadata only; excludes prompts, catalog, responses and credentials. " +
                        "Review model/provider identifiers before sharing.",
            )
            Text(metadata.notes, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    json,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
