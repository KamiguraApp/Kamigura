package li.mof.kamigura.reader.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import li.mof.kamigura.PersonalToCDto
import li.mof.kamigura.reader.internal.ReaderMenuHeader

@Composable
internal fun NovelContentsButton(onClick: () -> Unit) =
    BookmarkIcon("Contents", Icons.AutoMirrored.Filled.List, true, onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelContentsScreen(
    seriesName: String,
    contents: NovelContentsState,
    bookmarks: NovelBookmarkState?,
    tab: Int,
    currentEntry: Int?,
    enabled: Boolean,
    feedback: String?,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onTab: (Int) -> Unit,
    onOpenContents: (NovelContentsEntry) -> Unit,
    onOpenBookmark: (PersonalToCDto) -> Unit,
    onRemove: (PersonalToCDto) -> Unit
) {
    val selectedTab = if (bookmarks == null) 0 else tab
    val state = bookmarks ?: NovelBookmarkState()
    val contentsTab = selectedTab == 0
    Column(Modifier.fillMaxSize().background(Color(0xFF111412)).navigationBarsPadding()) {
        ReaderMenuHeader(seriesName, "Contents", onBack, onDismiss, backLabel = "Back to reader menu",
            bookmark = { BookmarkIcon(if (contentsTab) "Refresh contents" else "Refresh bookmarks",
                Icons.Filled.Refresh, if (contentsTab) !contents.loading else !state.busy, onRefresh) },
            feedback = feedback ?: if (contentsTab) contents.error else state.error ?: state.message,
            feedbackIsError = feedback != null || if (contentsTab) contents.error != null else state.error != null)
        if (bookmarks != null) {
            PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary) {
                listOf("Contents", "Bookmarks").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { onTab(index) }, text = { Text(label) })
                }
            }
        }
        if (contentsTab) {
            if (contents.entries.isEmpty()) {
                Row(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        contents.loading -> "Loading contents..."
                        !contents.loaded -> "Contents unavailable."
                        else -> "No contents in this book."
                    }, color = Color.White)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(contents.entries) { index, item ->
                        val current = index == currentEntry
                        ListItem(
                            headlineContent = { Text(item.title.ifBlank { "Page ${item.page + 1}" }, maxLines = 2,
                                overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Page ${item.page + 1}") },
                            leadingContent = { Box(Modifier.size(24.dp)) {
                                if (current) Icon(Icons.Filled.PlayArrow, "Current section", tint = MaterialTheme.colorScheme.primary)
                            } },
                            colors = ListItemDefaults.colors(
                                containerColor = if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                headlineColor = Color.White, supportingColor = Color.White.copy(alpha = 0.72f)),
                            modifier = Modifier.fillMaxWidth().padding(start = (item.depth.coerceAtMost(6) * 16).dp)
                                .clickable(enabled && contents.loaded) { onOpenContents(item) }.semantics {
                                    contentDescription = "Open contents: ${item.title}"
                                    selected = current
                                    if (current) stateDescription = "Current section"
                                }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    }
                }
            }
        } else if (state.items.isEmpty()) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    state.loading -> "Loading bookmarks..."
                    !state.loaded -> "Bookmarks unavailable."
                    else -> "No bookmarks in this book."
                }, color = Color.White)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.items, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(novelBookmarkSubtitle(item), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { BookmarkIcon("Remove bookmark: ${item.title}", Icons.Filled.DeleteOutline,
                            state.loaded && !state.busy, { onRemove(item) }) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent,
                            headlineColor = Color.White, supportingColor = Color.White.copy(alpha = 0.72f)),
                        modifier = Modifier.fillMaxWidth().clickable(enabled && state.loaded && !state.busy) { onOpenBookmark(item) }
                            .semantics { contentDescription = "Open bookmark: ${item.title}" }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkIcon(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f))
        }
    }
}
