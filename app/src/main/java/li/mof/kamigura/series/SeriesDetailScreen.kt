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
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack
import li.mof.kamigura.series.internal.ChapterCardItem
import li.mof.kamigura.series.internal.ChapterGridCard
import li.mof.kamigura.series.internal.ChapterGridHeader
import li.mof.kamigura.series.internal.ChapterIssueGrid
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
            isAdmin = runCatching {
                loadedApi.currentUser().roles.orEmpty().any { it.equals("Admin", ignoreCase = true) }
            }.getOrDefault(false)
            series = loadedApi.series(seriesId)
            metadata = runCatching { loadedApi.seriesMetadata(seriesId) }.getOrNull()
            continueChapter = runCatching { loadedApi.continuePoint(seriesId) }.getOrNull()
            volumes = loadedApi.volumes(seriesId)
            error = null
        } catch (t: Throwable) {
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
            val detail = runCatching { currentApi.seriesChapter(chapterId) }.getOrNull()
            val size = runCatching { currentApi.chapterSize(chapterId) }.getOrNull()
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
                    .getOrElse { item.chapter.copy(pagesRead = item.chapter.pages) }
                updateChapter(refreshed)
                Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
            }.onFailure {
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
                    .getOrElse { item.chapter.copy(pagesRead = 0) }
                updateChapter(refreshed)
                Toast.makeText(ctx, "Marked as unread", Toast.LENGTH_SHORT).show()
            }.onFailure {
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
            .background(Color(0xFF202222))
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

@Composable
private fun SeriesDetailSummary(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    continueChapter: ChapterDto?,
    chapterCards: List<ChapterCardItem>,
    volumeCount: Int,
    session: KavitaSession,
    api: KavitaApi,
    isAdmin: Boolean,
    onPick: (chapterId: Int, volumeId: Int) -> Unit
) {
    val summary = metadata?.summary?.takeIf { it.isNotBlank() }
    val genres = metadata?.genres?.mapNotNull { it.title }.orEmpty()
    val continueItem = continueChapter?.let { chapter ->
        chapterCards.firstOrNull { it.chapter.id == chapter.id }
    } ?: chapterCards.firstOrNull()
    val continueButtonText = series.primaryReadActionText()
    val continueButtonColor = series.coverActionColor()
    val summaryActionColor = continueButtonColor.readableAccentOn(Color(0xFF202222))
    var summaryExpanded by remember(summary) { mutableStateOf(false) }
    var summaryCanExpand by remember(summary) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SeriesDetailHero(
            series = series,
            metadata = metadata,
            issueCount = chapterCards.size,
            volumeCount = volumeCount,
            session = session
        )

        continueItem?.let { item ->
            SeriesReadSplitButton(
                text = continueButtonText,
                containerColor = continueButtonColor,
                series = series,
                api = api,
                isAdmin = isAdmin,
                onRead = { onPick(item.chapter.id, item.volume.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        summary?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (summaryExpanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layoutResult ->
                        if (!summaryExpanded && layoutResult.hasVisualOverflow) {
                            summaryCanExpand = true
                        }
                    }
                )
                if (summaryCanExpand || summaryExpanded) {
                    TextButton(
                        onClick = { summaryExpanded = !summaryExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = summaryActionColor)
                    ) {
                        Text(if (summaryExpanded) "Show less" else "Show more")
                    }
                }
            }
        }

        DetailFactBlock("Genres", genres)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeriesReadSplitButton(
    text: String,
    containerColor: Color,
    series: SeriesDto,
    api: KavitaApi,
    isAdmin: Boolean,
    onRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showingReadingLists by remember { mutableStateOf(false) }
    var readingLists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var actionRunning by remember { mutableStateOf(false) }
    val colors = ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = Color.White
    )
    val mainMenuItems = buildList {
        add(SeriesMenuAction.WantToRead)
        add(SeriesMenuAction.AddToReadingList)
        if (isAdmin) add(SeriesMenuAction.Refresh)
    }

    BoxWithConstraints(modifier) {
        val trailingWidth = 48.dp
        val leadingWidth = (maxWidth - trailingWidth - SplitButtonDefaults.Spacing).coerceAtLeast(48.dp)

        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onRead,
                    modifier = Modifier.width(leadingWidth),
                    colors = colors
                ) {
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingButton = {
                Box {
                    SplitButtonDefaults.TrailingButton(
                        checked = menuExpanded,
                        onCheckedChange = { menuExpanded = it },
                        modifier = Modifier
                            .width(trailingWidth)
                            .semantics {
                                stateDescription = if (menuExpanded) "Expanded" else "Collapsed"
                                contentDescription = "More reading actions"
                            },
                        colors = colors
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (menuExpanded) 180f else 0f,
                            label = "Reading actions arrow rotation"
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }

                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = {
                            menuExpanded = false
                            showingReadingLists = false
                        },
                        modifier = Modifier.width(240.dp),
                        offset = DpOffset(x = trailingWidth - 240.dp, y = 0.dp)
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(index = 0, count = 1)
                        ) {
                            val labels = if (showingReadingLists) {
                                listOf("Back") + readingLists.map { list ->
                                    list.title?.takeIf { it.isNotBlank() } ?: "Reading List ${list.id}"
                                }
                            } else {
                                mainMenuItems.map { it.label }
                            }

                            labels.forEachIndexed { index, label ->
                                val itemShape = MenuDefaults.itemShape(
                                    index = index,
                                    count = labels.size
                                ).shape

                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        if (showingReadingLists) {
                                            if (index == 0) {
                                                showingReadingLists = false
                                            } else {
                                                val readingList = readingLists[index - 1]
                                                menuExpanded = false
                                                showingReadingLists = false
                                                scope.launch {
                                                    actionRunning = true
                                                    runCatching {
                                                        api.addSeriesToReadingList(
                                                            UpdateReadingListBySeriesDto(
                                                                seriesId = series.id,
                                                                readingListId = readingList.id
                                                            )
                                                        )
                                                    }.onSuccess {
                                                        Toast.makeText(
                                                            context,
                                                            "Added to ${readingList.title ?: "reading list"}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }.onFailure {
                                                        Toast.makeText(
                                                            context,
                                                            "Could not update reading list",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                    actionRunning = false
                                                }
                                            }
                                        } else {
                                            when (mainMenuItems[index]) {
                                                SeriesMenuAction.WantToRead -> {
                                                    menuExpanded = false
                                                    scope.launch {
                                                        actionRunning = true
                                                        runCatching {
                                                            api.addSeriesToWantToRead(
                                                                UpdateWantToReadDto(listOf(series.id))
                                                            )
                                                        }.onSuccess {
                                                            Toast.makeText(
                                                                context,
                                                                "Added to Want to Read",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }.onFailure {
                                                            Toast.makeText(
                                                                context,
                                                                "Could not update Want to Read",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        actionRunning = false
                                                    }
                                                }
                                                SeriesMenuAction.AddToReadingList -> scope.launch {
                                                    actionRunning = true
                                                    runCatching { api.readingLists() }
                                                        .onSuccess { lists ->
                                                            readingLists = lists
                                                            if (lists.isEmpty()) {
                                                                menuExpanded = false
                                                                Toast.makeText(
                                                                    context,
                                                                    "No reading lists available",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } else {
                                                                showingReadingLists = true
                                                            }
                                                        }
                                                        .onFailure {
                                                            menuExpanded = false
                                                            Toast.makeText(
                                                                context,
                                                                "Could not load reading lists",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    actionRunning = false
                                                }
                                                SeriesMenuAction.Refresh -> {
                                                    menuExpanded = false
                                                    val libraryId = series.libraryId
                                                    if (libraryId == null) {
                                                        Toast.makeText(
                                                            context,
                                                            "Library information is unavailable",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } else {
                                                        scope.launch {
                                                            actionRunning = true
                                                            val request = RefreshSeriesDto(
                                                                libraryId = libraryId,
                                                                seriesId = series.id
                                                            )
                                                            runCatching {
                                                                api.scanSeries(request)
                                                                api.analyzeSeries(request)
                                                                api.refreshSeriesMetadata(request)
                                                            }.onSuccess {
                                                                Toast.makeText(
                                                                    context,
                                                                    "Series refresh requested",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }.onFailure {
                                                                Toast.makeText(
                                                                    context,
                                                                    "Could not refresh series",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                            actionRunning = false
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    shape = itemShape,
                                    enabled = !actionRunning
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private enum class SeriesMenuAction(val label: String) {
    WantToRead("Add to Want to Read"),
    AddToReadingList("Add to Reading List"),
    Refresh("Refresh")
}

@Composable
private fun SeriesDetailHero(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    issueCount: Int,
    volumeCount: Int,
    session: KavitaSession
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        val coverWidth = if (compact) 180.dp else 150.dp
        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SeriesCover(series, session, Modifier.width(coverWidth))
                SeriesDetailHeroInfo(
                    series = series,
                    metadata = metadata,
                    issueCount = issueCount,
                    volumeCount = volumeCount,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                SeriesCover(series, session, Modifier.width(coverWidth))
                SeriesDetailHeroInfo(
                    series = series,
                    metadata = metadata,
                    issueCount = issueCount,
                    volumeCount = volumeCount,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SeriesCover(series: SeriesDto, session: KavitaSession, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(KavitaCoverAspectRatio)
            .background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        if (session.baseUrl.isNotBlank() && session.apiKey.isNotBlank()) {
            AsyncImage(
                model = seriesCoverUrl(session, series.id),
                contentDescription = series.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(seriesInitial(series.name), color = Color(0xFFB9BDBD), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun SeriesDetailHeroInfo(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    issueCount: Int,
    volumeCount: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val creatorMaxChars = when {
            maxWidth < 220.dp -> 18
            maxWidth < 320.dp -> 28
            else -> 42
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = series.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            series.detailMetaLines(metadata, creatorMaxChars, issueCount, volumeCount).forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFFE6EAEA),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DetailFactBlock(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = Color(0xFFB9BDBD),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = values.joinToString(", "),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

