package li.mof.kamigura.library.internal

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.LibraryDto
import li.mof.kamigura.SearchHistoryStore
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.UpdateWantToReadDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.library.HomeShelfKind
import li.mof.kamigura.library.HomeSearchScreen
import li.mof.kamigura.library.SearchSeriesTarget
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.theme.KamiguraSurface

private val PaginationHeaderJson = Json { ignoreUnknownKeys = true }

@Composable
private fun LibraryHub(
    libraries: List<LibraryDto>,
    seriesCounts: Map<Int, Int>,
    isAdmin: Boolean,
    scanningLibraryIds: Set<Int>,
    session: KavitaSession,
    onSelectLibrary: (LibraryDto) -> Unit,
    onScanLibrary: (LibraryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Libraries", modifier = modifier) {
        if (libraries.isEmpty()) {
            DarkMessageState(title = "Libraries", body = "No libraries")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(libraries, key = { it.id }) { library ->
                    Surface(
                        color = KamiguraSurface,
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLibrary(library) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LibraryIcon(library, session)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    library.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                seriesCounts[library.id]?.let { count ->
                                    Text(
                                        "$count series",
                                        color = Color(0xFFB9BDBD),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (isAdmin) {
                                IconButton(
                                    onClick = { onScanLibrary(library) },
                                    enabled = library.id !in scanningLibraryIds
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = "Scan ${library.name}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryIcon(library: LibraryDto, session: KavitaSession) {
    val hasRemoteCover = session.baseUrl.isNotBlank() &&
        session.apiKey.isNotBlank() &&
        library.coverImage?.isNotBlank() == true
    var coverLoaded by remember(session.baseUrl, session.apiKey, library.id, library.coverImage) {
        mutableStateOf(false)
    }

    Surface(
        modifier = Modifier.size(48.dp),
        color = Color(0xFF273A32),
        contentColor = Color(0xFFD3EEE3),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!coverLoaded) {
                Text(
                    library.iconText(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (hasRemoteCover) {
                AsyncImage(
                    model = libraryCoverUrl(session, library.id),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                    onSuccess = { coverLoaded = true },
                    onError = { coverLoaded = false }
                )
            }
        }
    }
}

private fun libraryCoverUrl(session: KavitaSession, libraryId: Int): String {
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = session.apiKey.takeIf { it.isNotBlank() }?.let { "&apiKey=${Uri.encode(it)}" }.orEmpty()
    // Library icons are user-managed Kavita images, so the fallback initial remains visible until the endpoint proves usable.
    return "$root/api/Image/library-cover?libraryId=$libraryId$apiKey"
}

/** Internal to library, not for external use. */
internal suspend fun loadLibrarySeriesCounts(
    api: KavitaApi,
    libraries: List<LibraryDto>
): Map<Int, Int> = coroutineScope {
    libraries.map { library ->
        async {
            runCatching { api.librarySeriesCount(library.id) }
                .getOrNull()
                ?.let { count -> library.id to count }
        }
    }.awaitAll().filterNotNull().toMap()
}

private suspend fun KavitaApi.librarySeriesCount(libraryId: Int): Int? {
    val response = allSeriesV2Response(
        body = SeriesFilterV2Dto(
            statements = listOf(
                SeriesFilterStatementDto(
                    comparison = 0,
                    field = 19,
                    value = libraryId.toString()
                )
            )
        ),
        pageNumber = 0,
        pageSize = 1
    )
    if (!response.isSuccessful) return null

    // Request one item and read the pagination total; fetching full series lists here would make Home startup scale poorly.
    val header = response.headers()["Pagination"]
        ?: response.headers()["X-Pagination"]
        ?: return if (response.body().isNullOrEmpty()) 0 else null
    val pagination = runCatching {
        PaginationHeaderJson.parseToJsonElement(header).jsonObject
    }.getOrNull() ?: return null
    return pagination.entries
        .firstOrNull { (key, _) ->
            key.equals("totalItems", ignoreCase = true) ||
                key.equals("totalCount", ignoreCase = true)
        }
        ?.value
        ?.jsonPrimitive
        ?.intOrNull
}

private fun LibraryDto.iconText(): String {
    return name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "L"
}

@Composable
private fun HomePlaceholder(destination: HomeDestination, modifier: Modifier = Modifier) {
    DarkMessageState(
        title = destination.label,
        body = "This section is not implemented yet."
    )
}

/** Internal to library, not for external use. */
@Composable
internal fun HomeContent(
    destination: HomeDestination,
    libraries: List<LibraryDto>,
    librarySeriesCounts: Map<Int, Int>,
    isAdmin: Boolean,
    scanningLibraryIds: Set<Int>,
    loading: Boolean,
    error: String?,
    session: KavitaSession,
    onDeck: List<SeriesDto>,
    recentlyUpdated: List<SeriesDto>,
    newlyAdded: List<SeriesDto>,
    wantToRead: List<SeriesDto>,
    wantToReadError: String?,
    downloaded: List<OfflineIssueRecord>,
    api: KavitaApi?,
    searchHistoryStore: SearchHistoryStore,
    initialSearchQuery: String = "",
    onSelectLibrary: (LibraryDto) -> Unit,
    onScanLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit,
    onOpenShelf: (HomeShelfKind) -> Unit,
    onRemoveWantToRead: (SeriesDto) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onOpenFilteredSeries: (SearchSeriesTarget, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        if (destination != HomeDestination.Browse && loading) {
            DarkLoadingState()
            return@Column
        }
        if (destination != HomeDestination.Browse && error != null) {
            DarkMessageState(title = "Could not load home", body = error)
            return@Column
        }

        when (destination) {
            HomeDestination.Home -> {
                if (onDeck.isEmpty() && recentlyUpdated.isEmpty() && newlyAdded.isEmpty()) {
                    DarkMessageState(
                        title = "No series",
                        body = "This server did not return visible home shelves."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        item {
                            HomeShelf(HomeShelfKind.OnDeck, onDeck, session, onOpenShelf, onSelectSeries)
                        }
                        item {
                            HomeShelf(
                                HomeShelfKind.RecentlyUpdated,
                                recentlyUpdated,
                                session,
                                onOpenShelf,
                                onSelectSeries
                            )
                        }
                        item {
                            HomeShelf(HomeShelfKind.NewlyAdded, newlyAdded, session, onOpenShelf, onSelectSeries)
                        }
                    }
                }
            }
            HomeDestination.Libraries -> {
                LibraryHub(
                    libraries = libraries,
                    seriesCounts = librarySeriesCounts,
                    isAdmin = isAdmin,
                    scanningLibraryIds = scanningLibraryIds,
                    session = session,
                    onSelectLibrary = onSelectLibrary,
                    onScanLibrary = onScanLibrary,
                    modifier = Modifier.fillMaxSize()
                )
            }
            HomeDestination.WantToRead -> {
                if (wantToReadError != null) {
                    DarkMessageState(
                        title = "Could not load Want to Read",
                        body = wantToReadError
                    )
                } else {
                    WantToReadGrid(
                        series = wantToRead,
                        session = session,
                        onSelectSeries = onSelectSeries,
                        onRemove = onRemoveWantToRead,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            HomeDestination.Browse -> {
                BrowseHub(
                    downloadedCount = downloaded.size,
                    onOpenBookmarks = onOpenBookmarks,
                    onOpenDownloaded = onOpenDownloaded,
                    modifier = Modifier.fillMaxSize()
                )
            }
            HomeDestination.Search -> {
                HomeSearchScreen(
                    api = api,
                    session = session,
                    historyStore = searchHistoryStore,
                    initialQuery = initialSearchQuery,
                    onSelectSeries = onSelectSeries,
                    onOpenFilteredSeries = onOpenFilteredSeries,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun BrowseHub(
    downloadedCount: Int,
    onOpenBookmarks: () -> Unit,
    onOpenDownloaded: () -> Unit,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Browse", modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BrowseHubItem(
                title = "Bookmarks",
                subtitle = "Bookmarked pages",
                icon = Icons.Filled.BookmarkBorder,
                onClick = onOpenBookmarks
            )
            BrowseHubItem(
                title = "Downloaded",
                subtitle = when (downloadedCount) {
                    0 -> "No issues available offline"
                    1 -> "1 issue available offline"
                    else -> "$downloadedCount issues available offline"
                },
                icon = Icons.Filled.Download,
                onClick = onOpenDownloaded
            )
        }
    }
}

@Composable
private fun BrowseHubItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        color = KamiguraSurface,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF273A32),
                contentColor = Color(0xFFD3EEE3),
                shape = MaterialTheme.shapes.small
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color(0xFFB9BDBD),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun WantToReadGrid(
    series: List<SeriesDto>,
    session: KavitaSession,
    onSelectSeries: (SeriesDto) -> Unit,
    onRemove: (SeriesDto) -> Unit,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Want to Read", modifier = modifier) {
        if (series.isEmpty()) {
            DarkMessageState(title = "Want to Read", body = "No series added yet.")
        } else {
            PosterGrid(items = series, key = { it.id }) { item ->
                SeriesPosterCardMenu(
                    series = item,
                    session = session,
                    onClick = { onSelectSeries(item) },
                    onRemove = { onRemove(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesPosterCardMenu(
    series: SeriesDto,
    session: KavitaSession,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        SeriesPosterCard(
            series = series,
            session = session,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Remove from Want to Read") },
                onClick = {
                    menuExpanded = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun HomeShelf(
    kind: HomeShelfKind,
    series: List<SeriesDto>,
    session: KavitaSession,
    onOpenShelf: (HomeShelfKind) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenShelf(kind) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                kind.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open ${kind.title}",
                tint = Color(0xFFE6EAEA)
            )
        }
        Spacer(Modifier.height(10.dp))
        if (series.isEmpty()) {
            Text("Nothing here yet", color = Color(0xFF9FA5A5), style = MaterialTheme.typography.bodyMedium)
        } else {
            val carouselState = rememberCarouselState { series.size }
            val cardShape = MaterialTheme.shapes.small
            HorizontalMultiBrowseCarousel(
                state = carouselState,
                preferredItemWidth = 164.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                itemSpacing = 14.dp,
                minSmallItemWidth = 48.dp,
                maxSmallItemWidth = 72.dp,
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) { index ->
                val item = series[index]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .maskClip(cardShape)
                ) {
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        shape = cardShape,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onSelectSeries(item) }
                    )
                }
            }
        }
    }
}

