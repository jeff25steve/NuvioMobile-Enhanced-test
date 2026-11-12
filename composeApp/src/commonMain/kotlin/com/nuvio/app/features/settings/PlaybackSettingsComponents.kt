package com.nuvio.app.features.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Slider
import nuvio.composeapp.generated.resources.*
import kotlin.math.roundToInt

fun snapToStep(value: Float, step: Float): Float {
    return (value / step).roundToInt() * step
}

fun calculateSteps(
    min: Float,
    max: Float,
    stepSize: Float
): Int {
    val totalSteps = ((max - min) / stepSize).roundToInt()
    return (totalSteps - 1).coerceAtLeast(0)
}

@Composable
fun ValueBox(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SettingsSliderRow(
    title: String,
    value: Int,
    valueText: (@Composable (Int) -> String)? = null,
    valueRange: IntRange,
    step: Int,
    isTablet: Boolean,
    enabled: Boolean = true,
    description: String? = null,
    formatValue: (@Composable (Int) -> String)? = null,
    searchAnchorKey: String? = null,
    highlightedSearchAnchorKey: String? = null,
    onValueChange: (Int) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val rangeStart = valueRange.first.toFloat()
    val rangeEnd = valueRange.last.toFloat()
    var isDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) {
        if (!isDragging) {
            sliderValue = value.toFloat().coerceIn(rangeStart, rangeEnd)
        }
    }
    val shownValue = sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)
    val bringIntoViewRequester = remember(searchAnchorKey) {
        searchAnchorKey?.let { BringIntoViewRequester() }
    }
    LaunchedEffect(searchAnchorKey, highlightedSearchAnchorKey, bringIntoViewRequester) {
        val requester = bringIntoViewRequester
        if (requester != null && searchAnchorKey == highlightedSearchAnchorKey) {
            requester.bringIntoView()
        }
    }
    val highlighted = searchAnchorKey != null && searchAnchorKey == highlightedSearchAnchorKey
    val highlightShape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .then(bringIntoViewRequester?.let { Modifier.bringIntoViewRequester(it) } ?: Modifier)
            .fillMaxWidth()
            .then(
                if (highlighted) Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), highlightShape)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)), highlightShape)
                else Modifier
            )
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            ValueBox(
                text = (formatValue ?: valueText)?.invoke(shownValue) ?: shownValue.toString(),
                modifier = Modifier.wrapContentWidth(),
            )
        }
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = sliderValue.coerceIn(rangeStart, rangeEnd),
            onValueChange = { raw ->
                if (enabled) {
                    isDragging = true
                    val snapped = snapToStep(raw, step.toFloat()).coerceIn(rangeStart, rangeEnd)
                    sliderValue = snapped
                    val next = snapped.roundToInt().coerceIn(valueRange.first, valueRange.last)
                    if (next != value) onValueChange(next)
                }
            },
            onValueChangeFinished = {
                isDragging = false
                if (enabled) {
                    val next = sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last)
                    if (next != value) onValueChange(next)
                }
            },
            enabled = enabled,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = calculateSteps(valueRange.first.toFloat(), valueRange.last.toFloat(), step.toFloat()),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        )
    }
}

@Composable
internal fun PlaybackSettingsNote(
    text: String,
    color: Color,
    isTablet: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = 8.dp,
            ),
    )
}
