package li.mof.kamigura.reader.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import li.mof.kamigura.reader.internal.ReaderMenuHeader
import li.mof.kamigura.reader.internal.ReaderBookmarkButton
import li.mof.kamigura.reader.internal.ReaderBookmarkState
import li.mof.kamigura.reader.internal.ReaderMenuButtonLabels
import li.mof.kamigura.reader.internal.ReaderMenuMinimumTarget
import li.mof.kamigura.reader.internal.ReaderMenuOption
import li.mof.kamigura.reader.internal.ReaderMenuPanel
import li.mof.kamigura.reader.internal.limitHorizontal
import li.mof.kamigura.reader.internal.readerMenuPosition
import li.mof.kamigura.reader.internal.readerMenuSubtitle
import li.mof.kamigura.ui.ValueBubbleSlider
import li.mof.kamigura.PageBackground
import li.mof.kamigura.NovelFontSizeRange
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NovelReaderMenu(
    seriesName: String,
    issueLabel: String,
    page: Int,
    pages: Int,
    position: NovelPosition?,
    preferences: NovelReaderPreferences,
    enabled: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onWritingStyle: (Int) -> Unit,
    onFontSize: (Int) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onBackground: (PageBackground) -> Unit,
    bookmarkState: ReaderBookmarkState? = null,
    onBookmark: () -> Unit = {},
    onOpenContents: () -> Unit = {},
    showContents: Boolean = bookmarkState != null,
    jumpBackPage: Int? = null,
    onJumpBack: () -> Unit = {}
) {
    val count = pages.coerceAtLeast(1)
    val currentPage = page.coerceIn(0, count - 1)
    val screens = (position?.count ?: 1).coerceAtLeast(1)
    val screen = (position?.screen ?: 0).coerceIn(0, screens - 1)
    val rightToLeft = preferences.rightToLeft
    var selectedFont by remember(preferences.fontSize) { mutableIntStateOf(preferences.fontSize) }
    var jumpPage by remember(currentPage, count) { mutableIntStateOf(currentPage) }
    val writingLabels = ReaderMenuButtonLabels("Writing", listOf("Horizontal", "Vertical"),
        ButtonGroupDefaults.ConnectedSpaceBetween)
    val backgrounds = PageBackground.entries
    val backgroundLabels = ReaderMenuButtonLabels("Background", backgrounds.map { it.name },
        ButtonGroupDefaults.ConnectedSpaceBetween)
    fun pageFromSlider(value: Float): Int {
        val index = value.roundToInt().coerceIn(0, count - 1)
        return if (rightToLeft) count - 1 - index else index
    }

    Column(Modifier.fillMaxSize()) {
        val pagePosition = readerMenuPosition(currentPage, count, screen, screens)
        ReaderMenuHeader(seriesName, readerMenuSubtitle(issueLabel, pagePosition),
            onBack, onDismiss,
            contents = { if (showContents) NovelContentsButton(onOpenContents) },
            bookmark = { bookmarkState?.let { ReaderBookmarkButton(currentPage, it, onBookmark) } },
            feedback = bookmarkState?.error ?: bookmarkState?.message,
            feedbackIsError = bookmarkState?.error != null,
            compactSubtitle = pagePosition,
            jumpBackPage = jumpBackPage.takeIf { enabled }, onJumpBack = onJumpBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ReaderMenuPanel(
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCC000000))
                    .verticalScroll(rememberScrollState()).testTag("Novel reader controls").navigationBarsPadding().padding(12.dp),
                buttons = listOf(writingLabels, backgroundLabels),
                sliderLabels = listOf("Font size")
            ) {
                Buttons(writingLabels) { padding ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(writingLabels.gap)) {
                        writingLabels.options.forEachIndexed { index, label ->
                            ToggleButton(checked = preferences.writingStyle == index, enabled = enabled,
                                onCheckedChange = { onWritingStyle(index) },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight)
                                    .limitHorizontal(padding),
                                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else ButtonGroupDefaults.connectedTrailingButtonShapes()) { ReaderMenuOption(label) }
                        }
                    }
                }
                Buttons(backgroundLabels) { padding ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(backgroundLabels.gap)) {
                        backgrounds.forEachIndexed { index, background ->
                            ToggleButton(checked = preferences.background == background, enabled = enabled,
                                onCheckedChange = { onBackground(background) },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight)
                                    .limitHorizontal(padding),
                                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else ButtonGroupDefaults.connectedTrailingButtonShapes()) { ReaderMenuOption(background.name) }
                        }
                    }
                }
                Slider("Font size") {
                    ValueBubbleSlider(value = selectedFont.toFloat(),
                        onValueChange = { selectedFont = it.roundToInt() },
                        onValueChangeFinished = { onFontSize(selectedFont) },
                        valueRange = minOf(NovelFontSizeRange.first, preferences.fontSize).toFloat()..
                            maxOf(NovelFontSizeRange.last, preferences.fontSize).toFloat(),
                        valueLabel = { "${it.roundToInt()}%" }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Font size" },
                        trackModifier = Modifier.testTag("Font size track"), sliderMinHeight = ReaderMenuMinimumTarget)
                }
                PageSlider(start = if (rightToLeft) "$count" else "1", end = if (rightToLeft) "1" else "$count") {
                    ValueBubbleSlider(value = (if (rightToLeft) count - 1 - jumpPage else jumpPage).toFloat(),
                        onValueChange = { jumpPage = pageFromSlider(it) },
                        onValueChangeFinished = { if (jumpPage != currentPage) onJumpToPage(jumpPage) },
                        valueRange = 0f..(count - 1).toFloat(),
                        valueLabel = { "${pageFromSlider(it) + 1}" }, enabled = enabled && count > 1,
                        reverseTrackColors = rightToLeft, showStopIndicator = false,
                        modifier = Modifier.semantics { contentDescription = "EPUB page navigation" },
                        trackModifier = Modifier.testTag("Page navigation track"), sliderMinHeight = ReaderMenuMinimumTarget)
                }
            }
        }
    }
}
