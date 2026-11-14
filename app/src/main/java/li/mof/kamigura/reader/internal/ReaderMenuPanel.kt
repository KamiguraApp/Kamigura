package li.mof.kamigura.reader.internal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val ColumnGap = 12.dp
internal val ReaderMenuMinimumTarget = 48.dp

internal data class ReaderMenuButtonLabels(val label: String, val options: List<String>, val gap: Dp)

/**
 * Lays out the reader menu's bottom panel: every labelled row (buttons and the setting sliders) shares
 * one label column, and the page slider runs full width between its end numbers.
 *
 * [buttons] and [sliderLabels] list every row the panel can show, including rows hidden right now, so the
 * column does not move under the finger when choosing Smart brings in its slider.
 */
@Composable
internal fun ReaderMenuPanel(
    modifier: Modifier,
    buttons: List<ReaderMenuButtonLabels>,
    sliderLabels: List<String>,
    content: @Composable ReaderMenuColumns.() -> Unit
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = LocalTextStyle.current
    val numberStyle = labelStyle.copy(fontFeatureSettings = "tnum")
    val optionStyle = MaterialTheme.typography.labelLarge

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        fun measure(value: String, style: TextStyle = labelStyle): Dp =
            with(density) { measurer.measure(value, style, softWrap = false, maxLines = 1).size.width.toDp() }

        val labelColumn = (buttons.map { it.label } + sliderLabels).maxOf { measure(it) }
        val optionWidths = buttons.associateWith { row -> row.options.maxOf { measure(it, optionStyle) } }
        val minButtonsWidth = buttons.maxOf { row ->
            val option = maxOf(ReaderMenuMinimumTarget, optionWidths.getValue(row) + 12.dp)
            option * row.options.size + row.gap * row.options.lastIndex
        }
        val stack = labelColumn + ColumnGap + minButtonsWidth > maxWidth
        val controlsWidth = if (stack) maxWidth else maxWidth - labelColumn - ColumnGap
        val buttonPaddingLimits = optionWidths.mapValues { (row, textWidth) ->
            val optionWidth = (controlsWidth - row.gap * row.options.lastIndex) / row.options.size
            ((optionWidth - textWidth) / 2 - 1.dp).coerceAtLeast(0.dp)
        }

        val columns = ReaderMenuColumns(labelColumn, buttonPaddingLimits, stack, numberStyle)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            columns.content()
        }
    }
}

internal class ReaderMenuColumns(
    private val labelColumn: Dp,
    private val buttonPaddingLimits: Map<ReaderMenuButtonLabels, Dp>,
    private val stack: Boolean,
    private val numberStyle: TextStyle
) {
    @Composable
    fun Buttons(labels: ReaderMenuButtonLabels, content: @Composable (Dp) -> Unit) {
        Labelled(labels.label) { content(buttonPaddingLimits.getValue(labels)) }
    }

    @Composable
    fun Slider(label: String, content: @Composable () -> Unit) {
        Labelled(label, content)
    }

    @Composable
    fun PageSlider(start: String, end: String, content: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ColumnGap),
            verticalAlignment = Alignment.CenterVertically) {
            PanelLabel(start, style = numberStyle)
            Box(Modifier.weight(1f)) { content() }
            PanelLabel(end, style = numberStyle)
        }
    }

    @Composable
    private fun Labelled(label: String, content: @Composable () -> Unit) {
        if (stack) {
            Column(Modifier.fillMaxWidth()) {
                PanelLabel(label)
                content()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ColumnGap),
                verticalAlignment = Alignment.CenterVertically) {
                PanelLabel(label, Modifier.width(labelColumn))
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun PanelLabel(
    label: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current
) {
    Text(label, modifier, color = Color.White, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun PaddingValues.limitHorizontal(maximum: Dp): PaddingValues = PaddingValues(
    start = calculateLeftPadding(LayoutDirection.Ltr).coerceAtMost(maximum),
    end = calculateRightPadding(LayoutDirection.Ltr).coerceAtMost(maximum),
    top = calculateTopPadding(),
    bottom = calculateBottomPadding()
)

@Composable
internal fun ReaderMenuOption(label: String) {
    Text(label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
