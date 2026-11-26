package li.mof.kamigura.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack

@Composable
internal fun ReadingProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val fraction = progress.boundedReadingProgress()
    var widthPx by remember { mutableIntStateOf(0) }
    // The card or cover already announces progress; the strip is decorative.
    Box(modifier.clearAndSetSemantics { hideFromAccessibility() }) {
        LinearProgressIndicator(
            progress = {
                val width = widthPx
                // Match fillMaxWidth(fraction)'s whole-pixel rounding in the old strip.
                if (width > 0) (fraction * width).roundToInt().toFloat() / width else fraction
            },
            modifier = Modifier.fillMaxWidth().height(3.dp).onSizeChanged { widthPx = it.width },
            color = if (fraction == 1f) ReadingProgressRead else ReadingProgressInProgress,
            trackColor = ReadingProgressTrack,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}

internal fun Modifier.readingProgressSemantics(progress: Float?): Modifier = semantics {
    val fraction = progress.boundedReadingProgress()
    stateDescription = when (fraction) {
        0f -> "Unread"
        1f -> "Read"
        else -> "${(fraction * 100).roundToInt().coerceAtMost(99)}% read"
    }
}

private fun Float?.boundedReadingProgress(): Float =
    this?.takeUnless { it.isNaN() }?.coerceIn(0f, 1f) ?: 0f
