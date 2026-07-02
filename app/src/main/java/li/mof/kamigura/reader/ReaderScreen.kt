package li.mof.kamigura.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import li.mof.kamigura.AppSettings
import li.mof.kamigura.AppSettingsStore
import li.mof.kamigura.FileDimensionDto
import li.mof.kamigura.InvertMode
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    sessionStore: KavitaSessionStore,
    settingsStore: AppSettingsStore,
    libraryId: Int,
    seriesId: Int,
    volumeId: Int,
    chapterId: Int,
    incognito: Boolean = false,
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
    var queuedTurn by remember { mutableStateOf<PendingReaderTurn?>(null) }
    var pendingZoomLandingDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var dragBoundaryDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var closeDragOffsetY by remember { mutableFloatStateOf(0f) }
    var closeAnimationJob by remember { mutableStateOf<Job?>(null) }
    val invertDecisionCache = remember { mutableStateMapOf<ReaderInvertCacheKey, Boolean>() }
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }
    var pendingRemoteProgressPage by remember { mutableStateOf<Int?>(null) }
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
            if (pendingRemoteProgressPage == targetPage) pendingRemoteProgressPage = null
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
            if (pendingRemoteProgressPage == targetPage) pendingRemoteProgressPage = null
            if (offlineChapter != null && loadedSession != null) {
                offlineRepository.markProgressSynced(
                    session = loadedSession,
                    chapterId = chapterId,
                    expectedPage = targetPage
                )
            }
            true
        } catch (_: Throwable) {
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
    fun movePageBy(delta: Int, completeWhenPastEnd: Boolean = false) {
        if (delta < 0 && page == 0) {
            resetChapterAndExit()
            return
        }
        val targetPage = page + delta
        if (delta > 0 && pages > 0 && targetPage >= pages) {
            if (completeWhenPastEnd) {
                completeChapter()
            } else if (page != pages - 1) {
                page = pages - 1
            }
            return
        }
        val nextPage = clampPage(targetPage)
        if (nextPage != page) {
            page = nextPage
        }
    }
    fun jumpToPage(targetPage: Int) {
        val nextPage = clampPage(targetPage)
        if (nextPage != page) {
            page = nextPage
        }
    }
    fun nextSingle() { movePageBy(1, completeWhenPastEnd = page >= pages - 1) }
    fun prevSingle() { movePageBy(-1) }

    LaunchedEffect(Unit) {
        val loadedSession = sessionStore.load()
        session = loadedSession
        val local = runCatching {
            offlineRepository.localChapter(loadedSession, chapterId)
        }.getOrNull()
        offlineChapter = local
        if (local != null) {
            pages = local.pages.size
            pageDimensions = local.dimensions
            page = local.record.localPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
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
            } catch (_: Throwable) {
                settings.reader.rightToLeft
            }
            if (local == null) {
                val info = loadedApi.chapterInfo(chapterId, includeDimensions = true)
                val pageCount = info.pages ?: 0
                pages = pageCount
                pageDimensions = info.pageDimensions.toPageDimensionMap()
                val savedPage = loadedApi.getProgress(chapterId).pageNum
                page = if (pages > 0) savedPage.coerceIn(0, pages - 1) else 0
                lastRemoteProgressPage = page
            } else if (!local.record.progressPending) {
                val savedPage = runCatching { loadedApi.getProgress(chapterId).pageNum }.getOrNull()
                if (savedPage != null) {
                    page = savedPage.coerceIn(0, pages - 1)
                    lastRemoteProgressPage = page
                }
            }
            readerReady = true
        } catch (t: Throwable) {
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
        delay(ReaderProgressSyncDelayMillis)
        saveRemoteProgress(page)
    }

    val latestFlushProgress by rememberUpdatedState<suspend () -> Unit>({
        val targetPage = pendingRemoteProgressPage ?: page
        saveRemoteProgress(targetPage)
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
        val initialZoomPanState = ReaderZoomPanState()

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
        ): Int? {
            if (pages <= 0) return null
            return when (direction) {
                ReaderTurnDirection.Next -> {
                    val rawTarget = page + step
                    when {
                        rawTarget < pages -> rawTarget
                        completeWhenPastEnd -> null
                        page < pages - 1 -> pages - 1
                        else -> null
                    }
                }
                ReaderTurnDirection.Previous -> {
                    if (page == 0) null else (page - step).coerceAtLeast(0)
                }
            }
        }

        fun runBoundaryAction(direction: ReaderTurnDirection, completeWhenPastEnd: Boolean) {
            when (direction) {
                ReaderTurnDirection.Next -> if (completeWhenPastEnd) completeChapter()
                ReaderTurnDirection.Previous -> if (page == 0) resetChapterAndExit()
            }
        }

        lateinit var requestTurn: (ReaderTurnDirection, Int, Boolean) -> Unit

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

        requestTurn = requestTurnLambda@{ direction, step, completeWhenPastEnd ->
            if (transitionSettling || activeTransition != null) {
                queuedTurn = PendingReaderTurn(direction, step, completeWhenPastEnd)
                return@requestTurnLambda
            }
            val target = turnTarget(direction, step, completeWhenPastEnd)
            if (target == null) {
                runBoundaryAction(direction, completeWhenPastEnd)
                return@requestTurnLambda
            }
            if (!settings.reader.pageTransitionAnimation) {
                if (zoomPan.userScale > 1f + ReaderZoomEpsilon) {
                    pendingZoomLandingDirection = direction
                }
                page = target
                return@requestTurnLambda
            }
            activeTransition = ReaderPageTransition(
                outgoingPage = page,
                targetPage = target,
                direction = direction
            )
            transitionProgress = 0f
            settleTransition(commit = true, completeWhenPastEnd = completeWhenPastEnd)
        }

        LaunchedEffect(activeTransition, transitionSettling, queuedTurn, page) {
            val pending = queuedTurn
            if (activeTransition == null && !transitionSettling && pending != null) {
                queuedTurn = null
                requestTurn(pending.direction, pending.step, pending.completeWhenPastEnd)
            }
        }

        fun updateTurnDrag(direction: ReaderTurnDirection, progress: Float) {
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
                    ReaderPageTransition(page, it, direction)
                }
                dragBoundaryDirection = if (target == null) direction else null
            }
            transitionProgress = progress
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
            if (transitionVisible) {
                requireNotNull(transition)
                val progress = transitionProgress.coerceIn(0f, 1f)
                val physicalSign = readerTurnPhysicalSign(rtl, transition.direction)
                val targetOffsetX = if (zoomPanEnabled) {
                    zoomTurnLandingOffsetX(transition.direction)
                } else {
                    zoomPan.offsetX
                }
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = -physicalSign * viewportWidthPx * (1f - progress) + targetOffsetX
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = physicalSign * viewportWidthPx * progress + zoomPan.offsetX
                            translationY = zoomPan.offsetY
                            scaleX = totalZoomScale
                            scaleY = totalZoomScale
                        }
                )
            } else {
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
        }

        key(page, nextPageTurnStep, previousPageTurnStep, showingFinalPage) {
            ReaderTapLayer(
                rightToLeft = rtl,
                onNextSpread = {
                    requestTurn(ReaderTurnDirection.Next, nextPageTurnStep, showingFinalPage)
                },
                onPreviousSpread = {
                    requestTurn(ReaderTurnDirection.Previous, previousPageTurnStep, false)
                },
                onNextSingle = { requestTurn(ReaderTurnDirection.Next, 1, page >= pages - 1) },
                onPreviousSingle = { requestTurn(ReaderTurnDirection.Previous, 1, false) },
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
                onTurnDrag = ::updateTurnDrag,
                onTurnDragEnd = {
                    settleTransition(
                        commit = shouldCommitReaderTurn(transitionProgress),
                        completeWhenPastEnd = dragBoundaryDirection == ReaderTurnDirection.Next &&
                            showingFinalPage
                    )
                },
                onTurnDragCancel = {
                    settleTransition(commit = false, completeWhenPastEnd = false)
                },
                closeSwipeEnabled = activeTransition == null && !transitionSettling && !showReaderMenu,
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
                onNextSingle = { nextSingle() },
                onPreviousSingle = { prevSingle() },
                onJumpToPage = { jumpToPage(it) }
            )
        }

    }
}

