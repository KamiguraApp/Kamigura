package li.mof.kamigura.reader

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.imageLoader
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.PageCurlState
import eu.wewox.pagecurl.page.PageCurlTurnDirection
import li.mof.kamigura.AppSettings
import li.mof.kamigura.AppSettingsStore
import li.mof.kamigura.FileDimensionDto
import li.mof.kamigura.InvertMode
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.PageTurnMode
import li.mof.kamigura.ProgressDto
import li.mof.kamigura.download.OfflineChapter
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.reader.internal.ReaderInvertCacheKey
import li.mof.kamigura.reader.internal.ReaderPrefetchTarget
import li.mof.kamigura.reader.internal.ReaderFullscreenEffect
import li.mof.kamigura.reader.internal.ReaderMenuOverlay
import li.mof.kamigura.reader.internal.ReaderPageView
import li.mof.kamigura.reader.internal.ReaderTapLayer
import li.mof.kamigura.reader.internal.ReaderZoomEpsilon
import li.mof.kamigura.reader.internal.ReaderZoomPanState
import li.mof.kamigura.reader.internal.lerpTo
import li.mof.kamigura.reader.internal.pageIsWide
import li.mof.kamigura.reader.internal.preAnalyzeReaderPages
import li.mof.kamigura.reader.internal.prefetchReaderPages
import li.mof.kamigura.reader.internal.readerPageLayout
import li.mof.kamigura.reader.internal.readerPanBoundsPx
import li.mof.kamigura.reader.internal.readerPrefetchPageIndicesAround
import li.mof.kamigura.reader.internal.readerPrefetchSlotWidthPx
import li.mof.kamigura.reader.internal.readerVisiblePageIndices
import li.mof.kamigura.reader.internal.spreadPagesFor
import li.mof.kamigura.reader.internal.toPageDimensionMap
import li.mof.kamigura.reader.internal.withDoubleTapZoom
import li.mof.kamigura.reader.internal.withTransform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Kavita reading-profile reading direction values (ReadingDirection enum).
private const val KavitaReadingDirectionLtr = 0
private const val KavitaReadingDirectionRtl = 1

// ReadingProfileKind.Default = the user's global default (no per-series direction).
private const val KavitaReadingProfileKindDefault = 0

private const val ReaderProgressSyncDelayMillis = 3_000L
private const val ReaderSpreadCurlVisualPageCount = 3
private const val ReaderSpreadCurlVisualCurrent = 1
private const val ReaderSpreadCurlTurnEndFractionX = 0.5f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPageCurlApi::class)
@Composable
fun ReaderScreen(
    sessionStore: KavitaSessionStore,
    settingsStore: AppSettingsStore,
    libraryId: Int,
    seriesId: Int,
    volumeId: Int,
    chapterId: Int,
    incognito: Boolean = false,
    initialPage: Int? = null,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val fallbackImageLoader = ctx.imageLoader
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by settingsStore.flow.collectAsState(initial = AppSettings())
    ReaderFullscreenEffect()

    var session by remember { mutableStateOf<KavitaSession?>(null) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var pageDimensions by remember { mutableStateOf<Map<Int, FileDimensionDto>>(emptyMap()) }
    var page by remember { mutableIntStateOf(0) }
    var readerReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showReaderMenu by remember { mutableStateOf(false) }
    var rightToLeft by remember { mutableStateOf(settings.reader.rightToLeft) }
    var zoomPan by remember { mutableStateOf(ReaderZoomPanState()) }
    var completingRead by remember { mutableStateOf(false) }
    var offlineChapter by remember { mutableStateOf<OfflineChapter?>(null) }
    var readerImageLoader by remember { mutableStateOf<ImageLoader?>(null) }
    var activeTransition by remember { mutableStateOf<ReaderPageTransition?>(null) }
    var transitionProgress by remember { mutableFloatStateOf(0f) }
    var transitionSettling by remember { mutableStateOf(false) }
    var activeCurlDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var activeCurlTargetPage by remember { mutableStateOf<Int?>(null) }
    var curlDragStartPointer by remember { mutableStateOf<Offset?>(null) }
    var curlDragProgress by remember { mutableFloatStateOf(0f) }
    var queuedTurn by remember { mutableStateOf<PendingReaderTurn?>(null) }
    var pendingZoomLandingDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var dragBoundaryDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var closeDragOffsetY by remember { mutableFloatStateOf(0f) }
    var closeAnimationJob by remember { mutableStateOf<Job?>(null) }
    val invertDecisionCache = remember { mutableStateMapOf<ReaderInvertCacheKey, Boolean>() }
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }
    val minimumFlingVelocity = remember(ctx) {
        ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat()
    }
    var pendingRemoteProgressPage by remember { mutableStateOf<Int?>(null) }
    var pendingRemoteProgressSinceMillis by remember { mutableStateOf<Long?>(null) }
    var lastRemoteProgressPage by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(readerImageLoader) {
        val activeLoader = readerImageLoader
        onDispose { activeLoader?.shutdown() }
    }

    fun clampPage(value: Int): Int = value.coerceIn(0, (pages - 1).coerceAtLeast(0))
    suspend fun saveRemoteProgress(targetPage: Int): Boolean {
        if (!readerReady) return false
        if (incognito) return false
        if (pages <= 0 || targetPage !in 0 until pages) return false
        if (lastRemoteProgressPage == targetPage) {
            if (pendingRemoteProgressPage == targetPage) {
                pendingRemoteProgressPage = null
                pendingRemoteProgressSinceMillis = null
            }
            return true
        }
        val loadedApi = api ?: return false
        val loadedSession = session
        return try {
            loadedApi.saveProgress(
                ProgressDto(
                    libraryId = libraryId,
                    seriesId = seriesId,
                    volumeId = volumeId,
                    chapterId = chapterId,
                    pageNum = targetPage
                )
            )
            lastRemoteProgressPage = targetPage
            if (pendingRemoteProgressPage == targetPage) {
                pendingRemoteProgressPage = null
                pendingRemoteProgressSinceMillis = null
            }
            if (offlineChapter != null && loadedSession != null) {
                offlineRepository.markProgressSynced(
                    session = loadedSession,
                    chapterId = chapterId,
                    expectedPage = targetPage
                )
            }
            true
        } catch (t: Throwable) {
            KamiguraLog.w("Could not save remote reader progress for chapter $chapterId.", t)
            false
        }
    }
    fun completeChapter() {
        if (completingRead || pages <= 0) return
        completingRead = true
        showReaderMenu = false
        if (incognito) {
            onBack()
            return
        }
        scope.launch {
            val loadedSession = session
            if (offlineChapter != null && loadedSession != null) {
                offlineRepository.saveLocalProgress(
                    session = loadedSession,
                    chapterId = chapterId,
                    page = pages - 1,
                    markRead = true
                )
            }
            val loadedApi = api
            val progressSaved = loadedApi != null && runCatching {
                loadedApi.saveProgress(
                    ProgressDto(
                        libraryId = libraryId,
                        seriesId = seriesId,
                        volumeId = volumeId,
                        chapterId = chapterId,
                        pageNum = pages - 1
                    )
                )
            }.onFailure {
                KamiguraLog.w("Could not save final reader progress for chapter $chapterId.", it)
            }.isSuccess
            if (progressSaved) {
                lastRemoteProgressPage = pages - 1
                if (pendingRemoteProgressPage == pages - 1) pendingRemoteProgressPage = null
            }
            val readMarked = loadedApi != null && runCatching {
                loadedApi.markChapterRead(
                    MarkChapterReadDto(
                        seriesId = seriesId,
                        chapterId = chapterId,
                        generateReadingSession = false
                    )
                )
            }.onFailure {
                KamiguraLog.w("Could not mark chapter $chapterId as read.", it)
            }.isSuccess
            if (offlineChapter != null && loadedSession != null && progressSaved) {
                offlineRepository.markProgressSynced(
                    session = loadedSession,
                    chapterId = chapterId,
                    expectedPage = pages - 1,
                    markedRead = readMarked
                )
            }
            onBack()
        }
    }
    fun resetChapterAndExit() {
        if (completingRead) return
        completingRead = true
        showReaderMenu = false
        if (incognito) {
            onBack()
            return
        }
        scope.launch {
            val loadedSession = session
            if (offlineChapter != null && loadedSession != null) {
                offlineRepository.markLocalUnread(loadedSession, chapterId)
            }
            val loadedApi = api
            val unreadMarked = loadedApi != null && runCatching {
                loadedApi.markChaptersUnread(
                    MarkVolumesReadDto(
                        seriesId = seriesId,
                        chapterIds = listOf(chapterId)
                    )
                )
            }.onFailure {
                KamiguraLog.w("Could not mark chapter $chapterId as unread.", it)
            }.isSuccess
            if (unreadMarked) {
                lastRemoteProgressPage = 0
                if (pendingRemoteProgressPage == 0) pendingRemoteProgressPage = null
            }
            if (offlineChapter != null && loadedSession != null && unreadMarked) {
                offlineRepository.markProgressSynced(
                    session = loadedSession,
                    chapterId = chapterId,
                    expectedPage = 0,
                    markedUnread = true
                )
            }
            onBack()
        }
    }
    fun jumpToPage(targetPage: Int) {
        val nextPage = clampPage(targetPage)
        if (nextPage != page) {
            page = nextPage
        }
    }

    LaunchedEffect(Unit) {
        val loadedSession = sessionStore.load()
        session = loadedSession
        val local = runCatching {
            offlineRepository.localChapter(loadedSession, chapterId)
        }.onFailure {
            KamiguraLog.w("Could not load local offline chapter $chapterId.", it)
        }.getOrNull()
        offlineChapter = local
        if (local != null) {
            pages = local.pages.size
            pageDimensions = local.dimensions
            page = (initialPage ?: local.record.localPage).coerceIn(0, (pages - 1).coerceAtLeast(0))
            if (!local.record.progressPending) {
                lastRemoteProgressPage = page
            }
            readerReady = pages > 0
        }

        try {
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, okHttp) = client.buildApi()
            api = loadedApi
            readerImageLoader = client.buildReaderImageLoader(okHttp)
            runCatching { offlineRepository.syncPending(loadedSession, loadedApi) }
                .onFailure { KamiguraLog.w("Could not sync pending offline progress from Reader.", it) }
            rightToLeft = try {
                // A series-specific direction on the server (User/Implicit profile)
                // wins. When only the global Default profile applies, the series has
                // no direction of its own, so fall back to the app setting.
                val profile = loadedApi.readingProfile(libraryId, seriesId)
                when {
                    profile.kind == KavitaReadingProfileKindDefault -> settings.reader.rightToLeft
                    profile.readingDirection == KavitaReadingDirectionRtl -> true
                    profile.readingDirection == KavitaReadingDirectionLtr -> false
                    else -> settings.reader.rightToLeft
                }
            } catch (t: Throwable) {
                KamiguraLog.w("Could not load reading direction for series $seriesId.", t)
                settings.reader.rightToLeft
            }
            if (local == null) {
                val info = loadedApi.chapterInfo(chapterId, includeDimensions = true)
                val pageCount = info.pages ?: 0
                pages = pageCount
                pageDimensions = info.pageDimensions.toPageDimensionMap()
                val savedPage = initialPage ?: loadedApi.getProgress(chapterId).pageNum
                page = if (pages > 0) savedPage.coerceIn(0, pages - 1) else 0
                lastRemoteProgressPage = page
            } else if (!local.record.progressPending) {
                val savedPage = runCatching { loadedApi.getProgress(chapterId).pageNum }
                    .onFailure { KamiguraLog.w("Could not load remote reader progress for chapter $chapterId.", it) }
                    .getOrNull()
                val landingPage = initialPage ?: savedPage
                if (landingPage != null) {
                    page = landingPage.coerceIn(0, pages - 1)
                    lastRemoteProgressPage = page
                }
            }
            readerReady = true
        } catch (t: Throwable) {
            KamiguraLog.w("Could not initialize Reader for chapter $chapterId.", t)
            if (local == null) {
                error = t.message ?: t.toString()
            }
        }
    }

    LaunchedEffect(readerReady, pages, page, incognito, session, offlineChapter) {
        if (!readerReady) return@LaunchedEffect
        if (incognito) return@LaunchedEffect
        if (pages <= 0 || page !in 0 until pages) return@LaunchedEffect
        val loadedSession = session ?: return@LaunchedEffect
        if (offlineChapter != null) {
            offlineRepository.saveLocalProgress(loadedSession, chapterId, page)
        }
    }

    LaunchedEffect(api, readerReady, pages, page, incognito) {
        if (!readerReady) return@LaunchedEffect
        if (incognito) return@LaunchedEffect
        if (pages <= 0 || page !in 0 until pages) return@LaunchedEffect
        if (api == null) return@LaunchedEffect
        if (lastRemoteProgressPage == page) return@LaunchedEffect
        pendingRemoteProgressPage = page
        pendingRemoteProgressSinceMillis = SystemClock.elapsedRealtime()
        delay(ReaderProgressSyncDelayMillis)
        saveRemoteProgress(page)
    }

    val latestFlushProgress by rememberUpdatedState<suspend () -> Unit>({
        val targetPage = pendingRemoteProgressPage
        val pendingSince = pendingRemoteProgressSinceMillis
        if (targetPage != null && pendingSince != null) {
            val pendingAge = SystemClock.elapsedRealtime() - pendingSince
            if (pendingAge >= ReaderProgressSyncDelayMillis) {
                saveRemoteProgress(targetPage)
            }
        }
    })

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lifecycleOwner.lifecycleScope.launch { latestFlushProgress() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lifecycleOwner.lifecycleScope.launch { latestFlushProgress() }
        }
    }

    val s = session
    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading session...") }
        return
    }

    val client = remember { KavitaClient(ctx, sessionStore) }
    val activeImageLoader = readerImageLoader ?: fallbackImageLoader
    fun pageModel(index: Int): Any? = offlineChapter?.pages?.getOrNull(index)
        ?: if (index in 0 until pages) client.pageImageUrl(s.baseUrl, s.apiKey, chapterId, index) else null
    val rtl = rightToLeft
    val spreadPages = spreadPagesFor(page, rtl)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val turnVisualDistancePx = viewportWidthPx
        val layout = readerPageLayout(
            page = page,
            pageCount = pages,
            portrait = portrait,
            pageDimensions = pageDimensions
        )
        val portraitCurlState = remember(chapterId) { PageCurlState(initialCurrent = page) }
        val spreadCurlState = remember(chapterId) {
            PageCurlState(
                initialCurrent = ReaderSpreadCurlVisualCurrent,
                turnEndFractionX = ReaderSpreadCurlTurnEndFractionX
            )
        }
        val curlBackPageColor = if (settings.reader.invertMode == InvertMode.Off) {
            Color(0xFFFAF7F2)
        } else {
            Color(0xFF101010)
        }
        // In Curl mode every branch (curl, slide overlay, static fallback) letterboxes with
        // the paper colour, so landing on a slide-fallback page (cover, wide-adjacent
        // single) does not flip the margins back to near-black mid-read.
        val readerPageBackground = if (settings.reader.pageTurnMode == PageTurnMode.Curl) {
            curlBackPageColor
        } else {
            Color(0xFF111111)
        }
        val portraitCurlConfig = rememberPageCurlConfig(backPageColor = curlBackPageColor)
        val spreadCurlConfig = rememberPageCurlConfig(
            backPageColor = curlBackPageColor,
            backPageContentAlpha = 0.96f,
            dragInteraction = PageCurlConfig.StartEndDragInteraction(
                pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge
            )
        )
        LaunchedEffect(curlBackPageColor) {
            portraitCurlConfig.backPageColor = curlBackPageColor
            spreadCurlConfig.backPageColor = curlBackPageColor
        }
        fun prefetchTargetsFor(indices: List<Int>): List<ReaderPrefetchTarget> {
            return indices.mapNotNull { index ->
                val model = pageModel(index) as? String ?: return@mapNotNull null
                ReaderPrefetchTarget(
                    model = model,
                    targetWidth = readerPrefetchSlotWidthPx(
                        page = index,
                        pageCount = pages,
                        portrait = portrait,
                        pageDimensions = pageDimensions,
                        viewportWidthPx = viewportWidthPx
                    ),
                    targetHeight = viewportHeightPx.roundToInt().coerceAtLeast(1)
                )
            }
        }
        LaunchedEffect(
            page,
            pages,
            portrait,
            pageDimensions,
            offlineChapter,
            activeImageLoader,
            s.baseUrl,
            s.apiKey,
            settings.reader.prefetchTurns,
            settings.reader.invertMode,
            settings.reader.invertWhiteThreshold
        ) {
            if (offlineChapter != null || pages <= 0) return@LaunchedEffect
            val indices = readerPrefetchPageIndicesAround(
                page = page,
                pageCount = pages,
                portrait = portrait,
                pageDimensions = pageDimensions,
                turns = settings.reader.prefetchTurns
            )
            val targets = prefetchTargetsFor(indices)
            prefetchReaderPages(
                context = ctx,
                imageLoader = activeImageLoader,
                targets = targets
            )
            if (settings.reader.invertMode == InvertMode.Smart) {
                preAnalyzeReaderPages(
                    context = ctx,
                    imageLoader = activeImageLoader,
                    models = targets.map { it.model }.distinct(),
                    whiteThreshold = settings.reader.invertWhiteThreshold,
                    invertDecisionCache = invertDecisionCache
                )
            }
        }
        val showingFinalPage = if (layout.singlePage) {
            page >= pages - 1
        } else {
            spreadPages.leftPage >= pages - 1 || spreadPages.rightPage >= pages - 1
        }
        // Use the layout's own step sizes. layout.previousStep already accounts for a
        // wide/cover page just before the current view (step back 1, not a full 2), so
        // the page before a spread is not skipped when paging backwards.
        val nextPageTurnStep = layout.nextStep
        val previousPageTurnStep = layout.previousStep
        val baseZoomScale = 1f
        val totalZoomScale = baseZoomScale * zoomPan.userScale
        val panBounds = readerPanBoundsPx(viewportWidthPx, viewportHeightPx, totalZoomScale)
        val zoomPanEnabled = totalZoomScale > 1f + ReaderZoomEpsilon
        val usePortraitCurl =
            settings.reader.pageTurnMode == PageTurnMode.Curl &&
                settings.reader.pageTransitionAnimation &&
                portrait &&
                layout.singlePage &&
                !zoomPanEnabled
        val useSpreadCurl =
            settings.reader.pageTurnMode == PageTurnMode.Curl &&
                settings.reader.pageTransitionAnimation &&
                !portrait &&
                // A wide page is one sheet printed across the whole spread, so it turns as
                // a full-width leaf with the fold at the centre. Other landscape singles
                // (cover, chapter end, the page displayed alone next to a wide one) sit
                // centred with no spine under them, so they fall back to Slide.
                (!layout.singlePage || pageDimensions.pageIsWide(page)) &&
                !zoomPanEnabled
        val useCurl = usePortraitCurl || useSpreadCurl
        val initialZoomPanState = ReaderZoomPanState()

        LaunchedEffect(page, pages, usePortraitCurl, useSpreadCurl, rtl) {
            if (usePortraitCurl && pages > 0 && portraitCurlState.current != page) {
                portraitCurlState.snapTo(page)
            }
            if (useSpreadCurl && pages > 0 && spreadCurlState.current != ReaderSpreadCurlVisualCurrent) {
                spreadCurlState.snapTo(ReaderSpreadCurlVisualCurrent)
            }
        }

        LaunchedEffect(useCurl, rtl) {
            activeCurlDirection = null
            activeCurlTargetPage = null
            curlDragStartPointer = null
            curlDragProgress = 0f
            dragBoundaryDirection = null
            if (!useCurl) {
                return@LaunchedEffect
            }
            if (usePortraitCurl && pages > 0) {
                portraitCurlState.snapTo(page)
            }
            if (useSpreadCurl && pages > 0) {
                spreadCurlState.snapTo(ReaderSpreadCurlVisualCurrent)
            }
        }

        fun zoomTurnLandingOffsetX(direction: ReaderTurnDirection): Float {
            val physicalSign = readerTurnPhysicalSign(rtl, direction)
            return (-physicalSign * panBounds.maxX).coerceIn(-panBounds.maxX, panBounds.maxX)
        }

        fun rememberZoomTurnLanding(direction: ReaderTurnDirection) {
            if (zoomPan.userScale > 1f + ReaderZoomEpsilon) {
                pendingZoomLandingDirection = direction
            }
        }

        LaunchedEffect(page) {
            zoomAnimationJob?.cancel()
            closeAnimationJob?.cancel()
            val landingDirection = pendingZoomLandingDirection
            pendingZoomLandingDirection = null
            zoomPan = if (zoomPan.userScale > 1f + ReaderZoomEpsilon) {
                zoomPan.copy(
                    offsetX = landingDirection
                        ?.let(::zoomTurnLandingOffsetX)
                        ?: zoomPan.offsetX.coerceIn(-panBounds.maxX, panBounds.maxX),
                    offsetY = zoomPan.offsetY.coerceIn(-panBounds.maxY, panBounds.maxY)
                )
            } else {
                initialZoomPanState
            }
            closeDragOffsetY = 0f
        }

        fun turnTarget(
            direction: ReaderTurnDirection,
            step: Int,
            completeWhenPastEnd: Boolean
        ): Int? = readerTurnTargetPage(
            currentPage = page,
            pageCount = pages,
            direction = direction,
            step = step,
            completeWhenPastEnd = completeWhenPastEnd
        )

        fun runBoundaryAction(direction: ReaderTurnDirection, completeWhenPastEnd: Boolean) {
            when (direction) {
                ReaderTurnDirection.Next -> if (completeWhenPastEnd) completeChapter()
                ReaderTurnDirection.Previous -> if (page == 0) resetChapterAndExit()
            }
        }

        lateinit var requestTurn: (ReaderTurnDirection, Int, Boolean) -> Unit

        fun pageCurlDirection(direction: ReaderTurnDirection): PageCurlTurnDirection =
            when (direction) {
                ReaderTurnDirection.Next -> PageCurlTurnDirection.Forward
                ReaderTurnDirection.Previous -> PageCurlTurnDirection.Backward
            }

        fun curlPointer(position: Offset): Offset =
            if (rtl) Offset(viewportWidthPx - position.x, position.y) else position

        fun curlTurnStep(direction: ReaderTurnDirection): Int =
            if (useSpreadCurl) {
                if (direction == ReaderTurnDirection.Next) nextPageTurnStep else previousPageTurnStep
            } else {
                1
            }

        // A leaf can land on a spread or on a wide page (a full-width sheet), but not on a
        // centred landscape single (cover, chapter end, the lone page beside a wide one):
        // there is no spine under those, so such turns use the Slide path instead.
        fun curlTurnTargetEligible(target: Int): Boolean {
            if (!useSpreadCurl) return true
            val targetLayout = readerPageLayout(target, pages, portrait, pageDimensions)
            return !targetLayout.singlePage || pageDimensions.pageIsWide(target)
        }

        fun requestCurlTurn(
            direction: ReaderTurnDirection,
            step: Int,
            completeWhenPastEnd: Boolean,
            tapPosition: Offset? = null
        ) {
            // A slide transition (shift, boundary fallback) may be covering the curl;
            // queue the turn and replay it once the transition finishes.
            if (transitionSettling || activeTransition != null) {
                queuedTurn = PendingReaderTurn(direction, step, completeWhenPastEnd)
                return
            }
            val target = turnTarget(direction, step, completeWhenPastEnd)
            if (target == null) {
                runBoundaryAction(direction, completeWhenPastEnd)
                return
            }
            val curlState = if (useSpreadCurl) spreadCurlState else portraitCurlState
            val spreadCurl = useSpreadCurl
            activeCurlDirection = direction
            activeCurlTargetPage = target
            // The tap position picks which corner leads the fold (fork quietMiddle).
            val curlTapPosition = tapPosition?.let(::curlPointer)
            scope.launch {
                when (pageCurlDirection(direction)) {
                    PageCurlTurnDirection.Forward -> curlState.next(tapPosition = curlTapPosition)
                    PageCurlTurnDirection.Backward -> curlState.prev(tapPosition = curlTapPosition)
                }
                rememberZoomTurnLanding(direction)
                page = target
                if (spreadCurl) {
                    curlState.snapTo(ReaderSpreadCurlVisualCurrent)
                }
                activeCurlDirection = null
                activeCurlTargetPage = null
            }
        }

        fun settleTransition(
            commit: Boolean,
            completeWhenPastEnd: Boolean
        ) {
            val transition = activeTransition
            if (transition == null) {
                val boundaryDirection = dragBoundaryDirection
                dragBoundaryDirection = null
                if (commit && boundaryDirection != null) {
                    runBoundaryAction(boundaryDirection, completeWhenPastEnd)
                }
                transitionProgress = 0f
                return
            }

            if (!settings.reader.pageTransitionAnimation) {
                if (commit) {
                    rememberZoomTurnLanding(transition.direction)
                    page = transition.targetPage
                }
                activeTransition = null
                transitionProgress = 0f
                transitionSettling = false
                return
            }

            transitionSettling = true
            scope.launch {
                val targetProgress = if (commit) 1f else 0f
                Animatable(transitionProgress).animateTo(
                    targetValue = targetProgress,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) {
                    transitionProgress = value
                }
                if (commit) {
                    rememberZoomTurnLanding(transition.direction)
                    page = transition.targetPage
                    withFrameNanos { }
                }
                activeTransition = null
                transitionProgress = 0f
                transitionSettling = false
            }
        }

        fun requestSlideTurn(direction: ReaderTurnDirection, step: Int, completeWhenPastEnd: Boolean) {
            if (transitionSettling || activeTransition != null) {
                queuedTurn = PendingReaderTurn(direction, step, completeWhenPastEnd, slideOnly = true)
                return
            }
            val target = turnTarget(direction, step, completeWhenPastEnd)
            if (target == null) {
                runBoundaryAction(direction, completeWhenPastEnd)
                return
            }
            if (!settings.reader.pageTransitionAnimation) {
                if (zoomPan.userScale > 1f + ReaderZoomEpsilon) {
                    pendingZoomLandingDirection = direction
                }
                page = target
                return
            }
            activeTransition = ReaderPageTransition(
                outgoingPage = page,
                targetPage = target,
                direction = direction,
                distanceFraction = readerSlideDistanceFraction(step, portrait, layout.singlePage)
            )
            transitionProgress = 0f
            settleTransition(commit = true, completeWhenPastEnd = completeWhenPastEnd)
        }

        fun curlRouteForTurn(direction: ReaderTurnDirection, step: Int, completeWhenPastEnd: Boolean): Boolean {
            if (!(useCurl && (useSpreadCurl || step == 1))) return false
            val target = turnTarget(direction, step, completeWhenPastEnd)
            // Boundary turns (null target) stay on the curl path for the edge resistance.
            return target == null || curlTurnTargetEligible(target)
        }

        requestTurn = requestTurnLambda@{ direction, step, completeWhenPastEnd ->
            if (curlRouteForTurn(direction, step, completeWhenPastEnd)) {
                requestCurlTurn(direction, step, completeWhenPastEnd)
                return@requestTurnLambda
            }
            requestSlideTurn(direction, step, completeWhenPastEnd)
        }

        fun requestTurnFromTap(
            direction: ReaderTurnDirection,
            step: Int,
            completeWhenPastEnd: Boolean,
            tapPosition: Offset
        ) {
            if (curlRouteForTurn(direction, step, completeWhenPastEnd)) {
                requestCurlTurn(direction, step, completeWhenPastEnd, tapPosition)
            } else {
                requestSlideTurn(direction, step, completeWhenPastEnd)
            }
        }

        // Spread shift (long-press / menu ±1) animates as a Slide transition in both
        // modes: in Curl mode it draws over the still-mounted PageCurl, so there is no
        // unmount/remount churn (the original cause of the image thrash).
        fun requestSingleStep(direction: ReaderTurnDirection, completeWhenPastEnd: Boolean) {
            requestSlideTurn(direction, 1, completeWhenPastEnd)
        }

        LaunchedEffect(activeTransition, transitionSettling, queuedTurn, page) {
            val pending = queuedTurn
            if (activeTransition == null && !transitionSettling && pending != null) {
                queuedTurn = null
                if (pending.slideOnly) {
                    requestSlideTurn(pending.direction, pending.step, pending.completeWhenPastEnd)
                } else {
                    requestTurn(pending.direction, pending.step, pending.completeWhenPastEnd)
                }
            }
        }

        fun beginTurnDrag(direction: ReaderTurnDirection, pointer: Offset) {
            if (useCurl) {
                curlDragStartPointer = pointer
                curlDragProgress = 0f
                return
            }
        }

        fun updateTurnDrag(direction: ReaderTurnDirection, progress: Float, pointer: Offset) {
            if (useCurl) {
                // Ignore new curl drags only while a SETTLING slide transition covers the
                // curl (shift / tap fallback animating, a few hundred ms). A drag-driven
                // fallback transition has transitionSettling == false and must keep
                // receiving its own drag frames through the fall-through below.
                if (activeCurlDirection == null && transitionSettling) return
                var fallThroughToSlide = false
                if (activeCurlDirection != direction) {
                    val step = curlTurnStep(direction)
                    val target = turnTarget(direction, step, direction == ReaderTurnDirection.Next && showingFinalPage)
                    if (target != null && !curlTurnTargetEligible(target)) {
                        // Turning towards a centred landscape single: this drag runs as a
                        // Slide transition below instead of a leaf curl.
                        fallThroughToSlide = true
                    } else {
                    dragBoundaryDirection = if (target == null) direction else null
                    activeCurlDirection = direction
                    activeCurlTargetPage = target
                    val start = curlDragStartPointer ?: pointer
                    if (target != null) {
                        val curlState = if (useSpreadCurl) spreadCurlState else portraitCurlState
                        val spreadCurl = useSpreadCurl
                        // The spike validated PageEdge for the spread leaf (paper edge anchored
                        // to the finger, crease at the midpoint, stops at the spine on its own)
                        // and the library default for portrait single pages (Stage 1 feel).
                        val pointerBehavior = if (spreadCurl) {
                            PageCurlConfig.DragInteraction.PointerBehavior.PageEdge
                        } else {
                            PageCurlConfig.DragInteraction.PointerBehavior.Default
                        }
                        scope.launch {
                            if (spreadCurl) {
                                curlState.snapTo(ReaderSpreadCurlVisualCurrent)
                            }
                            if (curlState.beginTurn(pageCurlDirection(direction), curlPointer(start), pointerBehavior)) {
                                curlState.dragTurnTo(curlPointer(pointer))
                            }
                        }
                    }
                    }
                } else {
                    val curlState = if (useSpreadCurl) spreadCurlState else portraitCurlState
                    scope.launch {
                        curlState.dragTurnTo(curlPointer(pointer))
                    }
                }
                if (!fallThroughToSlide) {
                    curlDragProgress = progress
                    return
                }
            }
            if (transitionSettling) return
            if (activeTransition?.direction != direction) {
                val step = if (direction == ReaderTurnDirection.Next) {
                    nextPageTurnStep
                } else {
                    previousPageTurnStep
                }
                val completeAtBoundary = direction == ReaderTurnDirection.Next && showingFinalPage
                val target = turnTarget(direction, step, completeAtBoundary)
                activeTransition = target?.let {
                    ReaderPageTransition(
                        page,
                        it,
                        direction,
                        distanceFraction = readerSlideDistanceFraction(step, portrait, layout.singlePage)
                    )
                }
                dragBoundaryDirection = if (target == null) direction else null
            }
            transitionProgress = progress
        }

        fun settleTurnDrag(velocityX: Float) {
            val curlDirection = activeCurlDirection
            if (useCurl && curlDirection != null) {
                val commit = shouldCommitReaderTurn(
                    progress = curlDragProgress,
                    velocityX = velocityX,
                    direction = curlDirection,
                    rightToLeft = rtl,
                    minimumFlingVelocity = minimumFlingVelocity
                )
                val boundaryDirection = dragBoundaryDirection
                val step = curlTurnStep(curlDirection)
                val target = turnTarget(
                    curlDirection,
                    step,
                    curlDirection == ReaderTurnDirection.Next && showingFinalPage
                )
                val curlState = if (useSpreadCurl) spreadCurlState else portraitCurlState
                val spreadCurl = useSpreadCurl
                activeCurlDirection = null
                curlDragStartPointer = null
                curlDragProgress = 0f
                dragBoundaryDirection = null
                // Keep the in-flight target through the settle animation (commit or cancel):
                // the revealed area under the flap must keep showing the incoming spread
                // while the paper returns, or the content pops at release.
                activeCurlTargetPage = target
                if (commit && boundaryDirection != null) {
                    activeCurlTargetPage = null
                    runBoundaryAction(
                        boundaryDirection,
                        completeWhenPastEnd = boundaryDirection == ReaderTurnDirection.Next && showingFinalPage
                    )
                    return
                }
                scope.launch {
                    curlState.settleTurn(commit)
                    if (commit) {
                        rememberZoomTurnLanding(curlDirection)
                        target?.let { page = it }
                    }
                    if (spreadCurl) {
                        curlState.snapTo(ReaderSpreadCurlVisualCurrent)
                    }
                    activeCurlTargetPage = null
                }
                return
            }
            settleTransition(
                commit = shouldCommitReaderTurn(
                    progress = transitionProgress,
                    velocityX = velocityX,
                    direction = activeTransition?.direction ?: dragBoundaryDirection,
                    rightToLeft = rtl,
                    minimumFlingVelocity = minimumFlingVelocity
                ),
                completeWhenPastEnd = dragBoundaryDirection == ReaderTurnDirection.Next &&
                    showingFinalPage
            )
        }

        fun cancelTurnDrag() {
            if (useCurl && activeCurlDirection != null) {
                val curlState = if (useSpreadCurl) spreadCurlState else portraitCurlState
                val spreadCurl = useSpreadCurl
                activeCurlDirection = null
                // activeCurlTargetPage stays as set by the drag so the revealed area keeps
                // showing the incoming spread while the paper settles back; cleared below.
                curlDragStartPointer = null
                curlDragProgress = 0f
                dragBoundaryDirection = null
                scope.launch {
                    curlState.settleTurn(commit = false)
                    if (spreadCurl) {
                        curlState.snapTo(ReaderSpreadCurlVisualCurrent)
                    }
                    activeCurlTargetPage = null
                }
                return
            }
            settleTransition(commit = false, completeWhenPastEnd = false)
        }

        LaunchedEffect(
            activeTransition?.targetPage,
            pages,
            portrait,
            pageDimensions,
            offlineChapter,
            activeImageLoader,
            settings.reader.invertMode,
            settings.reader.invertWhiteThreshold
        ) {
            val targetPage = activeTransition?.targetPage ?: return@LaunchedEffect
            if (offlineChapter != null || pages <= 0) return@LaunchedEffect
            val targets = prefetchTargetsFor(
                readerVisiblePageIndices(
                    page = targetPage,
                    pageCount = pages,
                    portrait = portrait,
                    pageDimensions = pageDimensions
                )
            )
            prefetchReaderPages(
                context = ctx,
                imageLoader = activeImageLoader,
                targets = targets
            )
            if (settings.reader.invertMode == InvertMode.Smart) {
                preAnalyzeReaderPages(
                    context = ctx,
                    imageLoader = activeImageLoader,
                    models = targets.map { it.model }.distinct(),
                    whiteThreshold = settings.reader.invertWhiteThreshold,
                    invertDecisionCache = invertDecisionCache
                )
            }
        }

        if (error != null) {
            Text("Error: $error", color = Color.Red, modifier = Modifier.padding(12.dp))
        }

        val transition = activeTransition
        val transitionVisible =
            transition != null && settings.reader.pageTransitionAnimation
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .graphicsLayer {
                    val safeViewportHeight = viewportHeightPx.coerceAtLeast(1f)
                    translationY = closeDragOffsetY
                    alpha = (1f - closeDragOffsetY / safeViewportHeight * 0.35f).coerceIn(0.65f, 1f)
                }
        ) {
            // Keep PageCurl mounted whenever the curl mode is active (not just during a
            // turn): the neighbour pages it composes at rest are exactly the images the
            // next turn needs, so the gesture starts warm instead of kicking off image
            // loads on its first frame. Slide transitions (shift, boundary fallbacks) draw
            // on top of it rather than unmounting it — the remount recomposition is what
            // made the images thrash.
            if (usePortraitCurl) {
                val curlMirror = if (rtl) -1f else 1f
                PageCurl(
                    count = pages,
                    state = portraitCurlState,
                    config = portraitCurlConfig,
                    interactionsEnabled = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = curlMirror
                        }
                ) { cursor ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = curlMirror
                            }
                    ) {
                        ReaderPageView(
                            cursor = cursor,
                            pageCount = pages,
                            portrait = portrait,
                            pageDimensions = pageDimensions,
                            rightToLeft = rtl,
                            pageModel = ::pageModel,
                            imageLoader = activeImageLoader,
                            invertMode = settings.reader.invertMode,
                            whiteThreshold = settings.reader.invertWhiteThreshold,
                            invertDecisionCache = invertDecisionCache,
                            pageBackground = curlBackPageColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else if (useSpreadCurl) {
                val curlMirror = if (rtl) -1f else 1f
                // During a turn the under/flap pages come from the in-flight target; at rest
                // they pre-render the forward target spread (the likely next turn), which
                // warms its images while the reader sits on the current spread.
                val forwardRestTargetPage = turnTarget(ReaderTurnDirection.Next, nextPageTurnStep, false) ?: page
                val underPage = activeCurlTargetPage ?: forwardRestTargetPage
                PageCurl(
                    count = ReaderSpreadCurlVisualPageCount,
                    state = spreadCurlState,
                    config = spreadCurlConfig,
                    interactionsEnabled = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = curlMirror
                        },
                    backContent = { _, forward ->
                        val targetIsWide = pageDimensions.pageIsWide(underPage)
                        val backPage = readerSpreadCurlBackPageIndex(
                            targetPage = underPage,
                            pageCount = pages,
                            direction = if (forward) ReaderTurnDirection.Next else ReaderTurnDirection.Previous,
                            targetIsWide = targetIsWide
                        )
                        if (targetIsWide) {
                            // A wide page lands spanning the whole spread, so its back-face
                            // content is laid out full width; the fold clip reveals the near
                            // half of the artwork as the leaf turns.
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = curlMirror
                                    }
                            ) {
                                ReaderPageView(
                                    cursor = backPage,
                                    pageCount = pages,
                                    portrait = false,
                                    pageDimensions = pageDimensions,
                                    rightToLeft = rtl,
                                    pageModel = ::pageModel,
                                    imageLoader = activeImageLoader,
                                    invertMode = settings.reader.invertMode,
                                    whiteThreshold = settings.reader.invertWhiteThreshold,
                                    invertDecisionCache = invertDecisionCache,
                                    pageBackground = curlBackPageColor,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                        val backPageAlignment = if (forward == rtl) {
                            Alignment.CenterStart
                        } else {
                            Alignment.CenterEnd
                        }
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.5f)
                                    .align(if (forward) Alignment.CenterStart else Alignment.CenterEnd)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = curlMirror
                                        }
                                ) {
                                    ReaderPageView(
                                        cursor = backPage,
                                        pageCount = pages,
                                        portrait = true,
                                        pageDimensions = pageDimensions,
                                        rightToLeft = rtl,
                                        pageModel = ::pageModel,
                                        imageLoader = activeImageLoader,
                                        invertMode = settings.reader.invertMode,
                                        whiteThreshold = settings.reader.invertWhiteThreshold,
                                        invertDecisionCache = invertDecisionCache,
                                        pageBackground = curlBackPageColor,
                                        singlePageAlignmentOverride = backPageAlignment,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                        }
                    }
                ) { cursor ->
                    val renderPage = if (cursor != spreadCurlState.current) {
                        underPage
                    } else {
                        page
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = curlMirror
                            }
                    ) {
                        ReaderPageView(
                            cursor = renderPage,
                            pageCount = pages,
                            portrait = portrait,
                            pageDimensions = pageDimensions,
                            rightToLeft = rtl,
                            pageModel = ::pageModel,
                            imageLoader = activeImageLoader,
                            invertMode = settings.reader.invertMode,
                            whiteThreshold = settings.reader.invertWhiteThreshold,
                            invertDecisionCache = invertDecisionCache,
                            pageBackground = curlBackPageColor,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else if (!transitionVisible) {
                ReaderPageView(
                    cursor = page,
                    pageCount = pages,
                    portrait = portrait,
                    pageDimensions = pageDimensions,
                    rightToLeft = rtl,
                    pageModel = ::pageModel,
                    imageLoader = activeImageLoader,
                    invertMode = settings.reader.invertMode,
                    whiteThreshold = settings.reader.invertWhiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = readerPageBackground,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = zoomPan.offsetX
                            translationY = zoomPan.offsetY
                            scaleX = totalZoomScale
                            scaleY = totalZoomScale
                        }
                )
            }
            // The slide transition draws on top of whichever base is composed (a mounted
            // curl or nothing), fully covering it with its two opaque pages.
            if (transitionVisible) {
                requireNotNull(transition)
                val progress = transitionProgress.coerceIn(0f, 1f)
                val physicalSign = readerTurnPhysicalSign(rtl, transition.direction)
                val slideDistancePx = viewportWidthPx * transition.distanceFraction
                val targetOffsetX = if (zoomPanEnabled) {
                    zoomTurnLandingOffsetX(transition.direction)
                } else {
                    zoomPan.offsetX
                }
                if (transition.distanceFraction < 1f && !zoomPanEnabled) {
                    // Spread shift: the two whole-spread layers cannot slide seamlessly
                    // because each hugs the pages to its own spine — at the layer seam the
                    // outer margins meet and open a gap at the spine. Instead draw one
                    // paper background and the three page images (enter / stay / exit)
                    // glued edge-to-edge, all travelling by the staying page's rendered
                    // width; the leaving page fades out.
                    fun halfPageWidthPx(pageIndex: Int): Float {
                        val dims = pageDimensions[pageIndex] ?: return viewportWidthPx / 2f
                        val w = dims.width?.toFloat() ?: return viewportWidthPx / 2f
                        val h = dims.height?.toFloat() ?: return viewportWidthPx / 2f
                        if (w <= 0f || h <= 0f) return viewportWidthPx / 2f
                        return minOf(viewportWidthPx / 2f, viewportHeightPx * (w / h))
                    }

                    val (enterPage, stayPage, exitPage) = readerShiftStripPages(
                        outgoingPage = transition.outgoingPage,
                        targetPage = transition.targetPage,
                        direction = transition.direction
                    )
                    val geometry = readerShiftStripGeometry(
                        physicalSign = physicalSign,
                        halfViewportPx = viewportWidthPx / 2f,
                        stayWidthPx = halfPageWidthPx(stayPage),
                        enterWidthPx = halfPageWidthPx(enterPage),
                        exitWidthPx = halfPageWidthPx(exitPage)
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(readerPageBackground)
                    ) {
                        listOf(
                            // The entering page starts glued to the staying page, which puts
                            // a sliver of it inside the resting view's outer margin; fade it
                            // in over the first quarter so it does not pop into existence.
                            Triple(enterPage, geometry.enterStartLeftPx, (progress * 4f).coerceAtMost(1f)),
                            Triple(stayPage, geometry.stayStartLeftPx, 1f),
                            Triple(exitPage, geometry.exitStartLeftPx, 1f - progress)
                        ).forEach { (stripPage, startLeftPx, pageAlpha) ->
                            if (stripPage in 0 until pages) {
                                val widthDp = with(density) { halfPageWidthPx(stripPage).toDp() }
                                key(stripPage) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .width(widthDp)
                                            .graphicsLayer {
                                                translationX = startLeftPx + geometry.travelPx * progress
                                                alpha = pageAlpha
                                            }
                                    ) {
                                        ReaderPageView(
                                            cursor = stripPage,
                                            pageCount = pages,
                                            portrait = true,
                                            pageDimensions = pageDimensions,
                                            rightToLeft = rtl,
                                            pageModel = ::pageModel,
                                            imageLoader = activeImageLoader,
                                            invertMode = settings.reader.invertMode,
                                            whiteThreshold = settings.reader.invertWhiteThreshold,
                                            invertDecisionCache = invertDecisionCache,
                                            pageBackground = readerPageBackground,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                ReaderPageView(
                    cursor = transition.targetPage,
                    pageCount = pages,
                    portrait = portrait,
                    pageDimensions = pageDimensions,
                    rightToLeft = rtl,
                    pageModel = ::pageModel,
                    imageLoader = activeImageLoader,
                    invertMode = settings.reader.invertMode,
                    whiteThreshold = settings.reader.invertWhiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = readerPageBackground,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -physicalSign * slideDistancePx * (1f - progress) + targetOffsetX
                            translationY = zoomPan.offsetY
                            scaleX = totalZoomScale
                            scaleY = totalZoomScale
                        }
                )
                ReaderPageView(
                    cursor = transition.outgoingPage,
                    pageCount = pages,
                    portrait = portrait,
                    pageDimensions = pageDimensions,
                    rightToLeft = rtl,
                    pageModel = ::pageModel,
                    imageLoader = activeImageLoader,
                    invertMode = settings.reader.invertMode,
                    whiteThreshold = settings.reader.invertWhiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = readerPageBackground,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = physicalSign * slideDistancePx * progress + zoomPan.offsetX
                            translationY = zoomPan.offsetY
                            scaleX = totalZoomScale
                            scaleY = totalZoomScale
                        }
                )
                }
            }
        }

        key(page, rtl, nextPageTurnStep, previousPageTurnStep, showingFinalPage) {
            ReaderTapLayer(
                rightToLeft = rtl,
                onNextSpread = { position ->
                    requestTurnFromTap(ReaderTurnDirection.Next, nextPageTurnStep, showingFinalPage, position)
                },
                onPreviousSpread = { position ->
                    requestTurnFromTap(ReaderTurnDirection.Previous, previousPageTurnStep, false, position)
                },
                onNextSingle = {
                    requestSingleStep(ReaderTurnDirection.Next, page >= pages - 1)
                },
                onPreviousSingle = {
                    requestSingleStep(ReaderTurnDirection.Previous, false)
                },
                onCenterTap = { showReaderMenu = !showReaderMenu },
                turnVisualDistancePx = turnVisualDistancePx,
                zoomPanEnabled = zoomPanEnabled,
                panOffsetX = zoomPan.offsetX,
                panOffsetY = zoomPan.offsetY,
                panMaxX = panBounds.maxX,
                panMaxY = panBounds.maxY,
                onPan = { x, y ->
                    zoomAnimationJob?.cancel()
                    zoomPan = zoomPan.copy(offsetX = x, offsetY = y)
                },
                onTurnDragStart = ::beginTurnDrag,
                onTurnDrag = ::updateTurnDrag,
                onTurnDragEnd = ::settleTurnDrag,
                onTurnDragCancel = ::cancelTurnDrag,
                directionLockEnabled = useCurl,
                closeSwipeEnabled = activeTransition == null &&
                    activeCurlDirection == null &&
                    !transitionSettling &&
                    !showReaderMenu,
                closeVisualDistancePx = viewportHeightPx,
                onCloseDrag = { offset ->
                    closeAnimationJob?.cancel()
                    closeDragOffsetY = offset
                },
                onCloseDragEnd = { commit ->
                    closeAnimationJob?.cancel()
                    closeAnimationJob = scope.launch {
                        val start = closeDragOffsetY
                        val target = if (commit) viewportHeightPx else 0f
                        Animatable(start).animateTo(
                            targetValue = target,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) {
                            closeDragOffsetY = value
                        }
                        if (commit) {
                            onBack()
                        } else {
                            closeDragOffsetY = 0f
                        }
                    }
                },
                onCloseDragCancel = {
                    closeAnimationJob?.cancel()
                    closeAnimationJob = scope.launch {
                        Animatable(closeDragOffsetY).animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) {
                            closeDragOffsetY = value
                        }
                        closeDragOffsetY = 0f
                    }
                },
                onDoubleTap = {
                    val start = zoomPan
                    val target = start.withDoubleTapZoom(
                        tapPosition = it,
                        baseZoomScale = baseZoomScale,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx,
                        initialState = initialZoomPanState
                    )
                    zoomAnimationJob?.cancel()
                    zoomAnimationJob = scope.launch {
                        Animatable(0f).animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) {
                            zoomPan = start.lerpTo(target, value)
                        }
                        zoomPan = target
                    }
                },
                onTransform = { zoomChange, panChange, focalPoint ->
                    zoomAnimationJob?.cancel()
                    zoomPan = zoomPan.withTransform(
                        zoomChange = zoomChange,
                        panChange = panChange,
                        focalPoint = focalPoint,
                        baseZoomScale = baseZoomScale,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx
                    )
                }
            )
        }

        if (showReaderMenu) {
            ReaderMenuOverlay(
                page = page,
                pages = pages,
                rightToLeft = rtl,
                onBack = onBack,
                onDismiss = { showReaderMenu = false },
                onToggleDirection = {
                    val newValue = !rightToLeft
                    rightToLeft = newValue
                    scope.launch { settingsStore.setRightToLeft(newValue) }
                },
                invertMode = settings.reader.invertMode,
                onSetInvertMode = { mode -> scope.launch { settingsStore.setInvertMode(mode) } },
                onNextSingle = {
                    requestSingleStep(ReaderTurnDirection.Next, page >= pages - 1)
                },
                onPreviousSingle = {
                    requestSingleStep(ReaderTurnDirection.Previous, false)
                },
                onJumpToPage = { jumpToPage(it) }
            )
        }

    }
}

