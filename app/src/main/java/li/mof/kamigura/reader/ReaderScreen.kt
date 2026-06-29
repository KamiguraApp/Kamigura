package li.mof.kamigura.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import li.mof.kamigura.reader.internal.ReaderDragMode
import li.mof.kamigura.reader.internal.ReaderInvertCacheKey
import li.mof.kamigura.reader.internal.ReaderPendingCenterTap
import li.mof.kamigura.reader.internal.ReaderPrefetchTarget
import li.mof.kamigura.reader.internal.ReaderTapZone
import li.mof.kamigura.reader.internal.ReaderPageView
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
import li.mof.kamigura.ui.ValueBubbleSlider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
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

        LaunchedEffect(page) {
            zoomAnimationJob?.cancel()
            closeAnimationJob?.cancel()
            zoomPan = initialZoomPanState
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
                if (commit) page = transition.targetPage
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
            transition != null && settings.reader.pageTransitionAnimation && !zoomPanEnabled
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
                            translationX = -physicalSign * viewportWidthPx * (1f - progress)
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
                            translationX = physicalSign * viewportWidthPx * progress
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

@Composable
private fun ReaderTapLayer(
    rightToLeft: Boolean,
    onNextSpread: () -> Unit,
    onPreviousSpread: () -> Unit,
    onNextSingle: () -> Unit,
    onPreviousSingle: () -> Unit,
    onCenterTap: () -> Unit,
    turnVisualDistancePx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit = { _, _ -> },
    onTurnDragEnd: () -> Unit = {},
    onTurnDragCancel: () -> Unit = {},
    closeSwipeEnabled: Boolean = false,
    closeVisualDistancePx: Float = 1f,
    onCloseDrag: (Float) -> Unit = {},
    onCloseDragEnd: (Boolean) -> Unit = {},
    onCloseDragCancel: () -> Unit = {},
    onDoubleTap: (Offset) -> Unit = {},
    onTransform: (Float, Offset, Offset) -> Unit = { _, _, _ -> }
) {
    val latestOnNextSpread by rememberUpdatedState(onNextSpread)
    val latestOnPreviousSpread by rememberUpdatedState(onPreviousSpread)
    val latestOnNextSingle by rememberUpdatedState(onNextSingle)
    val latestOnPreviousSingle by rememberUpdatedState(onPreviousSingle)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Box(
        Modifier
            .fillMaxSize()
            .readerPinchZoom(onTransform = onTransform)
            .readerGestures(
                rightToLeft = rightToLeft,
                turnVisualDistancePx = turnVisualDistancePx,
                zoomPanEnabled = zoomPanEnabled,
                panOffsetX = panOffsetX,
                panOffsetY = panOffsetY,
                panMaxX = panMaxX,
                panMaxY = panMaxY,
                onPan = onPan,
                onTurnDrag = onTurnDrag,
                onTurnDragEnd = onTurnDragEnd,
                onTurnDragCancel = onTurnDragCancel,
                closeSwipeEnabled = closeSwipeEnabled,
                closeVisualDistancePx = closeVisualDistancePx,
                onCloseDrag = onCloseDrag,
                onCloseDragEnd = onCloseDragEnd,
                onCloseDragCancel = onCloseDragCancel,
                onLeftTap = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnNextSpread() else latestOnPreviousSpread()
                    }
                },
                onCenterTap = latestOnCenterTap,
                onRightTap = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnPreviousSpread() else latestOnNextSpread()
                    }
                },
                onLeftLongPress = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnNextSingle() else latestOnPreviousSingle()
                    }
                },
                onRightLongPress = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnPreviousSingle() else latestOnNextSingle()
                    }
                },
                onCenterDoubleTap = latestOnDoubleTap
            )
    )
}

@Composable
private fun Modifier.readerPinchZoom(
    onTransform: (Float, Offset, Offset) -> Unit
): Modifier {
    val latestOnTransform by rememberUpdatedState(onTransform)
    return pointerInput(Unit) {
        awaitEachGesture {
            var lastCentroid: Offset? = null
            var lastSpan = 0f

            while (true) {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                if (pressedChanges.isEmpty()) break
                if (pressedChanges.size < 2) {
                    lastCentroid = null
                    lastSpan = 0f
                    continue
                }

                var centroidX = 0f
                var centroidY = 0f
                pressedChanges.forEach { change ->
                    centroidX += change.position.x
                    centroidY += change.position.y
                }
                val centroid = Offset(
                    x = centroidX / pressedChanges.size,
                    y = centroidY / pressedChanges.size
                )
                val span = pressedChanges
                    .map { (it.position - centroid).getDistance() }
                    .average()
                    .toFloat()

                val previousCentroid = lastCentroid
                if (previousCentroid != null && lastSpan > 0f && span > 0f) {
                    latestOnTransform(span / lastSpan, centroid - previousCentroid, centroid)
                    pressedChanges.forEach { it.consume() }
                }

                lastCentroid = centroid
                lastSpan = span
            }
        }
    }
}

@Composable
private fun Modifier.readerGestures(
    rightToLeft: Boolean,
    turnVisualDistancePx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit,
    onTurnDragEnd: () -> Unit,
    onTurnDragCancel: () -> Unit,
    closeSwipeEnabled: Boolean = false,
    closeVisualDistancePx: Float = 1f,
    onCloseDrag: (Float) -> Unit = {},
    onCloseDragEnd: (Boolean) -> Unit = {},
    onCloseDragCancel: () -> Unit = {},
    onLeftTap: () -> Unit = {},
    onCenterTap: () -> Unit = {},
    onRightTap: () -> Unit = {},
    onLeftLongPress: () -> Unit = {},
    onRightLongPress: () -> Unit = {},
    onCenterDoubleTap: (Offset) -> Unit = {}
): Modifier {
    val latestRightToLeft by rememberUpdatedState(rightToLeft)
    val latestTurnVisualDistancePx by rememberUpdatedState(turnVisualDistancePx)
    val latestZoomPanEnabled by rememberUpdatedState(zoomPanEnabled)
    val latestPanOffsetX by rememberUpdatedState(panOffsetX)
    val latestPanOffsetY by rememberUpdatedState(panOffsetY)
    val latestPanMaxX by rememberUpdatedState(panMaxX)
    val latestPanMaxY by rememberUpdatedState(panMaxY)
    val latestCloseSwipeEnabled by rememberUpdatedState(closeSwipeEnabled)
    val latestCloseVisualDistancePx by rememberUpdatedState(closeVisualDistancePx)
    val latestOnPan by rememberUpdatedState(onPan)
    val latestOnTurnDrag by rememberUpdatedState(onTurnDrag)
    val latestOnTurnDragEnd by rememberUpdatedState(onTurnDragEnd)
    val latestOnTurnDragCancel by rememberUpdatedState(onTurnDragCancel)
    val latestOnCloseDrag by rememberUpdatedState(onCloseDrag)
    val latestOnCloseDragEnd by rememberUpdatedState(onCloseDragEnd)
    val latestOnCloseDragCancel by rememberUpdatedState(onCloseDragCancel)
    val latestOnLeftTap by rememberUpdatedState(onLeftTap)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)
    val latestOnRightTap by rememberUpdatedState(onRightTap)
    val latestOnLeftLongPress by rememberUpdatedState(onLeftLongPress)
    val latestOnRightLongPress by rememberUpdatedState(onRightLongPress)
    val latestOnCenterDoubleTap by rememberUpdatedState(onCenterDoubleTap)
    return pointerInput(Unit) {
        var totalDragX = 0f
        var gestureDragX = 0f
        var gestureDragY = 0f
        var dragMode = ReaderDragMode.Pending
        var closeDragOffsetY = 0f
        var activePanX = 0f
        var activePanY = 0f
        var dragStartedAtNegativePanEdge = false
        var dragStartedAtPositivePanEdge = false
        val panEdgeTolerancePx = 1f
        val horizontalIntentSlopPx = 8f
        val directionLockSlopPx = 4f
        val verticalCloseIntentSlopPx = 12f
        val tapMoveSlopPx = directionLockSlopPx
        val doubleTapSlopPx = 64f
        val longPressTimeoutMillis = 500L
        val doubleTapTimeoutMillis = 300L
        var pendingCenterTap: ReaderPendingCenterTap? = null

        fun closeMaxDragPx(): Float = latestCloseVisualDistancePx.coerceAtLeast(1f)

        fun closeCommitDistancePx(): Float = closeMaxDragPx() * 0.18f

        fun resetDragState() {
            totalDragX = 0f
            gestureDragX = 0f
            gestureDragY = 0f
            dragMode = ReaderDragMode.Pending
            closeDragOffsetY = 0f
            activePanX = latestPanOffsetX
            activePanY = latestPanOffsetY
            dragStartedAtNegativePanEdge = activePanX <= -latestPanMaxX + panEdgeTolerancePx
            dragStartedAtPositivePanEdge = activePanX >= latestPanMaxX - panEdgeTolerancePx
        }

        fun finishDrag() {
            when (dragMode) {
                ReaderDragMode.VerticalClose -> {
                    latestOnCloseDragEnd(closeDragOffsetY >= closeCommitDistancePx())
                }
                ReaderDragMode.HorizontalTurn,
                ReaderDragMode.ZoomEdgeTurn -> latestOnTurnDragEnd()
                ReaderDragMode.Pending -> Unit
            }
        }

        fun cancelDrag() {
            when (dragMode) {
                ReaderDragMode.VerticalClose -> latestOnCloseDragCancel()
                ReaderDragMode.HorizontalTurn,
                ReaderDragMode.ZoomEdgeTurn -> latestOnTurnDragCancel()
                ReaderDragMode.Pending -> Unit
            }
        }

        fun tapZone(position: Offset): ReaderTapZone {
            val thirdWidth = size.width / 3f
            return when {
                position.x < thirdWidth -> ReaderTapZone.Left
                position.x >= thirdWidth * 2f -> ReaderTapZone.Right
                else -> ReaderTapZone.Center
            }
        }

        fun flushPendingCenterTap() {
            if (pendingCenterTap != null) {
                pendingCenterTap = null
                latestOnCenterTap()
            }
        }

        fun dropPendingCenterTap() {
            pendingCenterTap = null
        }

        fun handleTap(position: Offset, uptimeMillis: Long) {
            when (tapZone(position)) {
                ReaderTapZone.Left -> {
                    flushPendingCenterTap()
                    latestOnLeftTap()
                }
                ReaderTapZone.Right -> {
                    flushPendingCenterTap()
                    latestOnRightTap()
                }
                ReaderTapZone.Center -> {
                    val pending = pendingCenterTap
                    if (
                        pending != null &&
                        uptimeMillis - pending.uptimeMillis <= doubleTapTimeoutMillis &&
                        (position - pending.position).getDistance() <= doubleTapSlopPx
                    ) {
                        pendingCenterTap = null
                        latestOnCenterDoubleTap(position)
                    } else {
                        pendingCenterTap = ReaderPendingCenterTap(position, uptimeMillis)
                    }
                }
            }
        }

        fun handleLongPress(position: Offset) {
            flushPendingCenterTap()
            when (tapZone(position)) {
                ReaderTapZone.Left -> latestOnLeftLongPress()
                ReaderTapZone.Right -> latestOnRightLongPress()
                ReaderTapZone.Center -> Unit
            }
        }

        fun handleDrag(change: PointerInputChange, dragAmount: Offset) {
            gestureDragX += dragAmount.x
            gestureDragY += dragAmount.y
            if (latestZoomPanEnabled) {
                if (dragMode == ReaderDragMode.ZoomEdgeTurn) {
                    totalDragX += dragAmount.x
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, latestRightToLeft),
                        readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                    )
                    change.consume()
                    return
                }

                val stillAtStartedNegativeEdge =
                    latestPanMaxX > 0f &&
                        dragStartedAtNegativePanEdge &&
                        activePanX <= -latestPanMaxX + panEdgeTolerancePx
                val stillAtStartedPositiveEdge =
                    latestPanMaxX > 0f &&
                        dragStartedAtPositivePanEdge &&
                        activePanX >= latestPanMaxX - panEdgeTolerancePx
                val draggingOutFromStartEdge =
                    (stillAtStartedNegativeEdge && dragAmount.x < 0f) ||
                        (stillAtStartedPositiveEdge && dragAmount.x > 0f)
                val horizontalIntent =
                    abs(gestureDragX) >= horizontalIntentSlopPx &&
                        abs(gestureDragX) > abs(gestureDragY) * 1.2f
                if (!draggingOutFromStartEdge || !horizontalIntent) {
                    activePanX = (activePanX + dragAmount.x).coerceIn(-latestPanMaxX, latestPanMaxX)
                    activePanY = (activePanY + dragAmount.y).coerceIn(-latestPanMaxY, latestPanMaxY)
                    latestOnPan(activePanX, activePanY)
                    change.consume()
                    return
                }

                totalDragX = gestureDragX
                dragMode = ReaderDragMode.ZoomEdgeTurn
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, latestRightToLeft),
                    readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                )
                change.consume()
                return
            }

            if (dragMode == ReaderDragMode.VerticalClose) {
                closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx())
                latestOnCloseDrag(closeDragOffsetY)
                change.consume()
                return
            }

            if (dragMode == ReaderDragMode.HorizontalTurn) {
                totalDragX += dragAmount.x
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, latestRightToLeft),
                    readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                )
                change.consume()
                return
            }

            val absGestureX = abs(gestureDragX)
            val hasDirectionIntent = hypot(gestureDragX, gestureDragY) >= directionLockSlopPx
            val downwardCloseIntent =
                latestCloseSwipeEnabled &&
                    gestureDragY >= verticalCloseIntentSlopPx &&
                    gestureDragY > absGestureX * 1.4f
            if (!hasDirectionIntent) {
                change.consume()
                return
            }

            when {
                downwardCloseIntent -> {
                    dragMode = ReaderDragMode.VerticalClose
                    closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx())
                    latestOnCloseDrag(closeDragOffsetY)
                }
                else -> {
                    totalDragX = gestureDragX
                    dragMode = ReaderDragMode.HorizontalTurn
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, latestRightToLeft),
                        readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                    )
                }
            }
            change.consume()
        }

        awaitEachGesture {
            val down = if (pendingCenterTap != null) {
                withTimeoutOrNull(doubleTapTimeoutMillis) {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                } ?: run {
                    flushPendingCenterTap()
                    return@awaitEachGesture
                }
            } else {
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
            }
            pendingCenterTap?.let { pending ->
                if ((down.position - pending.position).getDistance() > doubleTapSlopPx) {
                    flushPendingCenterTap()
                }
            }
            resetDragState()
            var previousPosition = down.position
            val pointerId = down.id
            var lastEventUptimeMillis = down.uptimeMillis
            var tapCandidate = true
            var longPressFired = false

            while (true) {
                val timeUntilLongPressMillis = longPressTimeoutMillis -
                    (lastEventUptimeMillis - down.uptimeMillis)
                val event = if (tapCandidate && !longPressFired && timeUntilLongPressMillis > 0L) {
                    withTimeoutOrNull(timeUntilLongPressMillis) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                } else {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
                if (event == null) {
                    if (tapCandidate && !longPressFired) {
                        handleLongPress(down.position)
                        longPressFired = true
                        tapCandidate = false
                        continue
                    } else {
                        finishDrag()
                        break
                    }
                }
                val pressedChanges = event.changes.filter { it.pressed }
                if (pressedChanges.size > 1) {
                    dropPendingCenterTap()
                    cancelDrag()
                    break
                }

                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    val release = event.changes.firstOrNull { it.id == pointerId }
                    if (tapCandidate && !longPressFired) {
                        handleTap(
                            position = release?.position ?: previousPosition,
                            uptimeMillis = release?.uptimeMillis ?: lastEventUptimeMillis
                        )
                    } else {
                        finishDrag()
                    }
                    break
                }
                lastEventUptimeMillis = change.uptimeMillis

                val dragAmount = change.position - previousPosition
                previousPosition = change.position
                if (longPressFired) {
                    change.consume()
                    continue
                }
                if (dragAmount != Offset.Zero) {
                    if (tapCandidate && (change.position - down.position).getDistance() >= tapMoveSlopPx) {
                        tapCandidate = false
                        dropPendingCenterTap()
                    }
                    if (tapCandidate) {
                        change.consume()
                    }
                    handleDrag(change, dragAmount)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReaderMenuOverlay(
    page: Int,
    pages: Int,
    rightToLeft: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onToggleDirection: () -> Unit,
    invertMode: InvertMode,
    onSetInvertMode: (InvertMode) -> Unit,
    onNextSingle: () -> Unit,
    onPreviousSingle: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    val safePageCount = pages.coerceAtLeast(1)
    val currentPage = page.coerceIn(0, safePageCount - 1)
    var jumpPage by remember(currentPage, safePageCount) { mutableIntStateOf(currentPage) }
    val sliderMax = (safePageCount - 1).toFloat()
    val sliderValue = if (rightToLeft) {
        safePageCount - 1 - jumpPage
    } else {
        jumpPage
    }.toFloat()
    fun sliderValueToPage(value: Float): Int {
        val sliderPage = value.roundToInt().coerceIn(0, safePageCount - 1)
        return if (rightToLeft) safePageCount - 1 - sliderPage else sliderPage
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0xCC000000))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text(
                text = "${page + 1} / $pages",
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onToggleDirection) {
                Text(if (rightToLeft) "Right-to-left" else "Left-to-right")
            }
            Button(onClick = onDismiss) { Text("Close") }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xCC000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invert", color = Color.White)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    val modes = InvertMode.entries
                    modes.forEachIndexed { index, mode ->
                        ToggleButton(
                            checked = invertMode == mode,
                            onCheckedChange = { onSetInvertMode(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            }
                        ) { Text(mode.name) }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (rightToLeft) "$safePageCount" else "1",
                    color = Color.White
                )
                ValueBubbleSlider(
                    value = sliderValue,
                    onValueChange = { value ->
                        jumpPage = sliderValueToPage(value)
                    },
                    onValueChangeFinished = {
                        onJumpToPage(jumpPage)
                    },
                    valueRange = 0f..sliderMax,
                    valueLabel = { value -> "${sliderValueToPage(value) + 1}" },
                    enabled = safePageCount > 1,
                    reverseTrackColors = rightToLeft,
                    showStopIndicator = false,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (rightToLeft) "1" else "$safePageCount",
                    color = Color.White
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (rightToLeft) {
                    Button(
                        onClick = onNextSingle,
                        modifier = Modifier.weight(1f)
                    ) { Text("Shift +1") }
                    Button(
                        onClick = onPreviousSingle,
                        modifier = Modifier.weight(1f)
                    ) { Text("Shift -1") }
                } else {
                    Button(
                        onClick = onPreviousSingle,
                        modifier = Modifier.weight(1f)
                    ) { Text("Shift -1") }
                    Button(
                        onClick = onNextSingle,
                        modifier = Modifier.weight(1f)
                    ) { Text("Shift +1") }
                }
            }
        }
    }
}

@Composable
private fun ReaderFullscreenEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        if (activity == null) {
            onDispose {}
        } else {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                WindowInsetsControllerCompat(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
