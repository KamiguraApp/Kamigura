package li.mof.kamigura.series

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ReadingListDto
import li.mof.kamigura.RefreshSeriesDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto
import li.mof.kamigura.SeriesMetadataDto
import li.mof.kamigura.UpdateReadingListBySeriesDto
import li.mof.kamigura.UpdateWantToReadDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial
import li.mof.kamigura.ui.theme.KamiguraBackground
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack
import li.mof.kamigura.series.internal.ChapterCardItem
import li.mof.kamigura.series.internal.ChapterGridCard
import li.mof.kamigura.series.internal.ChapterGridHeader
import li.mof.kamigura.series.internal.ChapterIssueGrid
import li.mof.kamigura.series.internal.SeriesDetailSummary
import li.mof.kamigura.series.internal.coverActionColor
import li.mof.kamigura.series.internal.detailMetaLines
import li.mof.kamigura.series.internal.displayName
import li.mof.kamigura.series.internal.displayShortName
import li.mof.kamigura.series.internal.displayTitle
import li.mof.kamigura.series.internal.primaryReadActionText
import li.mof.kamigura.series.internal.readableAccentOn
import li.mof.kamigura.series.internal.readingProgress
import li.mof.kamigura.series.internal.releaseDateText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChapterPickScreen(
    sessionStore: KavitaSessionStore,
    libraryId: Int,
    seriesId: Int,
    seriesName: String,
    onPick: (chapterId: Int, volumeId: Int, incognito: Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var series by remember { mutableStateOf<SeriesDto?>(null) }
    var metadata by remember { mutableStateOf<SeriesMetadataDto?>(null) }
    var continueChapter by remember { mutableStateOf<ChapterDto?>(null) }
    var volumes by remember { mutableStateOf<List<VolumeDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var isAdmin by remember { mutableStateOf(false) }
    var selectedIssue by remember { mutableStateOf<ChapterCardItem?>(null) }
    var selectedIssueDetail by remember { mutableStateOf<ChapterDto?>(null) }
    var selectedIssueSize by remember { mutableStateOf<Long?>(null) }
    var issueLoading by remember { mutableStateOf(false) }
    var issueActionBusy by remember { mutableStateOf(false) }
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }
    val pullRefreshState = rememberPullToRefreshState()

    suspend fun loadSeriesDetails(initialLoad: Boolean) {
        if (initialLoad) {
            loading = true
            error = null
            api = null
            isAdmin = false
        } else {
            refreshing = true
        }
        try {
            val loadedSession = sessionStore.load()
            session = loadedSession
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, _) = client.buildApi()
            api = loadedApi
            runCatching { offlineRepository.syncPending(loadedSession, loadedApi) }
                .onFailure { KamiguraLog.w("Could not sync pending offline progress from Series detail.", it) }
            isAdmin = runCatching {
                loadedApi.currentUser().roles.orEmpty().any { it.equals("Admin", ignoreCase = true) }
            }.onFailure {
                KamiguraLog.w("Could not load current user roles on Series detail.", it)
            }.getOrDefault(false)
            series = loadedApi.series(seriesId)
            metadata = runCatching { loadedApi.seriesMetadata(seriesId) }
                .onFailure { KamiguraLog.w("Could not load metadata for series $seriesId.", it) }
                .getOrNull()
            continueChapter = runCatching { loadedApi.continuePoint(seriesId) }
                .onFailure { KamiguraLog.w("Could not load continue point for series $seriesId.", it) }
                .getOrNull()
            volumes = loadedApi.volumes(seriesId)
            error = null
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load series details for series $seriesId.", t)
            val message = t.message ?: t.toString()
            if (initialLoad || series == null) {
                error = message
            } else {
                Toast.makeText(ctx, "Could not refresh series details", Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (initialLoad) {
                loading = false
            } else {
                refreshing = false
            }
        }
    }

    LaunchedEffect(seriesId) {
        loadSeriesDetails(initialLoad = true)
    }

    val chapterCards = volumes.flatMap { volume ->
        volume.chapters.map { chapter ->
            ChapterCardItem(volume = volume, chapter = chapter)
        }
    }
    val displaySeries = series ?: SeriesDto(id = seriesId, name = seriesName, libraryId = libraryId)
    val loadedApi = api
    val selectedChapterId = selectedIssue?.chapter?.id
    val downloadFlow = remember(session.baseUrl, selectedChapterId) {
        selectedChapterId?.let { offlineRepository.observe(session, it) } ?: flowOf(null)
    }
    val downloadRecord by downloadFlow.collectAsState(initial = null)

    LaunchedEffect(session.baseUrl, selectedChapterId, downloadRecord?.status) {
        val chapterId = selectedChapterId ?: return@LaunchedEffect
        if (offlineRepository.cleanupUnavailableDownload(session, chapterId)) {
            return@LaunchedEffect
        }
        while (downloadRecord?.status in setOf(
                OfflineDownloadStatus.Queued,
                OfflineDownloadStatus.Downloading
            )) {
            offlineRepository.reconcile(session, chapterId)
            delay(750)
        }
    }

    fun updateChapter(updated: ChapterDto) {
        volumes = volumes.map { volume ->
            if (volume.chapters.none { it.id == updated.id }) {
                volume
            } else {
                volume.copy(
                    chapters = volume.chapters.map { chapter ->
                        if (chapter.id == updated.id) updated else chapter
                    }
                )
            }
        }
        selectedIssue = selectedIssue?.let { item ->
            if (item.chapter.id == updated.id) item.copy(chapter = updated) else item
        }
        selectedIssueDetail = updated
    }

    fun openIssue(item: ChapterCardItem) {
        val currentApi = loadedApi ?: return
        selectedIssue = item
        selectedIssueDetail = item.chapter
        selectedIssueSize = null
        issueLoading = true
        scope.launch {
            val chapterId = item.chapter.id
            val detail = runCatching { currentApi.seriesChapter(chapterId) }
                .onFailure { KamiguraLog.w("Could not load issue detail for chapter $chapterId.", it) }
                .getOrNull()
            val size = runCatching { currentApi.chapterSize(chapterId) }
                .onFailure { KamiguraLog.w("Could not load issue size for chapter $chapterId.", it) }
                .getOrNull()
            if (selectedIssue?.chapter?.id == chapterId) {
                detail?.let(::updateChapter)
                selectedIssueSize = size
                issueLoading = false
            }
        }
    }

    fun markSelectedIssueRead() {
        val currentApi = loadedApi ?: return
        val item = selectedIssue ?: return
        scope.launch {
            issueActionBusy = true
            runCatching {
                currentApi.markChapterRead(
                    MarkChapterReadDto(
                        seriesId = seriesId,
                        chapterId = item.chapter.id,
                        generateReadingSession = false
                    )
                )
            }.onSuccess {
                val refreshed = runCatching { currentApi.seriesChapter(item.chapter.id) }
                    .onFailure {
                        KamiguraLog.w("Could not refresh issue detail after marking read.", it)
                    }
                    .getOrElse { item.chapter.copy(pagesRead = item.chapter.pages) }
                updateChapter(refreshed)
                Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
            }.onFailure {
                KamiguraLog.w("Could not mark issue ${item.chapter.id} as read.", it)
                Toast.makeText(ctx, "Could not mark issue as read", Toast.LENGTH_SHORT).show()
            }
            issueActionBusy = false
        }
    }

    fun markSelectedIssueUnread() {
        val currentApi = loadedApi ?: return
        val item = selectedIssue ?: return
        scope.launch {
            issueActionBusy = true
            runCatching {
                currentApi.markChaptersUnread(
                    MarkVolumesReadDto(
                        seriesId = seriesId,
                        chapterIds = listOf(item.chapter.id)
                    )
                )
            }.onSuccess {
                val refreshed = runCatching { currentApi.seriesChapter(item.chapter.id) }
                    .onFailure {
                        KamiguraLog.w("Could not refresh issue detail after marking unread.", it)
                    }
                    .getOrElse { item.chapter.copy(pagesRead = 0) }
                updateChapter(refreshed)
                Toast.makeText(ctx, "Marked as unread", Toast.LENGTH_SHORT).show()
            }.onFailure {
                KamiguraLog.w("Could not mark issue ${item.chapter.id} as unread.", it)
                Toast.makeText(ctx, "Could not mark issue as unread", Toast.LENGTH_SHORT).show()
            }
            issueActionBusy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(KamiguraBackground)
    ) {
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState("Could not load series details", error ?: "Unknown error")
            loadedApi == null -> DarkMessageState("Could not load series details", "API unavailable")
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch { loadSeriesDetails(initialLoad = false) }
                },
                modifier = Modifier.fillMaxSize(),
                state = pullRefreshState,
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullRefreshState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = Color(0xFF24352F),
                        color = Color(0xFF86D39B)
                    )
                }
            ) {
                SeriesDetailContent(
                    series = displaySeries,
                    metadata = metadata,
                    continueChapter = continueChapter,
                    chapterCards = chapterCards,
                    volumeCount = volumes.size,
                    session = session,
                    api = loadedApi,
                    isAdmin = isAdmin,
                    onPick = { chapterId, volumeId -> onPick(chapterId, volumeId, false) },
                    onIssueClick = ::openIssue
                )
            }
        }

        val issue = selectedIssue
        val seriesActionColor = displaySeries.coverActionColor()
        val issueActionColor = (selectedIssueDetail ?: issue?.chapter)
            ?.coverActionColor(fallback = seriesActionColor)
            ?: seriesActionColor
        IssueDetailSideSheet(
            visible = issue != null,
            seriesName = displaySeries.name,
            volume = issue?.volume,
            chapter = selectedIssueDetail ?: issue?.chapter,
            fileSizeBytes = selectedIssueSize,
            downloadRecord = downloadRecord,
            loading = issueLoading,
            actionBusy = issueActionBusy,
            session = session,
            actionColor = issueActionColor,
            onDismissRequest = {
                selectedIssue = null
                selectedIssueDetail = null
                selectedIssueSize = null
            },
            onRead = {
                issue?.let { onPick(it.chapter.id, it.volume.id, false) }
            },
            onReadIncognito = {
                issue?.let { onPick(it.chapter.id, it.volume.id, true) }
            },
            onMarkRead = ::markSelectedIssueRead,
            onMarkUnread = ::markSelectedIssueUnread,
            onDownload = {
                issue?.let { item ->
                    scope.launch {
                        issueActionBusy = true
                        runCatching {
                            offlineRepository.enqueue(
                                session = session,
                                libraryId = libraryId,
                                seriesId = seriesId,
                                volumeId = item.volume.id,
                                chapterId = item.chapter.id,
                                seriesName = displaySeries.name,
                                issueName = item.volume.displayName() ?: item.chapter.displayTitle(),
                                expectedBytes = selectedIssueSize,
                                expectedPageCount = selectedIssueDetail?.pages ?: item.chapter.pages
                            )
                        }.onSuccess {
                            Toast.makeText(ctx, "Download queued", Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            KamiguraLog.w("Could not queue offline download for chapter ${item.chapter.id}.", error)
                            Toast.makeText(
                                ctx,
                                error.message ?: "Could not queue download",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        issueActionBusy = false
                    }
                }
            },
            onRemoveDownload = {
                issue?.let { item ->
                    scope.launch {
                        issueActionBusy = true
                        runCatching { offlineRepository.remove(session, item.chapter.id) }
                            .onSuccess {
                                Toast.makeText(ctx, "Download removed", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                KamiguraLog.w("Could not remove offline download for chapter ${item.chapter.id}.", it)
                                Toast.makeText(ctx, "Could not remove download", Toast.LENGTH_SHORT).show()
                            }
                        issueActionBusy = false
                    }
                }
            }
        )
    }
}

@Composable
private fun SeriesDetailContent(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    continueChapter: ChapterDto?,
    chapterCards: List<ChapterCardItem>,
    volumeCount: Int,
    session: KavitaSession,
    api: KavitaApi,
    isAdmin: Boolean,
    onPick: (chapterId: Int, volumeId: Int) -> Unit,
    onIssueClick: (ChapterCardItem) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp && maxWidth > maxHeight
        if (wide) {
            val summaryWidth = if (maxWidth >= 1100.dp) 420.dp else 340.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(summaryWidth)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        SeriesDetailSummary(
                            series = series,
                            metadata = metadata,
                            continueChapter = continueChapter,
                            chapterCards = chapterCards,
                            volumeCount = volumeCount,
                            session = session,
                            api = api,
                            isAdmin = isAdmin,
                            onPick = onPick
                        )
                    }
                }
                ChapterIssueGrid(
                    chapterCards = chapterCards,
                    session = session,
                    onIssueClick = onIssueClick,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SeriesDetailSummary(
                        series = series,
                        metadata = metadata,
                        continueChapter = continueChapter,
                        chapterCards = chapterCards,
                        volumeCount = volumeCount,
                        session = session,
                        api = api,
                        isAdmin = isAdmin,
                        onPick = onPick
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ChapterGridHeader(chapterCards.size)
                }
                gridItems(chapterCards, key = { "${it.volume.id}-${it.chapter.id}" }) { item ->
                    ChapterGridCard(
                        item = item,
                        session = session,
                        onClick = { onIssueClick(item) }
                    )
                }
            }
        }
    }
}

