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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
    var session by remember { mutableStateOf(KavitaSession()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
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
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load bookmarks.", t)
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    BrowsePageScaffold(title = "Bookmarks", onBack = onBack) {
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState("Could not load bookmarks", error ?: "Unknown error")
            bookmarks.isEmpty() -> DarkMessageState("Bookmarks", "No bookmarked pages yet.")
            else -> PosterGrid(items = bookmarks, key = { bookmark -> bookmark.id ?: bookmark.stableKey() }) { bookmark ->
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
