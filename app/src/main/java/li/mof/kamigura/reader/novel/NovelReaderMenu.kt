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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import li.mof.kamigura.reader.internal.ReaderMenuHeader
import li.mof.kamigura.reader.internal.ReaderBookmarkButton
import li.mof.kamigura.reader.internal.ReaderBookmarkState
import li.mof.kamigura.ui.ValueBubbleSlider
import li.mof.kamigura.PageBackground
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NovelReaderMenu(
    seriesName: String,
    chapterName: String,
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
    showContents: Boolean = bookmarkState != null
) {
    val count = pages.coerceAtLeast(1)
    val currentPage = page.coerceIn(0, count - 1)
    val screens = (position?.count ?: 1).coerceAtLeast(1)
    val screen = (position?.screen ?: 0).coerceIn(0, screens - 1)
    val rightToLeft = preferences.rightToLeft
    var selectedFont by remember(preferences.fontSize) { mutableIntStateOf(preferences.fontSize) }
    var jumpPage by remember(currentPage, count) { mutableIntStateOf(currentPage) }
    fun pageFromSlider(value: Float): Int {
        val index = value.roundToInt().coerceIn(0, count - 1)
        return if (rightToLeft) count - 1 - index else index
    }

    Column(Modifier.fillMaxSize()) {
        ReaderMenuHeader(seriesName, "$chapterName | Page ${currentPage + 1} / $count \u2022 Screen ${screen + 1} / $screens",
            onBack, onDismiss, backLabel = "Back to series",
            bookmark = {
                if (showContents) NovelContentsButton(onOpenContents)
                bookmarkState?.let {
                ReaderBookmarkButton(currentPage, it, onBookmark)
            } },
            feedback = bookmarkState?.error ?: bookmarkState?.message,
            feedbackIsError = bookmarkState?.error != null,
            compactSubtitle = "Page ${currentPage + 1} / $count \u2022 Screen ${screen + 1} / $screens")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000))
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Writing", color = Color.White)
                    Row(Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        listOf("Horizontal", "Vertical").forEachIndexed { index, label ->
                            ToggleButton(checked = preferences.writingStyle == index, enabled = enabled,
                                onCheckedChange = { onWritingStyle(index) },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else ButtonGroupDefaults.connectedTrailingButtonShapes()) { Text(label) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Background", color = Color.White)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        PageBackground.entries.forEachIndexed { index, background ->
                            ToggleButton(checked = preferences.background == background, enabled = enabled,
                                onCheckedChange = { onBackground(background) },
                                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    else ButtonGroupDefaults.connectedTrailingButtonShapes()) { Text(background.name) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Font size", color = Color.White)
                    ValueBubbleSlider(value = selectedFont.toFloat(),
                        onValueChange = { selectedFont = it.roundToInt() },
                        onValueChangeFinished = { onFontSize(selectedFont) },
                        valueRange = minOf(50, preferences.fontSize).toFloat()..maxOf(250, preferences.fontSize).toFloat(),
                        valueLabel = { "${it.roundToInt()}%" }, enabled = enabled,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Font size" })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rightToLeft) "$count" else "1", color = Color.White)
                    ValueBubbleSlider(value = (if (rightToLeft) count - 1 - jumpPage else jumpPage).toFloat(),
                        onValueChange = { jumpPage = pageFromSlider(it) },
                        onValueChangeFinished = { if (jumpPage != currentPage) onJumpToPage(jumpPage) },
                        valueRange = 0f..(count - 1).toFloat(),
                        valueLabel = { "${pageFromSlider(it) + 1}" }, enabled = enabled && count > 1,
                        reverseTrackColors = rightToLeft, showStopIndicator = false,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "EPUB page navigation" })
                    Text(if (rightToLeft) "1" else "$count", color = Color.White)
                }
            }
        }
    }
}
