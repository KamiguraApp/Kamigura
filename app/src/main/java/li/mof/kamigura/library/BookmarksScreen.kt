package li.mof.kamigura.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import li.mof.kamigura.BookmarkDto
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.series.internal.displayShortName
import li.mof.kamigura.series.internal.displayTitle
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KamiguraPullToRefreshIndicator
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BookmarksScreen(
    sessionStore: KavitaSessionStore,
    onBack: () -> Unit,
    onOpenBookmark: (
        libraryId: Int,
        seriesId: Int,
        volumeId: Int,
        chapterId: Int,
        page: Int
    ) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(KavitaSession()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    suspend fun loadBookmarks(initialLoad: Boolean) {
        if (initialLoad) loading = true else refreshing = true
        if (initialLoad) error = null
        try {
            val loadedSession = sessionStore.load()
            session = loadedSession
            val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
            val loaded = api.allBookmarks().filter { it.seriesId > 0 && it.chapterId > 0 }
            // Bookmarks carry no chapter number, so read each bookmarked series' chapters once.
            val volumesBySeries = coroutineScope {
                loaded.map { it.seriesId }.distinct().map { seriesId ->
                    async {
                        runCatching { seriesId to api.volumes(seriesId) }
                            .onFailure { KamiguraLog.w("Could not load chapters for bookmarked series $seriesId.", it) }
                            .getOrNull()
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            bookmarks = bookmarkEntries(loaded, volumesBySeries)
            error = null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load bookmarks.", t)
            if (bookmarks.isEmpty()) error = t.message ?: t.toString()
        } finally {
            if (initialLoad) loading = false else refreshing = false
        }
    }

    LaunchedEffect(retryKey) {
        loadBookmarks(initialLoad = true)
    }

    BrowsePageScaffold(title = "Bookmarks", onBack = onBack) {
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState(
                title = "Could not load bookmarks",
                body = error ?: "Unknown error",
                actionLabel = "Retry",
                onAction = { retryKey++ }
            )
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { loadBookmarks(initialLoad = false) } },
                state = pullRefreshState,
                indicator = { KamiguraPullToRefreshIndicator(pullRefreshState, refreshing) }
            ) {
                if (bookmarks.isEmpty()) {
                    DarkMessageState("Bookmarks", "No bookmarked pages yet.")
                } else {
                    PosterGrid(items = bookmarks, key = { entry -> entry.bookmark.id ?: entry.bookmark.stableKey() }) { entry ->
                        val bookmark = entry.bookmark
                        BookmarkCard(
                            entry = entry,
                            session = session,
                            onClick = {
                                onOpenBookmark(
                                    bookmark.series?.libraryId ?: 0,
                                    bookmark.seriesId,
                                    bookmark.volumeId,
                                    bookmark.chapterId,
                                    bookmark.page.coerceAtLeast(0)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkCard(
    entry: BookmarkEntry,
    session: KavitaSession,
    onClick: () -> Unit
) {
    val bookmark = entry.bookmark
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
    ) {
        Column {
            AsyncImage(
                model = bookmarkImageUrl(session, entry),
                contentDescription = bookmark.displayTitle(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(KavitaCoverAspectRatio)
                    .background(Color(0xFF111111)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(
                    text = bookmark.displayTitle(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = entry.displaySubtitle(),
                    color = Color(0xFFB9BDBD),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun bookmarkImageUrl(session: KavitaSession, entry: BookmarkEntry): String? {
    if (session.baseUrl.isBlank() || session.apiKey.isBlank()) {
        return null
    }
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = Uri.encode(session.apiKey)
    val bookmark = entry.bookmark
    // page is a position in the series' bookmarks, not the chapter page. Kavita ignores
    // bookmarkId; it keeps the cache key apart when deleting a bookmark shifts the positions.
    return "$root/api/Reader/bookmark-image?seriesId=${bookmark.seriesId}&apiKey=$apiKey" +
        "&page=${entry.imageIndex}&bookmarkId=${bookmark.id ?: 0}"
}

private fun BookmarkDto.displayTitle(): String {
    return series?.name?.takeIf { it.isNotBlank() } ?: "Series $seriesId"
}

private fun BookmarkEntry.displaySubtitle(): String {
    val page = "Page ${bookmark.page + 1}"
    val chapter = bookmark.chapterTitle?.takeIf { it.isNotBlank() } ?: chapterLabel
    return if (chapter == null) page else "$chapter - $page"
}

internal data class BookmarkEntry(
    val bookmark: BookmarkDto,
    /** Position used by Kavita's bookmark-image endpoint: the series' bookmarks, oldest first. */
    val imageIndex: Int,
    val chapterLabel: String?
)

internal fun bookmarkEntries(
    bookmarks: List<BookmarkDto>,
    volumesBySeries: Map<Int, List<VolumeDto>>
): List<BookmarkEntry> {
    // Kavita caches a series' bookmark images in creation order; ids grow with creation.
    val imageIndex = HashMap<BookmarkDto, Int>()
    bookmarks.groupBy { it.seriesId }.values.forEach { seriesBookmarks ->
        seriesBookmarks.sortedBy { it.id ?: Int.MAX_VALUE }
            .forEachIndexed { index, bookmark -> imageIndex[bookmark] = index }
    }
    val readingOrder = HashMap<Int, Int>()
    val chapterLabels = HashMap<Int, String>()
    volumesBySeries.values.forEach { volumes ->
        volumes.flatMap { volume -> volume.chapters.map { volume to it } }
            .forEachIndexed { index, (volume, chapter) ->
                readingOrder[chapter.id] = index
                chapterLabels[chapter.id] = listOfNotNull(chapter.displayTitle(), volume.displayShortName())
                    .distinct()
                    .joinToString(" • ")
            }
    }
    return bookmarks
        .map { BookmarkEntry(it, imageIndex.getValue(it), chapterLabels[it.chapterId]) }
        .sortedWith(
            compareBy<BookmarkEntry> { it.bookmark.series?.name.orEmpty() }
                .thenBy { it.bookmark.seriesId }
                .thenBy { readingOrder[it.bookmark.chapterId] ?: Int.MAX_VALUE }
                .thenBy { it.bookmark.chapterId }
                .thenBy { it.bookmark.page }
        )
}

private fun BookmarkDto.stableKey(): String {
    return "$seriesId-$volumeId-$chapterId-$page"
}
