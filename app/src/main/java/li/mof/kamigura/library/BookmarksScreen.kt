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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import li.mof.kamigura.BookmarkDto
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid

@OptIn(ExperimentalMaterial3Api::class)
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
    var bookmarks by remember { mutableStateOf<List<BookmarkDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadBookmarks(initialLoad: Boolean) {
        if (initialLoad) loading = true else refreshing = true
        if (initialLoad) error = null
        try {
            val loadedSession = sessionStore.load()
            session = loadedSession
            val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
            bookmarks = api.allBookmarks()
                .filter { it.seriesId > 0 && it.chapterId > 0 }
                .sortedWith(
                    compareBy<BookmarkDto> { it.series?.name.orEmpty() }
                        .thenBy { it.volumeId }
                        .thenBy { it.chapterId }
                        .thenBy { it.page }
                )
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
                onRefresh = { scope.launch { loadBookmarks(initialLoad = false) } }
            ) {
                if (bookmarks.isEmpty()) {
                    DarkMessageState("Bookmarks", "No bookmarked pages yet.")
                } else {
                    PosterGrid(items = bookmarks, key = { bookmark -> bookmark.id ?: bookmark.stableKey() }) { bookmark ->
                        BookmarkCard(
                            bookmark = bookmark,
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
    bookmark: BookmarkDto,
    session: KavitaSession,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
    ) {
        Column {
            AsyncImage(
                model = bookmarkImageUrl(session, bookmark),
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
                    text = bookmark.displaySubtitle(),
                    color = Color(0xFFB9BDBD),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun bookmarkImageUrl(session: KavitaSession, bookmark: BookmarkDto): String? {
    if (session.baseUrl.isBlank() || session.apiKey.isBlank()) {
        return null
    }
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = Uri.encode(session.apiKey)
    return "$root/api/Reader/bookmark-image?seriesId=${bookmark.seriesId}&apiKey=$apiKey&page=${bookmark.page}"
}

private fun BookmarkDto.displayTitle(): String {
    return series?.name?.takeIf { it.isNotBlank() } ?: "Series $seriesId"
}

private fun BookmarkDto.displaySubtitle(): String {
    val chapter = chapterTitle?.takeIf { it.isNotBlank() } ?: "Chapter $chapterId"
    return "$chapter - Page ${page + 1}"
}

private fun BookmarkDto.stableKey(): String {
    return "$seriesId-$volumeId-$chapterId-$page"
}
