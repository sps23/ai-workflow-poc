package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.sps23.aiworkflow.openrouter.WorkflowMetadata
import com.sps23.aiworkflow.openrouter.WorkflowMetadataCodec

@Composable
internal fun DebugMetadataPanel(
    metadata: WorkflowMetadata,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(metadata) { mutableStateOf(initiallyExpanded) }
    val json = remember(metadata) { WorkflowMetadataCodec.encode(metadata) }
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }) {
        Text("DEBUG / Performance metadata ${if (expanded) "(collapse)" else "(expand)"}")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Workflow elapsed: ${metadata.elapsedMillis} ms")
            metadata.stages.forEach { stage ->
                Text("${stage.stage}: ${stage.outcome}, ${stage.elapsedMillis} ms", style = MaterialTheme.typography.titleSmall)
                stage.request?.let { request ->
                    Text("${request.api} / ${request.responseModelId ?: request.requestedModelId}")
                    Text(
                        "Reported tokens: input ${request.usage?.promptTokens ?: "unavailable"}, " +
                            "output ${request.usage?.completionTokens ?: "unavailable"}, " +
                            "total ${request.usage?.totalTokens ?: "unavailable"}, " +
                            "reasoning ${request.usage?.reasoningTokens ?: "unavailable"}, " +
                            "cached input ${request.usage?.cachedPromptTokens ?: "unavailable"}",
                    )
                    Text(
                        "Estimated input: ${request.estimatedInputTokens}; " +
                            "output budget: ${request.effectiveOutputTokens ?: "unavailable"} / target ${request.requestedOutputTokens}",
                    )
                }
            }
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
