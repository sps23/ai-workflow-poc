package com.sps23.aiworkflow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.sps23.aiworkflow.openrouter.GoogleAiModel
import com.sps23.aiworkflow.openrouter.OpenRouterModel
import kotlinx.coroutines.flow.collectLatest
import java.math.BigDecimal

internal enum class ModelPriceTier(
    val label: String,
) {
    FREE("Free"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    UNKNOWN("Price unavailable"),
}

internal data class ModelChoice(
    val id: String,
    val name: String = id,
    val tier: ModelPriceTier = ModelPriceTier.UNKNOWN,
    val priceText: String = "Price unavailable",
)

const val DEFAULT_GOOGLE_UI_MODEL = "gemini-3.5-flash-lite"

internal val GOOGLE_MODEL_PRESETS =
    listOf(
        ModelChoice(DEFAULT_GOOGLE_UI_MODEL, "Gemini 3.5 Flash-Lite (medium)"),
        ModelChoice("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite (low)"),
        ModelChoice("gemini-3.8-flash", "Gemini 3.8 Flash (high)"),
    )

internal fun googleModelChoices(models: List<GoogleAiModel>): List<ModelChoice> =
    (GOOGLE_MODEL_PRESETS + models.map { ModelChoice(it.id, it.name) }).distinctBy { it.id }

internal fun OpenRouterModel.toModelChoice(): ModelChoice {
    val input = promptPrice.toBigDecimal().multiply(BigDecimal(1_000_000))
    val output = completionPrice.toBigDecimal().multiply(BigDecimal(1_000_000))
    val tier =
        when {
            isFree -> ModelPriceTier.FREE
            output <= BigDecimal.ONE -> ModelPriceTier.LOW
            output <= BigDecimal.TEN -> ModelPriceTier.MEDIUM
            else -> ModelPriceTier.HIGH
        }
    return ModelChoice(
        id,
        name,
        tier,
        "USD / 1M tokens: input ${input.stripTrailingZeros().toPlainString()}; output ${output.stripTrailingZeros().toPlainString()}",
    )
}

internal class ModelPickerState(
    private val choices: List<ModelChoice>,
) {
    var query by mutableStateOf("")
        private set
    var visibleCount by mutableStateOf(10)
        private set
    var highlightedIndex by mutableStateOf(0)
        private set
    val matches: List<ModelChoice>
        get() = choices.filter { it.id.contains(query, true) || it.name.contains(query, true) }
    val visible: List<ModelChoice> get() = matches.take(visibleCount)

    fun search(value: String) {
        query = value
        visibleCount = 10
        highlightedIndex = 0
    }

    fun loadMore() {
        visibleCount = (visibleCount + 10).coerceAtMost(matches.size)
    }

    fun move(delta: Int) {
        highlightedIndex = (highlightedIndex + delta).coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        if (highlightedIndex >= visibleCount) loadMore()
    }

    fun highlighted(): ModelChoice? = matches.getOrNull(highlightedIndex)
}

@Composable
internal fun ModelPickerPopup(
    choices: List<ModelChoice>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val state = remember(choices) { ModelPickerState(choices) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val matches = state.matches
    val visible = state.visible
    LaunchedEffect(state, state.query) { listState.scrollToItem(0) }
    LaunchedEffect(state, state.highlightedIndex) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(state.highlightedIndex)
    }
    LaunchedEffect(listState, state) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index to info.totalItemsCount
        }.collectLatest { (last, total) ->
            if (total > 0 && last == total - 1 && state.visibleCount < state.matches.size) state.loadMore()
        }
    }
    Popup(onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        androidx.compose.material3.Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
            Column(Modifier.width(520.dp).padding(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = state::search,
                    label = { Text("Search models by ID / name") },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        state.move(1)
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        state.move(-1)
                                        true
                                    }

                                    Key.Enter -> {
                                        state.highlighted()?.let { onSelect(it.id) }
                                        true
                                    }

                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }

                                    else -> {
                                        false
                                    }
                                }
                            }
                        },
                )
                Text("${visible.size} / ${matches.size} matching models", style = MaterialTheme.typography.bodySmall)
                if (matches.isEmpty()) Text("No matches. Try another search or enter an exact ID outside this picker.")
                LazyColumn(state = listState, modifier = Modifier.height(360.dp)) {
                    itemsIndexed(visible, key = { _, model -> model.id }) { index, model ->
                        Column {
                            if (index == 0 || visible[index - 1].tier != model.tier) {
                                Text(model.tier.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.id)
                                        if (model.name != model.id) Text(model.name, style = MaterialTheme.typography.bodySmall)
                                        val color =
                                            when (model.tier) {
                                                ModelPriceTier.FREE, ModelPriceTier.LOW -> MaterialTheme.colorScheme.primary
                                                ModelPriceTier.MEDIUM -> MaterialTheme.colorScheme.tertiary
                                                ModelPriceTier.HIGH -> MaterialTheme.colorScheme.error
                                                ModelPriceTier.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        Text(
                                            "${model.tier.label} | ${model.priceText}",
                                            color = color,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = { onSelect(model.id) },
                                leadingIcon = { if (index == state.highlightedIndex) Text(">") },
                            )
                        }
                    }
                }
            }
        }
    }
}
