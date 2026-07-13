package li.mof.kamigura.library.internal

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import li.mof.kamigura.KamiguraLog
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
import li.mof.kamigura.library.ReadingListsPane
import li.mof.kamigura.library.SearchSeriesTarget
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.browse.SeriesShelfItemSpacing
import li.mof.kamigura.ui.browse.SeriesShelfItemWidth
import li.mof.kamigura.ui.browse.seriesShelfHeight
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
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Libraries", modifier = modifier) {
        if (libraries.isEmpty()) {
            DarkMessageState(title = "Libraries", body = "No libraries")
        } else {
            LazyColumn(
                state = listState,
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
                .onFailure { KamiguraLog.w("Could not load series count for library ${library.id}.", it) }
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeContent(
    destination: HomeDestination,
    scrollToTopSignal: Int,
    libraries: List<LibraryDto>,
    librarySeriesCounts: Map<Int, Int>,
    isAdmin: Boolean,
    scanningLibraryIds: Set<Int>,
    loading: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
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
    onRemoveWantToRead: (List<SeriesDto>) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onOpenFilteredSeries: (SearchSeriesTarget, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val homeListState = rememberLazyListState()
    val librariesListState = rememberLazyListState()
    val wantToReadGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    var browseDrilldown by rememberSaveable { mutableStateOf<BrowseDrilldown?>(null) }
    var searchQuery by rememberSaveable(initialSearchQuery) { mutableStateOf(initialSearchQuery) }

    LaunchedEffect(destination, scrollToTopSignal) {
        if (scrollToTopSignal <= 0) return@LaunchedEffect
        when (destination) {
            HomeDestination.Home -> homeListState.animateScrollToItem(0)
            HomeDestination.Libraries -> librariesListState.animateScrollToItem(0)
            HomeDestination.WantToRead -> wantToReadGridState.animateScrollToItem(0)
            HomeDestination.Search -> searchListState.animateScrollToItem(0)
            HomeDestination.Browse -> Unit
        }
    }

    Column(modifier.fillMaxSize()) {
        if (destination != HomeDestination.Browse && loading) {
            DarkLoadingState()
            return@Column
        }
        if (destination != HomeDestination.Browse && error != null) {
            DarkMessageState(
                title = "Could not load home",
                body = error,
                actionLabel = "Retry",
                onAction = onRefresh
            )
            return@Column
        }

        when (destination) {
            HomeDestination.Home -> {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    state = pullState,
                    indicator = {
                        // M3 Expressive contained (morphing) loading indicator.
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullState,
                            isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                ) {
                    if (onDeck.isEmpty() && recentlyUpdated.isEmpty() && newlyAdded.isEmpty()) {
                        DarkMessageState(
                            title = "No series",
                            body = "This server did not return visible home shelves."
                        )
                    } else {
                        LazyColumn(
                            state = homeListState,
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
                    listState = librariesListState,
                    modifier = Modifier.fillMaxSize()
                )
            }
            HomeDestination.WantToRead -> {
                if (wantToReadError != null) {
                    DarkMessageState(
                        title = "Could not load Want to Read",
                        body = wantToReadError,
                        actionLabel = "Retry",
                        onAction = onRefresh
                    )
                } else {
                    WantToReadGrid(
                        series = wantToRead,
                        session = session,
                        onSelectSeries = onSelectSeries,
                        onRemove = onRemoveWantToRead,
                        gridState = wantToReadGridState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            HomeDestination.Browse -> {
                when (browseDrilldown) {
                    BrowseDrilldown.ReadingLists -> {
                        ReadingListsPane(
                            api = api,
                            apiError = if (api == null) error else null,
                            onBack = { browseDrilldown = null },
                            onOpenReadingList = { readingList ->
                                onOpenFilteredSeries(
                                    SearchSeriesTarget.ReadingList,
                                    readingList.id,
                                    readingList.title ?: "Reading List ${readingList.id}"
                                )
                            },
                            onRetryApi = onRefresh,
                            statusBarPadding = false
                        )
                    }
                    null -> {
                        BrowseHub(
                            downloadedCount = downloaded.size,
                            onOpenBookmarks = onOpenBookmarks,
                            onOpenCollections = onOpenCollections,
                            onOpenReadingLists = { browseDrilldown = BrowseDrilldown.ReadingLists },
                            onOpenDownloaded = onOpenDownloaded,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            HomeDestination.Search -> {
                HomeSearchScreen(
                    api = api,
                    session = session,
                    historyStore = searchHistoryStore,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    listState = searchListState,
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
    onOpenCollections: () -> Unit,
    onOpenReadingLists: () -> Unit,
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
                title = "Collections",
                subtitle = "Curated series groups",
                icon = Icons.Filled.CollectionsBookmark,
                onClick = onOpenCollections
            )
            BrowseHubItem(
                title = "Reading Lists",
                subtitle = "Ordered reading queues",
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                onClick = onOpenReadingLists
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

private enum class BrowseDrilldown {
    ReadingLists
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
    onRemove: (List<SeriesDto>) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf<List<Int>>(emptyList()) }
    val selectedIdSet = selectedIds.toSet()

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptyList()
    }

    fun toggleSelection(seriesId: Int) {
        selectedIds = if (seriesId in selectedIdSet) {
            selectedIds - seriesId
        } else {
            selectedIds + seriesId
        }
    }

    LaunchedEffect(series) {
        val availableIds = series.mapTo(mutableSetOf()) { it.id }
        selectedIds = selectedIds.filter { it in availableIds }
        if (series.isEmpty()) selectionMode = false
    }

    BackHandler(enabled = selectionMode, onBack = ::exitSelectionMode)

    BrowsePageScaffold(
        title = if (selectionMode) "${selectedIds.size} selected" else "Want to Read",
        modifier = modifier,
        onBack = if (selectionMode) ::exitSelectionMode else null,
        statusBarPadding = false,
        navigationIcon = Icons.Filled.Close,
        navigationContentDescription = "Cancel selection",
        actions = {
            if (selectionMode) {
                IconButton(
                    onClick = {
                        selectedIds = if (selectedIds.size == series.size) {
                            emptyList()
                        } else {
                            series.map { it.id }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = if (selectedIds.size == series.size) "Clear selection" else "Select all",
                        tint = Color.White
                    )
                }
                IconButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        val selected = series.filter { it.id in selectedIdSet }
                        exitSelectionMode()
                        onRemove(selected)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove selected from Want to Read"
                    )
                }
            } else if (series.isNotEmpty()) {
                IconButton(onClick = { selectionMode = true }) {
                    Icon(
                        imageVector = Icons.Filled.Checklist,
                        contentDescription = "Select items",
                        tint = Color.White
                    )
                }
            }
        }
    ) {
        if (series.isEmpty()) {
            DarkMessageState(title = "Want to Read", body = "No series added yet.")
        } else {
            PosterGrid(items = series, key = { it.id }, state = gridState) { item ->
                SelectableSeriesPosterCard(
                    series = item,
                    session = session,
                    selectionMode = selectionMode,
                    selected = item.id in selectedIdSet,
                    onClick = {
                        if (selectionMode) toggleSelection(item.id) else onSelectSeries(item)
                    },
                    onLongClick = {
                        if (!selectionMode) selectionMode = true
                        toggleSelection(item.id)
                    },
                    onSelectionChange = { toggleSelection(item.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableSeriesPosterCard(
    series: SeriesDto,
    session: KavitaSession,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectionChange: () -> Unit
) {
    Box {
        SeriesPosterCard(
            series = series,
            session = session,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        )
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(KavitaCoverAspectRatio)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        else Color.Black.copy(alpha = 0.12f)
                    )
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectionChange() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.62f), MaterialTheme.shapes.small)
                )
            }
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
            val cardShape = MaterialTheme.shapes.small
            // A shelf of fixed-width poster cards. Dimensions live in BrowseComponents so Home
            // and Search stay in sync. A plain LazyRow is used instead of a Carousel: the
            // carousel keeps a masked cut-off item at the right edge whose clip rect trembled
            // against the overscroll spring, and the uniform cards here don't need that masking.
            val shelfHeight = seriesShelfHeight()
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(SeriesShelfItemSpacing)
            ) {
                items(series, key = { it.id }) { item ->
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        shape = cardShape,
                        coverFillsHeight = true,
                        modifier = Modifier
                            .width(SeriesShelfItemWidth)
                            .height(shelfHeight)
                            .clickable { onSelectSeries(item) }
                    )
                }
            }
        }
    }
}

