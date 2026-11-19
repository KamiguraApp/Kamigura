package li.mof.kamigura.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import li.mof.kamigura.BookmarkPageDto
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.reader.internal.readerMenuSubtitle
import li.mof.kamigura.reader.internal.readerPageLabel
import li.mof.kamigura.series.internal.issueLabel
import li.mof.kamigura.series.internal.readerIssueLabel
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KamiguraPullToRefreshIndicator
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SelectablePosterCard
import li.mof.kamigura.ui.browse.SelectablePosterScaffold

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
    var deleting by remember { mutableStateOf(false) }
    var thumbnailsCurrent by remember { mutableStateOf(true) }
    val feedback = remember { SnackbarHostState() }
    val pullRefreshState = rememberPullToRefreshState()

    suspend fun reload(api: KavitaApi) {
        val loaded = api.allBookmarks().filter { it.seriesId > 0 && it.chapterId > 0 }
        // Bookmarks carry no chapter number, so read each bookmarked series' chapters once.
        val volumesBySeries = coroutineScope {
            loaded.map { it.seriesId }.distinct().map { seriesId ->
                async {
                    try {
                        seriesId to api.volumes(seriesId)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Exception) {
                        KamiguraLog.w("Could not load chapters for bookmarked series $seriesId.", t)
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        bookmarks = bookmarkEntries(loaded, volumesBySeries)
        thumbnailsCurrent = true
        error = null
    }

    suspend fun loadBookmarks(initialLoad: Boolean) {
        try {
            session = sessionStore.load()
            val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
            reload(api)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Exception) {
            KamiguraLog.w("Could not load bookmarks.", t)
            if (bookmarks.isEmpty()) error = t.message ?: t.toString()
            else if (!thumbnailsCurrent) scope.launch { feedback.showSnackbar("Could not refresh bookmarks.") }
        } finally {
            if (initialLoad) loading = false else refreshing = false
        }
    }

    fun deleteSelected(keys: Set<String>) {
        if (deleting || refreshing || loading) return
        val targets = bookmarks.filter { it.key in keys }
        if (targets.isEmpty()) return
        deleting = true
        feedback.currentSnackbarData?.dismiss()
        scope.launch {
            var removed = 0
            var refreshFailed = false
            try {
                val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
                val removedKeys = mutableSetOf<String>()
                // Positions can change as soon as the first request reaches the server.
                thumbnailsCurrent = false
                for (entry in targets) {
                    val bookmark = entry.bookmark
                    try {
                        api.unbookmarkPage(BookmarkPageDto(
                            bookmark.seriesId, bookmark.volumeId, bookmark.chapterId, bookmark.page, bookmark.imageOffset
                        ))
                        removedKeys += entry.key
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Exception) {
                        KamiguraLog.w("Could not remove bookmark for chapter ${bookmark.chapterId}.", t)
                    }
                }
                removed = removedKeys.size
                bookmarks = bookmarks.filterNot { it.key in removedKeys }
                reload(api)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Exception) {
                refreshFailed = !thumbnailsCurrent
                KamiguraLog.w("Could not complete bookmark removal.", t)
            } finally {
                deleting = false
            }
            val failed = targets.size - removed
            val message = buildList {
                if (removed > 0) add(if (removed == 1) "Removed 1 bookmark." else "Removed $removed bookmarks.")
                if (failed > 0) add(if (failed == 1) "Could not remove 1 bookmark." else "Could not remove $failed bookmarks.")
                if (refreshFailed) add("Could not refresh bookmarks.")
            }.joinToString(" ")
            feedback.showSnackbar(message, withDismissAction = true)
        }
    }

    LaunchedEffect(retryKey) {
        loadBookmarks(initialLoad = true)
    }

    SelectablePosterScaffold(
        title = "Bookmarks",
        keys = if (loading || error != null) null else bookmarks.map { it.key },
        deleteLabel = "Delete selected bookmarks",
        onBack = onBack,
        onDelete = ::deleteSelected,
        exitOnDelete = false,
        busy = refreshing || deleting
    ) { selecting, selected, toggle ->
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState(
                title = "Could not load bookmarks",
                body = error ?: "Unknown error",
                actionLabel = "Retry",
                onAction = {
                    loading = true
                    error = null
                    retryKey++
                }
            )
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    if (!refreshing && !deleting) {
                        refreshing = true
                        scope.launch { loadBookmarks(initialLoad = false) }
                    }
                },
                state = pullRefreshState,
                indicator = { KamiguraPullToRefreshIndicator(pullRefreshState, refreshing) }
            ) {
                if (bookmarks.isEmpty()) {
                    DarkMessageState("Bookmarks", "No bookmarked pages yet.")
                } else {
                    PosterGrid(items = bookmarks, key = { it.key }) { entry ->
                        val bookmark = entry.bookmark
                        BookmarkCard(
                            entry = entry,
                            imageUrl = if (thumbnailsCurrent) bookmarkImageUrl(session, entry) else null,
                            selecting = selecting,
                            selected = entry.key in selected,
                            enabled = !refreshing && !deleting,
                            onToggle = { toggle(entry.key) },
                            onOpen = {
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
        SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
@Composable
private fun BookmarkCard(
    entry: BookmarkEntry,
    imageUrl: String?,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val bookmark = entry.bookmark
    SelectablePosterCard(selecting, selected, onOpen, onToggle, enabled) {
        AsyncImage(
            model = imageUrl,
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
                maxLines = 1,
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

internal fun BookmarkEntry.displaySubtitle(): String = readerMenuSubtitle(
    chapterLabel ?: ChapterDto(bookmark.chapterId, title = bookmark.chapterTitle).issueLabel("#").orEmpty(),
    readerPageLabel(bookmark.page)
)

internal data class BookmarkEntry(
    val bookmark: BookmarkDto,
    /** Position used by Kavita's bookmark-image endpoint: the series' bookmarks, oldest first. */
    val imageIndex: Int,
    val chapterLabel: String?
) {
    val key: String get() = bookmark.id?.let { "id-$it" }
        ?: with(bookmark) { "page-$seriesId-$volumeId-$chapterId-$page-$imageOffset" }
}

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
                volume.readerIssueLabel(chapter).takeIf { it.isNotEmpty() }?.let { chapterLabels[chapter.id] = it }
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
