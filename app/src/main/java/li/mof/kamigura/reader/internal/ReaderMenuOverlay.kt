package li.mof.kamigura.reader.internal
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import li.mof.kamigura.InvertMode
import li.mof.kamigura.InvertWhiteThresholdRange
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.reader.novel.NovelContentsButton
import li.mof.kamigura.ui.ValueBubbleSlider
import kotlin.math.roundToInt
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
/** Internal to reader, not for external use. */
@Composable
internal fun ReaderMenuOverlay(
    seriesName: String,
    issueLabel: String,
    page: Int,
    bookmarkState: ReaderBookmarkState?,
    onBookmark: () -> Unit,
    pages: Int,
    readingDirection: ReaderReadingDirection,
    // Spread shift only means something while spreads are shown; portrait hides the row
    // (a single-page ±1 there is just a page turn).
    showSpreadShift: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onSetReadingDirection: (ReaderReadingDirection) -> Unit,
    invertMode: InvertMode,
    onSetInvertMode: (InvertMode) -> Unit,
    whiteThreshold: Float,
    onSetWhiteThreshold: (Float) -> Unit,
    onNextSingle: () -> Unit,
    onPreviousSingle: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    jumpBackPage: Int? = null,
    onJumpBack: () -> Unit = {},
    onOpenContents: (() -> Unit)? = null
) {
    val rightToLeft = readingDirection == ReaderReadingDirection.RightToLeft
    val safePageCount = pages.coerceAtLeast(1)
    val currentPage = page.coerceIn(0, safePageCount - 1)
    var jumpPage by remember(currentPage, safePageCount) { mutableIntStateOf(currentPage) }
    val sliderMax = (safePageCount - 1).toFloat()
    val sliderValue = if (rightToLeft) {
        safePageCount - 1 - jumpPage
    } else {
        jumpPage
    }.toFloat()
    fun sliderValueToPage(value: Float): Int {
        val sliderPage = value.roundToInt().coerceIn(0, safePageCount - 1)
        return if (rightToLeft) safePageCount - 1 - sliderPage else sliderPage
    }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        val position = readerMenuPosition(currentPage, safePageCount)
        ReaderMenuHeader(
            seriesName, readerMenuSubtitle(issueLabel, position), onBack, onDismiss,
            compactSubtitle = position,
            contents = { onOpenContents?.let { NovelContentsButton(it) } },
            bookmark = { bookmarkState?.let { ReaderBookmarkButton(currentPage, it, onBookmark) } },
            feedback = bookmarkState?.error ?: bookmarkState?.message,
            feedbackIsError = bookmarkState?.error != null,
            jumpBackPage = jumpBackPage,
            onJumpBack = onJumpBack
        )

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Direction", color = Color.White)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    val directions = ReaderReadingDirection.entries
                    directions.forEachIndexed { index, direction ->
                        ToggleButton(
                            checked = readingDirection == direction,
                            onCheckedChange = { onSetReadingDirection(direction) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                directions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            }
                        ) {
                            Text(
                                when (direction) {
                                    ReaderReadingDirection.RightToLeft -> "RTL"
                                    ReaderReadingDirection.Vertical -> "Vertical"
                                    ReaderReadingDirection.LeftToRight -> "LTR"
                                }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invert", color = Color.White)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    val modes = InvertMode.entries
                    modes.forEachIndexed { index, mode ->
                        ToggleButton(
                            checked = invertMode == mode,
                            onCheckedChange = { onSetInvertMode(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            }
                        ) { Text(mode.name) }
                    }
                }
            }

            if (invertMode == InvertMode.Smart) {
                var thresholdDraft by remember(whiteThreshold) { mutableFloatStateOf(whiteThreshold) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("White threshold", color = Color.White)
                    ValueBubbleSlider(
                        value = thresholdDraft,
                        onValueChange = { thresholdDraft = it },
                        onValueChangeFinished = {
                            if (thresholdDraft != whiteThreshold) onSetWhiteThreshold(thresholdDraft)
                        },
                        valueRange = InvertWhiteThresholdRange,
                        valueLabel = { "${(it * 100f).roundToInt()}%" },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "White threshold" }
                    )
                    Text("${(thresholdDraft * 100f).roundToInt()}%", color = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (rightToLeft) "$safePageCount" else "1",
                    color = Color.White
                )
                ValueBubbleSlider(
                    value = sliderValue,
                    onValueChange = { value ->
                        jumpPage = sliderValueToPage(value)
                    },
                    onValueChangeFinished = {
                        onJumpToPage(jumpPage)
                        // The requested page may already be the second leaf of this spread.
                        jumpPage = currentPage
                    },
                    valueRange = 0f..sliderMax,
                    valueLabel = { value -> "${sliderValueToPage(value) + 1}" },
                    enabled = safePageCount > 1,
                    reverseTrackColors = rightToLeft,
                    showStopIndicator = false,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (rightToLeft) "1" else "$safePageCount",
                    color = Color.White
                )
            }

            if (showSpreadShift) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Kept discoverable here even though edge long-press does the same:
                    // the menu is the only place a new user can find spread correction.
                    Text("Spread shift", color = Color.White)
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (rightToLeft) {
                            Button(
                                onClick = onNextSingle,
                                modifier = Modifier.weight(1f)
                            ) { Text("+1") }
                            Button(
                                onClick = onPreviousSingle,
                                modifier = Modifier.weight(1f)
                            ) { Text("-1") }
                        } else {
                            Button(
                                onClick = onPreviousSingle,
                                modifier = Modifier.weight(1f)
                            ) { Text("-1") }
                            Button(
                                onClick = onNextSingle,
                                modifier = Modifier.weight(1f)
                            ) { Text("+1") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderMenuHeader(
    seriesName: String,
    subtitle: String,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    backLabel: String = "Back to series",
    bookmark: @Composable () -> Unit = {},
    feedback: String? = null,
    feedbackIsError: Boolean = false,
    compactSubtitle: String? = null,
    contents: @Composable () -> Unit = {},
    jumpBackPage: Int? = null,
    onJumpBack: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("Reader menu header")
            .background(Color(0xCC000000))
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val measurer = rememberTextMeasurer()
        val subtitleStyle = MaterialTheme.typography.bodySmall
        val subtitleColor = Color.White.copy(alpha = 0.72f)
        SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
            val spacing = 8.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val back = subcompose("back") {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel, tint = Color.White)
                }
            }.single().measure(loose)
            val actions = subcompose("actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    contents()
                    if (jumpBackPage != null) {
                        ReaderPageActionButton(jumpBackPage, "Back to p. ${jumpBackPage + 1}", onJumpBack) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = Color.White)
                        }
                    }
                    bookmark()
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close menu", tint = Color.White)
                    }
                }
            }.single().measure(loose.copy(maxWidth = (loose.maxWidth - back.width).coerceAtLeast(0)))
            val titleWidth = (constraints.maxWidth - back.width - actions.width - 2 * spacing).coerceAtLeast(0)
            fun fits(text: String) = measurer.measure(text, subtitleStyle, softWrap = false, maxLines = 1).size.width <= titleWidth
            // Keep page position readable even if all of the header actions are present on a phone.
            val inlineSubtitle = when {
                compactSubtitle == null || fits(subtitle) -> subtitle
                fits(compactSubtitle) -> compactSubtitle
                else -> null
            }
            val stackTitle = inlineSubtitle == null
            val textWidth = if (stackTitle) constraints.maxWidth else titleWidth
            val title = subcompose("title") {
                Column {
                    Text(seriesName, color = Color.White, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val position = inlineSubtitle ?: checkNotNull(compactSubtitle)
                    Text(
                        position, color = subtitleColor, style = subtitleStyle,
                        maxLines = if (stackTitle) Int.MAX_VALUE else 1,
                        overflow = if (stackTitle) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }.single().measure(loose.copy(minWidth = textWidth, maxWidth = textWidth))
            val rowHeight = maxOf(back.height, actions.height, if (stackTitle) 0 else title.height)
            layout(constraints.maxWidth, rowHeight + if (stackTitle) title.height else 0) {
                back.placeRelative(0, (rowHeight - back.height) / 2)
                if (stackTitle) title.placeRelative(0, rowHeight)
                else title.placeRelative(back.width + spacing, (rowHeight - title.height) / 2)
                actions.placeRelative(constraints.maxWidth - actions.width, (rowHeight - actions.height) / 2)
            }
        }
        if (feedback != null) {
            Text(
                text = feedback,
                color = if (feedbackIsError) MaterialTheme.colorScheme.error
                    else Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
