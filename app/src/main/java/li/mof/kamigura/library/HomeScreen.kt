package li.mof.kamigura.library

import android.net.Uri
import android.widget.Toast

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.GroupedSeriesDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.LibraryDto
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.UpdateWantToReadDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.SearchHistoryStore
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.localCoverFile
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.series.IssueDetailSideSheet
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.series.internal.coverActionColor
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.update.AvailableUpdate
import li.mof.kamigura.library.internal.HomeShell
import li.mof.kamigura.library.internal.loadLibrarySeriesCounts

private val PaginationHeaderJson = Json { ignoreUnknownKeys = true }

enum class HomeShelfKind(
    val routeValue: String,
    val title: String,
    val emptyMessage: String
) {
    OnDeck("on-deck", "On Deck", "No on-deck series"),
    RecentlyUpdated("recently-updated", "Recently Updated Series", "No recently updated series"),
    NewlyAdded("newly-added", "Newly Added Series", "No newly added series");

    companion object {
        fun fromRouteValue(value: String?): HomeShelfKind? {
            return entries.firstOrNull { it.routeValue == value }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sessionStore: KavitaSessionStore,
    sessionRevision: Int,
    initialSearchQuery: String = "",
    availableUpdate: AvailableUpdate? = null,
    onOpenUpdate: (String) -> Unit = {},
    onUpdateNoticeShown: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenShelf: (HomeShelfKind) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenDownloaded: () -> Unit,
    onOpenFilteredSeries: (SearchSeriesTarget, Int, String) -> Unit,
    onSelectLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }
    val searchHistoryStore = remember(ctx) { SearchHistoryStore(ctx) }
    val updateSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(availableUpdate) {
        val update = availableUpdate ?: return@LaunchedEffect
        onUpdateNoticeShown()
        val result = updateSnackbarHostState.showSnackbar(
            message = "Kamigura ${update.tagName} is available",
            actionLabel = "View release",
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite
        )
        if (result == SnackbarResult.ActionPerformed) {
            onOpenUpdate(update.releaseUrl)
        }
    }

    var libs by remember { mutableStateOf<List<LibraryDto>>(emptyList()) }
    var librarySeriesCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var onDeck by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var recentlyUpdated by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var newlyAdded by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var wantToRead by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var wantToReadError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var serverName by remember { mutableStateOf("No server selected") }
    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var isAdmin by remember { mutableStateOf(false) }
    var scanningLibraryIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val downloadedFlow = remember(session.baseUrl, session.username, session.apiKey) {
        offlineRepository.observeDownloaded(session)
    }
    val downloaded by downloadedFlow.collectAsState(initial = emptyList())

    LaunchedEffect(sessionRevision) {
        libs = emptyList()
        librarySeriesCounts = emptyMap()
        onDeck = emptyList()
        recentlyUpdated = emptyList()
        newlyAdded = emptyList()
        wantToRead = emptyList()
        wantToReadError = null
        error = null
        loading = true
        api = null
        isAdmin = false
        scanningLibraryIds = emptySet()
        try {
            serverName = sessionStore.activeProfile()?.name ?: "No server selected"
            session = sessionStore.load()
            runCatching { offlineRepository.ensureLocalCovers(session) }
                .onFailure { KamiguraLog.w("Could not ensure local covers on Home.", it) }
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, _) = client.buildApi()
            api = loadedApi
            val loadedLibraries = loadedApi.userLibraries().sortedBy { it.id }
            libs = loadedLibraries
            launch {
                isAdmin = runCatching {
                    loadedApi.currentUser().roles.orEmpty()
                        .any { it.equals("Admin", ignoreCase = true) }
                }.onFailure {
                    KamiguraLog.w("Could not load current user roles on Home.", it)
                }.getOrDefault(false)
            }
            launch {
                librarySeriesCounts = loadLibrarySeriesCounts(loadedApi, loadedLibraries)
            }
            onDeck = loadedApi.onDeck(pageSize = HomePreviewShelfPageSize)
            recentlyUpdated = loadedApi.recentlyUpdatedSeries(pageSize = HomePreviewShelfPageSize)
                .map { it.toSeriesDto() }
                .distinctBy { it.id }
            newlyAdded = loadedApi.recentlyAdded(pageSize = HomePreviewShelfPageSize)
            runCatching { loadedApi.wantToRead(pageSize = 200) }
                .onSuccess { wantToRead = it }
                .onFailure {
                    KamiguraLog.w("Could not load Want to Read list.", it)
                    wantToReadError = it.message ?: it.toString()
                }
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load Home.", t)
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    fun removeFromWantToRead(series: SeriesDto) {
        val currentApi = api ?: return
        scope.launch {
            runCatching {
                currentApi.removeSeriesFromWantToRead(UpdateWantToReadDto(listOf(series.id)))
            }.onSuccess {
                wantToRead = wantToRead.filterNot { it.id == series.id }
                Toast.makeText(ctx, "Removed from Want to Read", Toast.LENGTH_SHORT).show()
            }.onFailure {
                KamiguraLog.w("Could not remove series from Want to Read.", it)
                Toast.makeText(ctx, "Could not update Want to Read", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun scanLibrary(library: LibraryDto) {
        val currentApi = api ?: return
        if (library.id in scanningLibraryIds) return
        scope.launch {
            scanningLibraryIds = scanningLibraryIds + library.id
            runCatching {
                currentApi.scanLibrary(library.id)
            }.onSuccess {
                Toast.makeText(
                    ctx,
                    "${library.name} scan requested",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                KamiguraLog.w("Could not scan library ${library.id}.", it)
                Toast.makeText(
                    ctx,
                    "Could not scan ${library.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            scanningLibraryIds = scanningLibraryIds - library.id
        }
    }

    Box(Modifier.fillMaxSize()) {
        HomeShell(
            libraries = libs,
            librarySeriesCounts = librarySeriesCounts,
            isAdmin = isAdmin,
            scanningLibraryIds = scanningLibraryIds,
            serverName = serverName,
            loading = loading,
            error = error,
            session = session,
            onDeck = onDeck,
            recentlyUpdated = recentlyUpdated,
            newlyAdded = newlyAdded,
            wantToRead = wantToRead,
            wantToReadError = wantToReadError,
            downloaded = downloaded,
            api = api,
            searchHistoryStore = searchHistoryStore,
            initialSearchQuery = initialSearchQuery,
            onOpenSettings = onOpenSettings,
            onOpenShelf = onOpenShelf,
            onOpenBookmarks = onOpenBookmarks,
            onOpenCollections = onOpenCollections,
            onOpenDownloaded = onOpenDownloaded,
            onOpenFilteredSeries = onOpenFilteredSeries,
            onSelectLibrary = onSelectLibrary,
            onScanLibrary = ::scanLibrary,
            onSelectSeries = onSelectSeries,
            onRemoveWantToRead = ::removeFromWantToRead
        )
        SnackbarHost(
            hostState = updateSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        )
    }
}

private const val HomePreviewShelfPageSize = 20

private fun GroupedSeriesDto.toSeriesDto(): SeriesDto {
    return SeriesDto(
        id = seriesId,
        name = seriesName ?: "Series $seriesId",
        libraryId = libraryId
    )
}
