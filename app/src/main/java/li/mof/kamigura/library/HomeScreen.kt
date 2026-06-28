package li.mof.kamigura.library

import android.net.Uri
import android.widget.Toast

import androidx.activity.compose.BackHandler
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
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.localCoverFile
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.series.IssueDetailSideSheet
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.series.coverActionColor
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.update.AvailableUpdate

private enum class HomeDestination(
    val label: String,
    val icon: ImageVector,
    val expandedLabel: String = label
) {
    Home("Home", Icons.Filled.Home),
    Libraries("Libraries", Icons.Filled.CollectionsBookmark),
    WantToRead("Want", Icons.Filled.BookmarkBorder, "Want to Read"),
    Browse("Browse", Icons.Filled.Explore),
    Search("Search", Icons.Filled.Search)
}

private val PaginationHeaderJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sessionStore: KavitaSessionStore,
    sessionRevision: Int,
    availableUpdate: AvailableUpdate? = null,
    onOpenUpdate: (String) -> Unit = {},
    onUpdateNoticeShown: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onSelectLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit,
    onPickIssue: (
        libraryId: Int,
        seriesId: Int,
        volumeId: Int,
        chapterId: Int,
        incognito: Boolean
    ) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }
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
    var selectedDownload by remember { mutableStateOf<OfflineIssueRecord?>(null) }
    var selectedChapter by remember { mutableStateOf<ChapterDto?>(null) }
    var selectedVolume by remember { mutableStateOf<VolumeDto?>(null) }
    var issueLoading by remember { mutableStateOf(false) }
    var issueActionBusy by remember { mutableStateOf(false) }

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
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, _) = client.buildApi()
            api = loadedApi
            val loadedLibraries = loadedApi.userLibraries().sortedBy { it.id }
            libs = loadedLibraries
            launch {
                isAdmin = runCatching {
                    loadedApi.currentUser().roles.orEmpty()
                        .any { it.equals("Admin", ignoreCase = true) }
                }.getOrDefault(false)
            }
            launch {
                librarySeriesCounts = loadLibrarySeriesCounts(loadedApi, loadedLibraries)
            }
            onDeck = loadedApi.onDeck(pageSize = 12)
            recentlyUpdated = loadedApi.recentlyUpdatedSeries(pageSize = 16)
                .map { it.toSeriesDto() }
                .distinctBy { it.id }
            newlyAdded = loadedApi.recentlyAdded(pageSize = 18)
            runCatching { loadedApi.wantToRead(pageSize = 200) }
                .onSuccess { wantToRead = it }
                .onFailure { wantToReadError = it.message ?: it.toString() }
        } catch (t: Throwable) {
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
                Toast.makeText(
                    ctx,
                    "Could not scan ${library.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            scanningLibraryIds = scanningLibraryIds - library.id
        }
    }

    fun openDownloaded(record: OfflineIssueRecord) {
        selectedDownload = record
        val fallbackChapter = ChapterDto(
            id = record.chapterId,
            title = record.issueName,
            pages = record.pageCount.takeIf { it > 0 },
            pagesRead = record.localPage,
            volumeId = record.volumeId
        )
        selectedChapter = fallbackChapter
        selectedVolume = VolumeDto(
            id = record.volumeId,
            name = record.issueName,
            chapters = listOf(fallbackChapter)
        )
        val currentApi = api
        issueLoading = currentApi != null
        if (currentApi == null) return
        scope.launch {
            val detail = runCatching { currentApi.seriesChapter(record.chapterId) }.getOrNull()
            val volume = runCatching { currentApi.volumes(record.seriesId) }.getOrNull()
                ?.firstOrNull { candidate ->
                    candidate.id == record.volumeId || candidate.chapters.any { it.id == record.chapterId }
                }
            if (selectedDownload?.chapterId == record.chapterId) {
                selectedChapter = detail ?: fallbackChapter
                selectedVolume = volume ?: detail?.let {
                    VolumeDto(id = record.volumeId, name = record.issueName, chapters = listOf(it))
                } ?: selectedVolume
                issueLoading = false
            }
        }
    }

    fun deleteDownloaded(record: OfflineIssueRecord, closeSheet: Boolean = false) {
        scope.launch {
            issueActionBusy = true
            runCatching { offlineRepository.remove(session, record.chapterId) }
                .onSuccess {
                    if (closeSheet && selectedDownload?.chapterId == record.chapterId) {
                        selectedDownload = null
                        selectedChapter = null
                        selectedVolume = null
                    }
                    Toast.makeText(ctx, "Download deleted", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(ctx, "Could not delete download", Toast.LENGTH_SHORT).show()
                }
            issueActionBusy = false
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
            onOpenSettings = onOpenSettings,
            onSelectLibrary = onSelectLibrary,
            onScanLibrary = ::scanLibrary,
            onSelectSeries = onSelectSeries,
            onRemoveWantToRead = ::removeFromWantToRead,
            onSelectDownloaded = ::openDownloaded,
            onDeleteDownloaded = { deleteDownloaded(it) }
        )
        SnackbarHost(
            hostState = updateSnackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        )
    }

    val selectedRecord = selectedDownload
    val detail = selectedChapter
    val actionColor = detail?.coverActionColor(Color(0xFF6A5BB7)) ?: Color(0xFF6A5BB7)
    IssueDetailSideSheet(
        visible = selectedRecord != null,
        seriesName = selectedRecord?.seriesName.orEmpty(),
        volume = selectedVolume,
        chapter = detail,
        fileSizeBytes = selectedRecord?.totalBytes,
        downloadRecord = selectedRecord,
        loading = issueLoading,
        actionBusy = issueActionBusy,
        session = session,
        actionColor = actionColor,
        onDismissRequest = {
            selectedDownload = null
            selectedChapter = null
            selectedVolume = null
        },
        onRead = {
            selectedRecord?.let {
                onPickIssue(it.libraryId, it.seriesId, it.volumeId, it.chapterId, false)
            }
        },
        onReadIncognito = {
            selectedRecord?.let {
                onPickIssue(it.libraryId, it.seriesId, it.volumeId, it.chapterId, true)
            }
        },
        onMarkRead = {
            val record = selectedRecord ?: return@IssueDetailSideSheet
            val currentApi = api
            scope.launch {
                issueActionBusy = true
                runCatching {
                    if (currentApi != null) {
                        currentApi.markChapterRead(MarkChapterReadDto(record.seriesId, record.chapterId, false))
                    } else {
                        offlineRepository.saveLocalProgress(
                            session = session,
                            chapterId = record.chapterId,
                            page = record.pageCount,
                            markRead = true
                        )
                    }
                }.onSuccess {
                    selectedChapter = currentApi?.let {
                        runCatching { it.seriesChapter(record.chapterId) }.getOrNull()
                    } ?: detail?.copy(pagesRead = detail.pages)
                    Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, "Could not mark issue as read", Toast.LENGTH_SHORT).show()
                }
                issueActionBusy = false
            }
        },
        onMarkUnread = {
            val record = selectedRecord ?: return@IssueDetailSideSheet
            val currentApi = api
            scope.launch {
                issueActionBusy = true
                runCatching {
                    if (currentApi != null) {
                        currentApi.markChaptersUnread(
                            MarkVolumesReadDto(record.seriesId, chapterIds = listOf(record.chapterId))
                        )
                    } else {
                        offlineRepository.markLocalUnread(session, record.chapterId)
                    }
                }.onSuccess {
                    selectedChapter = currentApi?.let {
                        runCatching { it.seriesChapter(record.chapterId) }.getOrNull()
                    } ?: detail?.copy(pagesRead = 0)
                    Toast.makeText(ctx, "Marked as unread", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, "Could not mark issue as unread", Toast.LENGTH_SHORT).show()
                }
                issueActionBusy = false
            }
        },
        onDownload = {},
        onRemoveDownload = {
            selectedRecord?.let { deleteDownloaded(it, closeSheet = true) }
        }
    )
}

@Composable
private fun HomeShell(
    libraries: List<LibraryDto>,
    librarySeriesCounts: Map<Int, Int>,
    isAdmin: Boolean,
    scanningLibraryIds: Set<Int>,
    serverName: String,
    loading: Boolean,
    error: String?,
    session: KavitaSession,
    onDeck: List<SeriesDto>,
    recentlyUpdated: List<SeriesDto>,
    newlyAdded: List<SeriesDto>,
    wantToRead: List<SeriesDto>,
    wantToReadError: String?,
    downloaded: List<OfflineIssueRecord>,
    onOpenSettings: () -> Unit,
    onSelectLibrary: (LibraryDto) -> Unit,
    onScanLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit,
    onRemoveWantToRead: (SeriesDto) -> Unit,
    onSelectDownloaded: (OfflineIssueRecord) -> Unit,
    onDeleteDownloaded: (OfflineIssueRecord) -> Unit
) {
    var destination by rememberSaveable(
        stateSaver = Saver(
            save = { it.ordinal },
            restore = { HomeDestination.entries[it] }
        )
    ) { mutableStateOf(HomeDestination.Home) }
    var showDownloaded by rememberSaveable { mutableStateOf(false) }

    fun selectDestination(next: HomeDestination) {
        if (next == HomeDestination.Browse) showDownloaded = false
        destination = next
    }

    BackHandler(enabled = destination == HomeDestination.Browse && showDownloaded) {
        showDownloaded = false
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFF202222))
    ) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(serverName = serverName, onOpenSettings = onOpenSettings)
                Row(Modifier.fillMaxSize()) {
                    HomeNavigationRail(
                        selected = destination,
                        onSelect = ::selectDestination
                    )
                    HomeContent(
                        destination = destination,
                        libraries = libraries,
                        librarySeriesCounts = librarySeriesCounts,
                        isAdmin = isAdmin,
                        scanningLibraryIds = scanningLibraryIds,
                        loading = loading,
                        error = error,
                        session = session,
                        onDeck = onDeck,
                        recentlyUpdated = recentlyUpdated,
                        newlyAdded = newlyAdded,
                        wantToRead = wantToRead,
                        wantToReadError = wantToReadError,
                        downloaded = downloaded,
                        showDownloaded = showDownloaded,
                        onSelectLibrary = onSelectLibrary,
                        onScanLibrary = onScanLibrary,
                        onSelectSeries = onSelectSeries,
                        onRemoveWantToRead = onRemoveWantToRead,
                        onSelectDownloaded = onSelectDownloaded,
                        onDeleteDownloaded = onDeleteDownloaded,
                        onOpenDownloaded = { showDownloaded = true },
                        onBackToBrowse = { showDownloaded = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(serverName = serverName, onOpenSettings = onOpenSettings)
                HomeContent(
                    destination = destination,
                    libraries = libraries,
                    librarySeriesCounts = librarySeriesCounts,
                    isAdmin = isAdmin,
                    scanningLibraryIds = scanningLibraryIds,
                    loading = loading,
                    error = error,
                    session = session,
                    onDeck = onDeck,
                    recentlyUpdated = recentlyUpdated,
                    newlyAdded = newlyAdded,
                    wantToRead = wantToRead,
                    wantToReadError = wantToReadError,
                    downloaded = downloaded,
                    showDownloaded = showDownloaded,
                    onSelectLibrary = onSelectLibrary,
                    onScanLibrary = onScanLibrary,
                    onSelectSeries = onSelectSeries,
                    onRemoveWantToRead = onRemoveWantToRead,
                    onSelectDownloaded = onSelectDownloaded,
                    onDeleteDownloaded = onDeleteDownloaded,
                    onOpenDownloaded = { showDownloaded = true },
                    onBackToBrowse = { showDownloaded = false },
                    modifier = Modifier.weight(1f)
                )
                HomeBottomNavigation(
                    selected = destination,
                    onSelect = ::selectDestination
                )
            }
        }
    }
}

@Composable
private fun HomeTopBar(serverName: String, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171818))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Kamigura", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(serverName, color = Color(0xFFB9BDBD), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeNavigationRail(
    selected: HomeDestination,
    onSelect: (HomeDestination) -> Unit
) {
    val railState = rememberWideNavigationRailState()
    val scope = rememberCoroutineScope()
    val expanded = railState.targetValue == WideNavigationRailValue.Expanded

    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        ModalWideNavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .background(Color(0xFF181A1A)),
            state = railState,
            hideOnCollapse = false,
            colors = WideNavigationRailDefaults.colors(
                containerColor = Color(0xFF181A1A),
                contentColor = Color.White,
                modalContainerColor = Color(0xFF202222),
                modalContentColor = Color.White
            ),
            header = {
                IconButton(
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .semantics {
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                    onClick = { scope.launch { railState.toggle() } }
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.AutoMirrored.Filled.MenuOpen
                        } else {
                            Icons.Filled.Menu
                        },
                        contentDescription = if (expanded) {
                            "Collapse navigation"
                        } else {
                            "Expand navigation"
                        }
                    )
                }
            }
        ) {
            HomeDestination.entries.forEach { destination ->
                WideNavigationRailItem(
                    selected = selected == destination,
                    onClick = {
                        onSelect(destination)
                        if (expanded) scope.launch { railState.collapse() }
                    },
                    icon = { NavDestinationIcon(destination, selected == destination) },
                    label = {
                        Text(if (expanded) destination.expandedLabel else destination.label)
                    },
                    railExpanded = expanded
                )
            }
        }
    }
}

@Composable
private fun HomeBottomNavigation(
    selected: HomeDestination,
    onSelect: (HomeDestination) -> Unit
) {
    ShortNavigationBar(
        containerColor = Color(0xFF181A1A),
        contentColor = Color.White
    ) {
        HomeDestination.entries.forEach { destination ->
            ShortNavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { NavDestinationIcon(destination, selected == destination) },
                label = { Text(destination.label) }
            )
        }
    }
}

@Composable
private fun NavDestinationIcon(destination: HomeDestination, selected: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.snapTo(0.8f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Icon(
        imageVector = destination.icon,
        contentDescription = destination.label,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    )
}

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
                        color = Color(0xFF2C3030),
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
    return "$root/api/Image/library-cover?libraryId=$libraryId$apiKey"
}

private suspend fun loadLibrarySeriesCounts(
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

@Composable
private fun HomeContent(
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
    showDownloaded: Boolean,
    onSelectLibrary: (LibraryDto) -> Unit,
    onScanLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit,
    onRemoveWantToRead: (SeriesDto) -> Unit,
    onSelectDownloaded: (OfflineIssueRecord) -> Unit,
    onDeleteDownloaded: (OfflineIssueRecord) -> Unit,
    onOpenDownloaded: () -> Unit,
    onBackToBrowse: () -> Unit,
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
                            HomeShelf("On Deck", onDeck, session, onSelectSeries)
                        }
                        item {
                            HomeShelf("Recently Updated Series", recentlyUpdated, session, onSelectSeries)
                        }
                        item {
                            HomeShelf("Newly Added Series", newlyAdded, session, onSelectSeries)
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
                if (showDownloaded) {
                    DownloadedGrid(
                        records = downloaded,
                        session = session,
                        onSelect = onSelectDownloaded,
                        onDelete = onDeleteDownloaded,
                        onBack = onBackToBrowse,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    BrowseHub(
                        downloadedCount = downloaded.size,
                        onOpenDownloaded = onOpenDownloaded,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            HomeDestination.Search -> {
                HomePlaceholder(destination, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun BrowseHub(
    downloadedCount: Int,
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
            Surface(
                color = Color(0xFF2C3030),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDownloaded)
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
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Downloaded",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when (downloadedCount) {
                                0 -> "No issues available offline"
                                1 -> "1 issue available offline"
                                else -> "$downloadedCount issues available offline"
                            },
                            color = Color(0xFFB9BDBD),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
private fun DownloadedGrid(
    records: List<OfflineIssueRecord>,
    session: KavitaSession,
    onSelect: (OfflineIssueRecord) -> Unit,
    onDelete: (OfflineIssueRecord) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Downloaded", modifier = modifier, onBack = onBack) {
        if (records.isEmpty()) {
            DarkMessageState(title = "Downloaded", body = "No issues downloaded yet.")
        } else {
            PosterGrid(items = records, key = { it.chapterId }) { record ->
                DownloadedIssueCardMenu(
                    record = record,
                    session = session,
                    onClick = { onSelect(record) },
                    onDelete = { onDelete(record) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedIssueCardMenu(
    record: OfflineIssueRecord,
    session: KavitaSession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
        ) {
            Column {
                AsyncImage(
                    model = record.localCoverFile() ?: chapterCoverUrl(session, record.chapterId),
                    contentDescription = record.issueName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(KavitaCoverAspectRatio)
                        .background(Color(0xFF111111)),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(
                        text = record.issueName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = record.seriesName,
                        color = Color(0xFFB9BDBD),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Delete Downloaded") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun HomeShelf(
    title: String,
    series: List<SeriesDto>,
    session: KavitaSession,
    onSelectSeries: (SeriesDto) -> Unit
) {
    Column {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (series.isEmpty()) {
            Text("Nothing here yet", color = Color(0xFF9FA5A5), style = MaterialTheme.typography.bodyMedium)
        } else {
            val carouselState = rememberCarouselState { series.size }
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
                        .maskClip(MaterialTheme.shapes.small)
                ) {
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSeries(item) }
                    )
                }
            }
        }
    }
}

private fun GroupedSeriesDto.toSeriesDto(): SeriesDto {
    return SeriesDto(
        id = seriesId,
        name = seriesName ?: "Series $seriesId",
        libraryId = libraryId
    )
}
