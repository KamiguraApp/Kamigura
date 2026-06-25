package li.mof.kamigura.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
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
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.download.decodeOfflinePage
import li.mof.kamigura.ui.ValueBubbleSlider
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Smart invert: only invert "text" pages (mostly white, low color) and leave
// illustration / color pages untouched.
private const val SmartInvertSampleSize = 64
private const val SmartInvertColorThreshold = 0.1f
private const val SmartInvertWhiteChannelMin = 217
private const val SmartInvertSaturationMin = 0.2f
private const val SmartInvertColorValueMin = 40

private val NegativeColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

// Kavita reading-profile reading direction values (ReadingDirection enum).
private const val KavitaReadingDirectionLtr = 0
private const val KavitaReadingDirectionRtl = 1

// ReadingProfileKind.Default = the user's global default (no per-series direction).
private const val KavitaReadingProfileKindDefault = 0

private const val ReaderDoubleTapUserZoomScale = 2f
private const val ReaderMaxZoomScale = 5f
private const val ReaderZoomEpsilon = 0.01f
private const val ReaderPrefetchConcurrency = 2

private data class ReaderSpreadPages(
    val leftPage: Int,
    val rightPage: Int
)

private data class ReaderPageLayout(
    val singlePage: Boolean,
    val nextStep: Int,
    val previousStep: Int,
    val singleAlignment: Alignment
)

private data class ReaderZoomPanState(
    val userScale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

private data class ReaderPanBounds(
    val maxX: Float,
    val maxY: Float
)

private fun Map<Int, FileDimensionDto>.pageIsWide(page: Int): Boolean {
    return page >= 0 && this[page]?.isWidePage() == true
}

private fun List<FileDimensionDto>.toPageDimensionMap(): Map<Int, FileDimensionDto> {
    return mapNotNull { dimension ->
        val page = dimension.pageNumber ?: return@mapNotNull null
        if (page < 0) null else page to dimension
    }.toMap()
}

private fun FileDimensionDto.isWidePage(): Boolean {
    return isWide
}

private fun spreadPagesFor(page: Int, rightToLeft: Boolean): ReaderSpreadPages {
    val firstPage = page
    val secondPage = page + 1
    return if (rightToLeft) {
        ReaderSpreadPages(leftPage = secondPage, rightPage = firstPage)
    } else {
        ReaderSpreadPages(leftPage = firstPage, rightPage = secondPage)
    }
}

private fun readerPageLayout(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>
): ReaderPageLayout {
    if (portrait) {
        return ReaderPageLayout(
            singlePage = true,
            nextStep = 1,
            previousStep = 1,
            singleAlignment = Alignment.Center
        )
    }

    val currentPageIsWide = pageDimensions.pageIsWide(page)
    val pairedPageIsWide = pageDimensions.pageIsWide(page + 1)
    // The cover (page 0) is always shown alone, matching Kavita's pairing, so the
    // first spread starts at page 1. Foldout/wide misalignment beyond this is still
    // corrected manually with the Shift +1/-1 buttons.
    val isCover = page == 0
    val singlePage = isCover || currentPageIsWide || pairedPageIsWide || page + 1 >= pageCount

    return ReaderPageLayout(
        singlePage = singlePage,
        nextStep = if (singlePage) 1 else 2,
        previousStep = if (currentPageIsWide || pageDimensions.pageIsWide(page - 1) || page - 1 == 0) 1 else 2,
        singleAlignment = Alignment.Center
    )
}

internal fun readerPrefetchPageIndices(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    turns: Int
): List<Int> {
    if (pageCount <= 0 || page !in 0 until pageCount || turns <= 0) return emptyList()
    val result = LinkedHashSet<Int>()
    var cursor = page
    var remainingTurns = turns
    while (remainingTurns > 0) {
        val currentLayout = readerPageLayout(cursor, pageCount, portrait, pageDimensions)
        val next = cursor + currentLayout.nextStep
        if (next !in 0 until pageCount) break
        val nextLayout = readerPageLayout(next, pageCount, portrait, pageDimensions)
        result += next
        if (!nextLayout.singlePage && next + 1 < pageCount) result += next + 1
        cursor = next
        remainingTurns--
    }
    return result.toList()
}

private fun readerPanBoundsPx(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    zoomScale: Float
): ReaderPanBounds {
    val overflowScale = (zoomScale - 1f).coerceAtLeast(0f)
    return ReaderPanBounds(
        maxX = viewportWidthPx * overflowScale / 2f,
        maxY = viewportHeightPx * overflowScale / 2f
    )
}

private fun ReaderZoomPanState.withTransform(
    zoomChange: Float,
    panChange: Offset,
    baseZoomScale: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float
): ReaderZoomPanState {
    val maxUserScale = (ReaderMaxZoomScale / baseZoomScale).coerceAtLeast(1f)
    val nextUserScale = (userScale * zoomChange).coerceIn(1f, maxUserScale)
    val nextTotalScale = baseZoomScale * nextUserScale
    val bounds = readerPanBoundsPx(viewportWidthPx, viewportHeightPx, nextTotalScale)
    val keepOffset = nextTotalScale > 1f + ReaderZoomEpsilon
    return ReaderZoomPanState(
        userScale = nextUserScale,
        offsetX = if (keepOffset) (offsetX + panChange.x).coerceIn(-bounds.maxX, bounds.maxX) else 0f,
        offsetY = if (keepOffset) (offsetY + panChange.y).coerceIn(-bounds.maxY, bounds.maxY) else 0f
    )
}

private fun ReaderZoomPanState.withDoubleTapZoom(
    tapPosition: Offset,
    baseZoomScale: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    initialState: ReaderZoomPanState
): ReaderZoomPanState {
    if (userScale > 1f + ReaderZoomEpsilon) return initialState

    val maxUserScale = (ReaderMaxZoomScale / baseZoomScale).coerceAtLeast(1f)
    val nextUserScale = ReaderDoubleTapUserZoomScale.coerceIn(1f, maxUserScale)
    if (nextUserScale <= 1f + ReaderZoomEpsilon) return initialState

    val nextTotalScale = baseZoomScale * nextUserScale
    val bounds = readerPanBoundsPx(viewportWidthPx, viewportHeightPx, nextTotalScale)
    val center = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f)
    val tapFromCenter = tapPosition - center
    return ReaderZoomPanState(
        userScale = nextUserScale,
        offsetX = (initialState.offsetX * nextUserScale + tapFromCenter.x * (1f - nextUserScale))
            .coerceIn(-bounds.maxX, bounds.maxX),
        offsetY = (initialState.offsetY * nextUserScale + tapFromCenter.y * (1f - nextUserScale))
            .coerceIn(-bounds.maxY, bounds.maxY)
    )
}

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
    val fallbackImageLoader = LocalImageLoader.current
    val scope = rememberCoroutineScope()
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
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }

    DisposableEffect(readerImageLoader) {
        val activeLoader = readerImageLoader
        onDispose { activeLoader?.shutdown() }
    }

    fun clampPage(value: Int): Int = value.coerceIn(0, (pages - 1).coerceAtLeast(0))
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
            } else if (!local.record.progressPending) {
                val savedPage = runCatching { loadedApi.getProgress(chapterId).pageNum }.getOrNull()
                if (savedPage != null) page = savedPage.coerceIn(0, pages - 1)
            }
            readerReady = true
        } catch (t: Throwable) {
            if (local == null) {
                error = t.message ?: t.toString()
            }
        }
    }

    LaunchedEffect(api, readerReady, pages, page, incognito) {
        if (!readerReady) return@LaunchedEffect
        if (incognito) return@LaunchedEffect
        if (pages <= 0 || page !in 0 until pages) return@LaunchedEffect
        val loadedSession = session ?: return@LaunchedEffect
        if (offlineChapter != null) {
            offlineRepository.saveLocalProgress(loadedSession, chapterId, page)
        }
        val loadedApi = api ?: return@LaunchedEffect
        try {
            loadedApi.saveProgress(
                ProgressDto(
                    libraryId = libraryId,
                    seriesId = seriesId,
                    volumeId = volumeId,
                    chapterId = chapterId,
                    pageNum = page
                )
            )
            if (offlineChapter != null) {
                offlineRepository.markProgressSynced(
                    session = loadedSession,
                    chapterId = chapterId,
                    expectedPage = page
                )
            }
        } catch (t: Throwable) {
            if (offlineChapter == null) {
                error = "Progress save failed: ${t.message ?: t.toString()}"
            }
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
        val layout = readerPageLayout(
            page = page,
            pageCount = pages,
            portrait = portrait,
            pageDimensions = pageDimensions
        )
        LaunchedEffect(
            page,
            pages,
            portrait,
            pageDimensions,
            offlineChapter,
            activeImageLoader,
            s.baseUrl,
            s.apiKey,
            settings.reader.prefetchTurns
        ) {
            if (offlineChapter != null || pages <= 0) return@LaunchedEffect
            val indices = readerPrefetchPageIndices(
                page = page,
                pageCount = pages,
                portrait = portrait,
                pageDimensions = pageDimensions,
                turns = settings.reader.prefetchTurns
            )
            prefetchReaderPages(
                context = ctx,
                imageLoader = activeImageLoader,
                models = indices.mapNotNull { index -> pageModel(index) as? String },
                targetWidth = viewportWidthPx.toInt().coerceAtLeast(1),
                targetHeight = viewportHeightPx.toInt().coerceAtLeast(1)
            )
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
            zoomPan = initialZoomPanState
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
                val remaining = abs(targetProgress - transitionProgress)
                val duration = (90 + 170 * remaining).roundToInt().coerceIn(90, 260)
                Animatable(transitionProgress).animateTo(
                    targetValue = targetProgress,
                    animationSpec = tween(
                        durationMillis = duration,
                        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
                    )
                ) {
                    transitionProgress = value
                }
                if (commit) page = transition.targetPage
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

        if (error != null) {
            Text("Error: $error", color = Color.Red, modifier = Modifier.padding(12.dp))
        }

        val transition = activeTransition
        val transitionVisible =
            transition != null && settings.reader.pageTransitionAnimation && !zoomPanEnabled
        val transitionUsesAtomicSpread = transition?.let {
            !portrait && !readerPageLayout(
                page = it.outgoingPage,
                pageCount = pages,
                portrait = false,
                pageDimensions = pageDimensions
            ).singlePage
        } == true
        if (transitionVisible) {
            requireNotNull(transition)
            val progress = transitionProgress.coerceIn(0f, 1f)
            val physicalSign = readerTurnPhysicalSign(rtl, transition.direction)
            val travelFraction = if (transitionUsesAtomicSpread) 0.06f else 1f
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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX =
                            -physicalSign * viewportWidthPx * travelFraction * (1f - progress)
                        scaleX = 0.99f + 0.01f * progress
                        scaleY = 0.99f + 0.01f * progress
                        alpha = if (transitionUsesAtomicSpread) 1f else 0.94f + 0.06f * progress
                    }
            )
        }

        val currentModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (transitionVisible) {
                    requireNotNull(transition)
                    val progress = transitionProgress.coerceIn(0f, 1f)
                    val physicalSign = readerTurnPhysicalSign(rtl, transition.direction)
                    val shadowEnvelope = 4f * progress * (1f - progress)
                    val travelFraction = if (transitionUsesAtomicSpread) 0.06f else 1f
                    translationX = physicalSign * viewportWidthPx * travelFraction * progress
                    scaleX = 1f - 0.008f * progress
                    scaleY = 1f - 0.008f * progress
                    alpha = if (transitionUsesAtomicSpread) {
                        ((1f - progress) / 0.18f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    shadowElevation = 12.dp.toPx() * shadowEnvelope
                    shape = RectangleShape
                    clip = false
                } else {
                    translationX = zoomPan.offsetX
                    translationY = zoomPan.offsetY
                    scaleX = totalZoomScale
                    scaleY = totalZoomScale
                }
            }
        ReaderPageView(
            cursor = transition?.outgoingPage ?: page,
            pageCount = pages,
            portrait = portrait,
            pageDimensions = pageDimensions,
            rightToLeft = rtl,
            pageModel = ::pageModel,
            imageLoader = activeImageLoader,
            invertMode = settings.reader.invertMode,
            whiteThreshold = settings.reader.invertWhiteThreshold,
            modifier = currentModifier
        )

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
                turnViewportWidthPx = viewportWidthPx,
                zoomPanEnabled = zoomPanEnabled,
                panOffsetX = zoomPan.offsetX,
                panOffsetY = zoomPan.offsetY,
                panMaxX = panBounds.maxX,
                panMaxY = panBounds.maxY,
                onPan = { x, y -> zoomPan = zoomPan.copy(offsetX = x, offsetY = y) },
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
                onDoubleTap = {
                    zoomPan = zoomPan.withDoubleTapZoom(
                        tapPosition = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f),
                        baseZoomScale = baseZoomScale,
                        viewportWidthPx = viewportWidthPx,
                        viewportHeightPx = viewportHeightPx,
                        initialState = initialZoomPanState
                    )
                },
                onTransform = { zoomChange, panChange ->
                    zoomPan = zoomPan.withTransform(
                        zoomChange = zoomChange,
                        panChange = panChange,
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
private fun ReaderPageView(
    cursor: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    rightToLeft: Boolean,
    pageModel: (Int) -> Any?,
    imageLoader: ImageLoader,
    invertMode: InvertMode,
    whiteThreshold: Float,
    modifier: Modifier = Modifier
) {
    val layout = readerPageLayout(
        page = cursor,
        pageCount = pageCount,
        portrait = portrait,
        pageDimensions = pageDimensions
    )
    val spread = spreadPagesFor(cursor, rightToLeft)
    Row(modifier) {
        if (layout.singlePage) {
            key(cursor) {
                PageImage(
                    model = pageModel(cursor),
                    imageLoader = imageLoader,
                    label = "Page $cursor",
                    alignment = layout.singleAlignment,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold
                )
            }
        } else {
            key(spread.leftPage) {
                PageImage(
                    model = pageModel(spread.leftPage),
                    imageLoader = imageLoader,
                    label = "Left ${spread.leftPage}",
                    alignment = Alignment.CenterEnd,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold
                )
            }
            key(spread.rightPage) {
                PageImage(
                    model = pageModel(spread.rightPage),
                    imageLoader = imageLoader,
                    label = "Right ${spread.rightPage}",
                    alignment = Alignment.CenterStart,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold
                )
            }
        }
    }
}

@Composable
private fun RowScope.PageImage(
    model: Any?,
    imageLoader: ImageLoader,
    label: String,
    alignment: Alignment,
    contentScale: ContentScale = ContentScale.Fit,
    invertMode: InvertMode = InvertMode.Off,
    whiteThreshold: Float = 0.5f
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(Color(0xFF111111))
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val targetWidth = with(density) { maxWidth.toPx().toInt() }
        val targetHeight = with(density) { maxHeight.toPx().toInt() }
        val resolvedModel by produceState<Any?>(
            initialValue = model.takeUnless { it is OfflinePage },
            model,
            targetWidth,
            targetHeight
        ) {
            value = when (model) {
                is OfflinePage -> decodeOfflinePage(model, targetWidth, targetHeight)
                else -> model
            }
        }
        val shouldInvert by produceState(
            initialValue = false,
            resolvedModel,
            invertMode,
            whiteThreshold,
            imageLoader
        ) {
            value = when (invertMode) {
                InvertMode.Off -> false
                InvertMode.Always -> true
                InvertMode.Smart -> resolvedModel != null &&
                    analyzeShouldInvert(ctx, imageLoader, resolvedModel!!, whiteThreshold)
            }
        }

        if (resolvedModel == null) {
            Text("-", color = Color.Gray)
        } else {
            AsyncImage(
                model = resolvedModel,
                imageLoader = imageLoader,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                alignment = alignment,
                contentScale = contentScale,
                colorFilter = if (shouldInvert) NegativeColorFilter else null
            )
        }
    }
}

private suspend fun prefetchReaderPages(
    context: Context,
    imageLoader: ImageLoader,
    models: List<String>,
    targetWidth: Int,
    targetHeight: Int
) = coroutineScope {
    val semaphore = Semaphore(ReaderPrefetchConcurrency)
    models.mapIndexed { index, model ->
        launch {
            semaphore.withPermit {
                val request = ImageRequest.Builder(context)
                    .data(model)
                    .size(targetWidth, targetHeight)
                    .memoryCachePolicy(
                        if (index == 0) CachePolicy.ENABLED else CachePolicy.DISABLED
                    )
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build()
                try {
                    imageLoader.execute(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Prefetch is opportunistic; the visible page reports its own error.
                }
            }
        }
    }.forEach { it.join() }
}

/**
 * Loads a small downscaled copy of [model] and decides whether the page looks like
 * a text page (mostly white, low color) that should be inverted for night reading.
 * Illustration / color pages return false so they are shown normally.
 */
private suspend fun analyzeShouldInvert(
    ctx: Context,
    imageLoader: coil.ImageLoader,
    model: Any,
    whiteThreshold: Float
): Boolean {
    val request = ImageRequest.Builder(ctx)
        .data(model)
        .size(SmartInvertSampleSize)
        .allowHardware(false)
        .build()
    val bitmap = (imageLoader.execute(request) as? SuccessResult)
        ?.drawable
        ?.toBitmap()
        ?: return false

    val width = bitmap.width
    val height = bitmap.height
    val total = width * height
    if (total <= 0) return false

    val pixels = IntArray(total)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var whiteCount = 0
    var colorCount = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mn >= SmartInvertWhiteChannelMin) whiteCount++
        val saturation = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        if (mx >= SmartInvertColorValueMin && saturation >= SmartInvertSaturationMin) colorCount++
    }

    val whiteRatio = whiteCount.toFloat() / total
    val colorRatio = colorCount.toFloat() / total
    return whiteRatio >= whiteThreshold && colorRatio <= SmartInvertColorThreshold
}

@Composable
private fun ReaderTapLayer(
    rightToLeft: Boolean,
    onNextSpread: () -> Unit,
    onPreviousSpread: () -> Unit,
    onNextSingle: () -> Unit,
    onPreviousSingle: () -> Unit,
    onCenterTap: () -> Unit,
    turnViewportWidthPx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit = { _, _ -> },
    onTurnDragEnd: () -> Unit = {},
    onTurnDragCancel: () -> Unit = {},
    onDoubleTap: (Offset) -> Unit = {},
    onTransform: (Float, Offset) -> Unit = { _, _ -> }
) {
    val latestOnNextSpread by rememberUpdatedState(onNextSpread)
    val latestOnPreviousSpread by rememberUpdatedState(onPreviousSpread)
    val latestOnNextSingle by rememberUpdatedState(onNextSingle)
    val latestOnPreviousSingle by rememberUpdatedState(onPreviousSingle)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .readerPinchZoom(onTransform = onTransform)
                .readerDrag(
                    rightToLeft = rightToLeft,
                    turnViewportWidthPx = turnViewportWidthPx,
                    zoomPanEnabled = zoomPanEnabled,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY,
                    panMaxX = panMaxX,
                    panMaxY = panMaxY,
                    onPan = onPan,
                    onTurnDrag = onTurnDrag,
                    onTurnDragEnd = onTurnDragEnd,
                    onTurnDragCancel = onTurnDragCancel
                )
                .pointerInput(rightToLeft, zoomPanEnabled) {
                    detectTapGestures(
                        onTap = {
                            if (zoomPanEnabled) return@detectTapGestures
                            if (rightToLeft) latestOnNextSpread() else latestOnPreviousSpread()
                        },
                        onLongPress = {
                            if (zoomPanEnabled) return@detectTapGestures
                            if (rightToLeft) latestOnNextSingle() else latestOnPreviousSingle()
                        }
                    )
                }
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .readerPinchZoom(onTransform = onTransform)
                .readerDrag(
                    rightToLeft = rightToLeft,
                    turnViewportWidthPx = turnViewportWidthPx,
                    zoomPanEnabled = zoomPanEnabled,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY,
                    panMaxX = panMaxX,
                    panMaxY = panMaxY,
                    onPan = onPan,
                    onTurnDrag = onTurnDrag,
                    onTurnDragEnd = onTurnDragEnd,
                    onTurnDragCancel = onTurnDragCancel
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { latestOnDoubleTap(it) },
                        onTap = { latestOnCenterTap() }
                    )
                }
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .readerPinchZoom(onTransform = onTransform)
                .readerDrag(
                    rightToLeft = rightToLeft,
                    turnViewportWidthPx = turnViewportWidthPx,
                    zoomPanEnabled = zoomPanEnabled,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY,
                    panMaxX = panMaxX,
                    panMaxY = panMaxY,
                    onPan = onPan,
                    onTurnDrag = onTurnDrag,
                    onTurnDragEnd = onTurnDragEnd,
                    onTurnDragCancel = onTurnDragCancel
                )
                .pointerInput(rightToLeft, zoomPanEnabled) {
                    detectTapGestures(
                        onTap = {
                            if (zoomPanEnabled) return@detectTapGestures
                            if (rightToLeft) latestOnPreviousSpread() else latestOnNextSpread()
                        },
                        onLongPress = {
                            if (zoomPanEnabled) return@detectTapGestures
                            if (rightToLeft) latestOnPreviousSingle() else latestOnNextSingle()
                        }
                    )
                }
        )
    }
}

@Composable
private fun Modifier.readerPinchZoom(
    onTransform: (Float, Offset) -> Unit
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
                    latestOnTransform(span / lastSpan, centroid - previousCentroid)
                    pressedChanges.forEach { it.consume() }
                }

                lastCentroid = centroid
                lastSpan = span
            }
        }
    }
}

@Composable
private fun Modifier.readerDrag(
    rightToLeft: Boolean,
    turnViewportWidthPx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit,
    onTurnDragEnd: () -> Unit,
    onTurnDragCancel: () -> Unit
): Modifier {
    val latestPanOffsetX by rememberUpdatedState(panOffsetX)
    val latestPanOffsetY by rememberUpdatedState(panOffsetY)
    val latestOnTurnDrag by rememberUpdatedState(onTurnDrag)
    val latestOnTurnDragEnd by rememberUpdatedState(onTurnDragEnd)
    val latestOnTurnDragCancel by rememberUpdatedState(onTurnDragCancel)
    return pointerInput(rightToLeft, zoomPanEnabled, panMaxX, panMaxY) {
        var totalDragX = 0f
        var turnDragActive = false
        var activePanX = 0f
        var activePanY = 0f
        var dragStartedAtNegativePanEdge = false
        var dragStartedAtPositivePanEdge = false
        val panEdgeTolerancePx = 1f

        detectDragGestures(
            onDragStart = {
                totalDragX = 0f
                turnDragActive = false
                activePanX = latestPanOffsetX
                activePanY = latestPanOffsetY
                dragStartedAtNegativePanEdge = activePanX <= -panMaxX + panEdgeTolerancePx
                dragStartedAtPositivePanEdge = activePanX >= panMaxX - panEdgeTolerancePx
            },
            onDrag = { change, dragAmount ->
                if (zoomPanEnabled) {
                    val stillAtStartedNegativeEdge =
                        panMaxX > 0f &&
                            dragStartedAtNegativePanEdge &&
                            activePanX <= -panMaxX + panEdgeTolerancePx
                    val stillAtStartedPositiveEdge =
                        panMaxX > 0f &&
                            dragStartedAtPositivePanEdge &&
                            activePanX >= panMaxX - panEdgeTolerancePx
                    val draggingOutFromStartEdge =
                        (stillAtStartedNegativeEdge && dragAmount.x < 0f) ||
                            (stillAtStartedPositiveEdge && dragAmount.x > 0f)
                    if (!draggingOutFromStartEdge) {
                        activePanX = (activePanX + dragAmount.x).coerceIn(-panMaxX, panMaxX)
                        activePanY = (activePanY + dragAmount.y).coerceIn(-panMaxY, panMaxY)
                        onPan(activePanX, activePanY)
                        change.consume()
                        return@detectDragGestures
                    }

                    totalDragX += dragAmount.x
                    turnDragActive = true
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, rightToLeft),
                        readerTurnProgress(totalDragX, turnViewportWidthPx)
                    )
                    change.consume()
                    return@detectDragGestures
                }

                totalDragX += dragAmount.x
                turnDragActive = true
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, rightToLeft),
                    readerTurnProgress(totalDragX, turnViewportWidthPx)
                )
                change.consume()
            },
            onDragEnd = {
                if (turnDragActive) latestOnTurnDragEnd()
            },
            onDragCancel = {
                if (turnDragActive) latestOnTurnDragCancel()
            }
        )
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
