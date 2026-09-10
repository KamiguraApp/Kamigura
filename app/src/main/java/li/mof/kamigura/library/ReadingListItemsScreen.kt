package li.mof.kamigura.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.ReadingListItemDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.series.internal.displayName
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KamiguraPullToRefreshIndicator
import li.mof.kamigura.ui.browse.BrowsePageScaffold

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReadingListItemsScreen(
    sessionStore: KavitaSessionStore,
    readingListId: Int,
    label: String,
    onBack: () -> Unit,
    onOpenItem: (ReadingListItemDto) -> Unit,
    onOpenSeries: (ReadingListItemDto) -> Unit,
    onOpenIssueDetail: (ReadingListItemDto) -> Unit
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf(KavitaSession()) }
    var entries by remember { mutableStateOf<List<ReadingListItemDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()

    suspend fun loadEntries(initialLoad: Boolean) {
        if (initialLoad) {
            loading = true
            error = null
        } else {
            refreshing = true
        }
        try {
            session = sessionStore.load()
            val (api, _) = KavitaClient(context, sessionStore).buildApi()
            // The endpoint returns the queue in Order order, including repeated series/chapters.
            entries = api.readingListItems(readingListId)
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load reading list $readingListId.", t)
            // A failed pull keeps the rows already on screen.
            if (initialLoad || entries.isEmpty()) error = t.message ?: t.toString()
        } finally {
            if (initialLoad) loading = false else refreshing = false
        }
    }

    LaunchedEffect(readingListId, retryKey) {
        loadEntries(initialLoad = true)
    }

    BrowsePageScaffold(
        title = label,
        modifier = Modifier.navigationBarsPadding(),
        onBack = onBack
    ) {
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState(
                title = "Could not load reading list",
                body = error ?: "Unknown error",
                actionLabel = "Retry",
                onAction = { retryKey++ }
            )
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { loadEntries(initialLoad = false) } },
                state = pullRefreshState,
                indicator = { KamiguraPullToRefreshIndicator(pullRefreshState, refreshing) }
            ) {
                if (entries.isEmpty()) {
                    ReadingListEmpty()
                } else {
                    ReadingListItems(entries, session, onOpenItem, onOpenSeries, onOpenIssueDetail)
                }
            }
        }
    }
}

@Composable
private fun ReadingListEmpty() {
    // Scrollable so the empty state can still be pulled to refresh.
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize()) {
                DarkMessageState("No items", "This reading list is empty.")
            }
        }
    }
}

@Composable
internal fun ReadingListItems(
    entries: List<ReadingListItemDto>,
    session: KavitaSession,
    onOpenItem: (ReadingListItemDto) -> Unit,
    onOpenSeries: (ReadingListItemDto) -> Unit,
    onOpenIssueDetail: (ReadingListItemDto) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Position keys also preserve duplicate chapters if a list contains them more than once.
        itemsIndexed(entries) { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenItem(item) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("${index + 1}", modifier = Modifier.width(32.dp),
                    color = Color(0xFFB9BDBD), style = MaterialTheme.typography.labelMedium)
                AsyncImage(
                    model = chapterCoverUrl(session, item.chapterId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(56.dp).height(84.dp).background(Color(0xFF222626))
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.readingListChapterLabel(),
                        color = Color.White, style = MaterialTheme.typography.titleSmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    // Like Kavita's own list, the series name is the way to the series page.
                    Text(item.seriesName?.takeIf { it.isNotBlank() } ?: "Series ${item.seriesId}",
                        color = Color(0xFF86D39B), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenSeries(item) })
                    if (item.pagesTotal > 0) {
                        Text("${item.pagesRead.coerceIn(0, item.pagesTotal)} / ${item.pagesTotal} pages",
                            color = Color(0xFFB9BDBD), style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = { onOpenIssueDetail(item) }) {
                    Icon(Icons.Outlined.Info, contentDescription = "Issue details", tint = Color(0xFFB9BDBD))
                }
            }
            HorizontalDivider(color = Color(0xFF303636), modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

internal fun ReadingListItemDto.readingListChapterLabel(): String {
    val volume = VolumeDto(id = volumeId, name = volumeNumber).displayName()
    val number = chapterNumber?.trim()?.takeIf { it.isNotEmpty() && it != "-100000" }
    val chapter = when {
        isSpecial -> "Special"
        number != null -> "Chapter $number"
        else -> "Chapter $chapterId"
    }
    val name = chapterTitleName?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
    return listOfNotNull(volume, chapter, name).distinct().joinToString(" | ")
}
