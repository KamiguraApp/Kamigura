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
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

internal fun chapterCoverUrl(session: KavitaSession, chapterId: Int): String {
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = session.apiKey.takeIf { it.isNotBlank() }?.let { "&apiKey=${Uri.encode(it)}" }.orEmpty()
    return "$root/api/Image/chapter-cover?chapterId=$chapterId$apiKey"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SeriesScreen(
    sessionStore: KavitaSessionStore,
    libraryId: Int,
    libraryName: String,
    onSelect: (SeriesDto) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var isAdmin by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var scanRunning by remember { mutableStateOf(false) }

    LaunchedEffect(libraryId) {
        loading = true
        error = null
        api = null
        isAdmin = false
        menuExpanded = false
        scanRunning = false
        try {
            session = sessionStore.load()
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, _) = client.buildApi()
            api = loadedApi
            isAdmin = runCatching {
                loadedApi.currentUser().roles.orEmpty().any { it.equals("Admin", ignoreCase = true) }
            }.getOrDefault(false)
            val all = loadedApi.allSeriesV2(
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
                pageSize = 300
            )
            series = all.filter { it.libraryId == null || it.libraryId == libraryId }
                .sortedBy { it.name }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFF202222))
    ) {
        BrowsePageScaffold(
            title = libraryName,
            actions = {
                if (isAdmin && api != null) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !scanRunning
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Library actions",
                                tint = Color.White
                            )
                        }
                        DropdownMenuPopup(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.width(220.dp),
                            offset = DpOffset(x = (-172).dp, y = 0.dp)
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(index = 0, count = 1)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Scan Library") },
                                    onClick = {
                                        menuExpanded = false
                                        val loadedApi = api ?: return@DropdownMenuItem
                                        scope.launch {
                                            scanRunning = true
                                            runCatching {
                                                loadedApi.scanLibrary(libraryId)
                                            }.onSuccess {
                                                Toast.makeText(
                                                    ctx,
                                                    "Library scan requested",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }.onFailure {
                                                Toast.makeText(
                                                    ctx,
                                                    "Could not scan library",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            scanRunning = false
                                        }
                                    },
                                    enabled = !scanRunning
                                )
                            }
                        }
                    }
                }
            }
        ) {
            when {
                loading -> DarkLoadingState()
                error != null -> DarkMessageState("Could not load series", error ?: "Unknown error")
                series.isEmpty() -> DarkMessageState("No series", "This library did not return any visible series.")
                else -> PosterGrid(items = series, key = { it.id }) { item ->
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var isAdmin by remember { mutableStateOf(false) }
    var selectedIssue by remember { mutableStateOf<ChapterCardItem?>(null) }
    var selectedIssueDetail by remember { mutableStateOf<ChapterDto?>(null) }
    var selectedIssueSize by remember { mutableStateOf<Long?>(null) }
    var issueLoading by remember { mutableStateOf(false) }
    var issueActionBusy by remember { mutableStateOf(false) }
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }

    LaunchedEffect(seriesId) {
        loading = true
        error = null
        api = null
        isAdmin = false
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
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
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
            else -> SeriesDetailContent(
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
private fun ChapterIssueGrid(
    chapterCards: List<ChapterCardItem>,
    session: KavitaSession,
    onIssueClick: (ChapterCardItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
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

@Composable
private fun ChapterGridHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Issues",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Surface(
            color = Color(0xFF596061),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
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
            .aspectRatio(0.68f)
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

private data class ChapterCardItem(
    val volume: VolumeDto,
    val chapter: ChapterDto
)

@Composable
private fun ChapterGridCard(item: ChapterCardItem, session: KavitaSession, onClick: () -> Unit) {
    val chapter = item.chapter
    val title = chapter.displayTitle()
    val label = listOfNotNull(
        title,
        item.volume.displayName(),
        chapter.releaseDateText()
    ).joinToString(" • ")
    val progress = chapter.readingProgress()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                if (session.baseUrl.isNotBlank() && session.apiKey.isNotBlank()) {
                    AsyncImage(
                        model = chapterCoverUrl(session, chapter.id),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("CH", color = Color(0xFFB9BDBD), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.fillMaxWidth()) {
                ReadingProgressBar(
                    progress = progress,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(3.dp)
                )
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val boundedProgress = (progress ?: 0f).coerceIn(0f, 1f)
    val fillColor = if (boundedProgress >= 1f) ReadingProgressRead else ReadingProgressInProgress
    Box(modifier = modifier.background(ReadingProgressTrack)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(boundedProgress)
                .background(fillColor)
        )
    }
}

private fun ChapterDto.displayTitle(): String {
    title?.takeIf { it.isDisplayableChapterLabel() }?.let { return it }
    number.displayText()?.takeIf { it.isDisplayableChapterLabel() }?.let { return it }
    return id.toString()
}

internal fun VolumeDto.displayName(): String? {
    name?.takeIf { it.isDisplayableVolumeLabel() }?.let { return it }
    val numberText = number.displayText()
    return numberText
        ?.takeIf { it.isDisplayableVolumeLabel() }
        ?.let { "Volume $it" }
}

private fun ChapterDto.releaseDateText(): String? {
    return releaseDate
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("0001-01-01") }
        ?.substringBefore("T")
        ?.takeIf { it.isNotBlank() }
}

private fun String?.isDisplayableChapterLabel(): Boolean {
    return !isNullOrBlank() && this != "-100000"
}

private fun String?.isDisplayableVolumeLabel(): Boolean {
    return !isNullOrBlank() && this != "-100000" && this != "0"
}

private fun SeriesDto.readingProgress(): Float? {
    val total = pages ?: return null
    if (total <= 0) return null
    val read = (pagesRead ?: 0).coerceIn(0, total)
    return read.toFloat() / total.toFloat()
}

private fun ChapterDto.readingProgress(): Float? {
    val total = pages ?: return null
    if (total <= 0) return null
    val read = (pagesRead ?: 0).coerceIn(0, total)
    return read.toFloat() / total.toFloat()
}

private fun SeriesDto.primaryReadActionText(): String {
    return primaryReadActionText(pages = pages, pagesRead = pagesRead)
}

internal fun primaryReadActionText(pages: Int?, pagesRead: Int?): String {
    val total = pages ?: return "Start Reading"
    if (total <= 0) return "Start Reading"
    val read = (pagesRead ?: 0).coerceIn(0, total)
    return when {
        read <= 0 -> "Start Reading"
        read >= total -> "Re-Read"
        else -> "Continue Reading"
    }
}

private fun SeriesDto.isRead(): Boolean {
    val total = pages ?: return false
    if (total <= 0) return false
    return (pagesRead ?: 0) >= total
}

private fun SeriesDto.coverActionColor(): Color {
    return coverActionColor(primaryColor, secondaryColor, Color(0xFF6A5BB7))
}

internal fun ChapterDto.coverActionColor(fallback: Color): Color {
    return coverActionColor(primaryColor, secondaryColor, fallback)
}

private fun coverActionColor(primaryColor: String?, secondaryColor: String?, fallback: Color): Color {
    val primary = primaryColor.parseKavitaRgb()
    val secondary = secondaryColor.parseKavitaRgb()
    val seed = when {
        primary == null -> secondary
        secondary == null -> primary
        primary.hsvSaturation() < 0.12f && secondary.hsvSaturation() > primary.hsvSaturation() + 0.12f -> secondary
        else -> primary
    } ?: return fallback

    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (seed shr 16) and 0xFF,
        (seed shr 8) and 0xFF,
        seed and 0xFF,
        hsv
    )
    hsv[1] = hsv[1].coerceIn(0.38f, 0.72f)
    hsv[2] = hsv[2].coerceIn(0.42f, 0.68f)
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun Color.readableAccentOn(background: Color): Color {
    for (step in 0..10) {
        val candidate = lerp(this, Color.White, step / 10f)
        val lighter = maxOf(candidate.luminance(), background.luminance())
        val darker = minOf(candidate.luminance(), background.luminance())
        if ((lighter + 0.05f) / (darker + 0.05f) >= 4.5f) return candidate
    }
    return Color.White
}

private fun String?.parseKavitaRgb(): Int? {
    val raw = this
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 || it.length == 8 }
        ?: return null
    val rgb = if (raw.length == 8) raw.takeLast(6) else raw
    return rgb.toLongOrNull(16)?.toInt()
}

private fun Int.hsvSaturation(): Float {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (this shr 16) and 0xFF,
        (this shr 8) and 0xFF,
        this and 0xFF,
        hsv
    )
    return hsv[1]
}

private fun SeriesDto.detailMetaLines(
    metadata: SeriesMetadataDto?,
    creatorMaxChars: Int,
    issueCount: Int,
    volumeCount: Int
): List<String> {
    val peopleLine = metadata?.creatorMetaLine(creatorMaxChars)
    val publisherLine = listOfNotNull(
        metadata?.imprints?.mapNotNull { it.name }.orEmpty().compactNameList(maxItems = 2),
        metadata?.publishers?.mapNotNull { it.name }.orEmpty().compactNameList(maxItems = 2)
    ).joinMetaLine()
    val publicationLine = listOfNotNull(
        metadata?.releaseYear?.takeIf { it > 0 }?.toString(),
        metadata?.publicationStatus.publicationStatusText(),
        issueOrVolumeCountText(issueCount, volumeCount)
    ).joinMetaLine()
    val lengthLine = listOfNotNull(
        pages?.let { "${it.compactCount()} pages" },
        readingHoursText(),
        readingStateText()
    ).joinMetaLine()
    return listOfNotNull(
        peopleLine,
        publisherLine,
        publicationLine,
        lengthLine
    )
}

private fun SeriesDto.issueOrVolumeCountText(issueCount: Int, volumeCount: Int): String? {
    return when {
        issueCount > 0 -> "$issueCount ${if (issueCount == 1) "issue" else "issues"}"
        volumeCount > 0 -> "$volumeCount ${if (volumeCount == 1) "volume" else "volumes"}"
        else -> null
    }
}

private fun SeriesDto.readingStateText(): String? {
    val total = pages ?: return null
    if (total <= 0) return null
    val read = (pagesRead ?: 0).coerceAtLeast(0)
    return when {
        read <= 0 -> "Unread"
        read >= total -> "All read"
        else -> "In progress"
    }
}

private fun SeriesDto.readingHoursText(): String? {
    return readingHoursText(minHoursToRead, maxHoursToRead, avgHoursToRead)
}

internal fun readingHoursText(min: Int?, max: Int?, average: Float?): String? {
    return when {
        min != null && max != null && min > 0 && max > min -> "$min-$max hours"
        min != null && min > 0 -> min.hourText()
        max != null && max > 0 -> max.hourText()
        average != null && average > 0f -> average.roundToInt().coerceAtLeast(1).hourText()
        else -> null
    }
}

private fun Int.hourText(): String = "$this ${if (this == 1) "hour" else "hours"}"

private fun Int.compactCount(): String {
    if (this < 1000) return toString()
    val tenths = (this / 100f).roundToInt()
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0) "${whole}K" else "$whole.${decimal}K"
}

private fun SeriesMetadataDto.creatorMetaLine(maxChars: Int): String? {
    return listOf(
        writers,
        coverArtists,
        pencillers,
        inkers,
        colorists,
        letterers,
        editors,
        translators
    )
        .flatten()
        .mapNotNull { it.name?.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .compactNameLine(maxChars)
}

private fun List<String>.joinMetaLine(): String? {
    return joinToString("  •  ").takeIf { it.isNotBlank() }
}

private fun List<String>.compactNameList(maxItems: Int = 2): String? {
    val names = map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (names.isEmpty()) return null
    return if (names.size <= maxItems) {
        names.joinToString(", ")
    } else {
        names.take(maxItems).joinToString(", ") + " +${names.size - maxItems}"
    }
}

private fun List<String>.compactNameLine(maxChars: Int): String? {
    val names = map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (names.isEmpty()) return null
    val visible = mutableListOf<String>()
    for (name in names) {
        val candidate = (visible + name).joinToString("  •  ")
        if (visible.isNotEmpty() && candidate.length > maxChars) break
        visible += name
        if (candidate.length >= maxChars) break
    }
    if (visible.isEmpty()) visible += names.first()
    val hidden = names.size - visible.size
    return visible.joinToString("  •  ") + if (hidden > 0) " +$hidden" else ""
}

private fun Int?.publicationStatusText(): String? {
    return when (this) {
        0 -> "Ongoing"
        1 -> "Hiatus"
        2 -> "Completed"
        3 -> "Cancelled"
        4 -> "Ended"
        else -> null
    }
}

private fun kotlinx.serialization.json.JsonElement?.displayText(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        null -> null
        else -> toString()
    }?.trim('"')
}
