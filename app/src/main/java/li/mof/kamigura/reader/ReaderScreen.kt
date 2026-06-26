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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerInputChange
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
import coil.imageLoader
import coil.compose.AsyncImage
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
import kotlinx.coroutines.Job
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

private fun ReaderZoomPanState.lerpTo(
    target: ReaderZoomPanState,
    fraction: Float
): ReaderZoomPanState {
    val t = fraction.coerceIn(0f, 1f)
    return ReaderZoomPanState(
        userScale = userScale + (target.userScale - userScale) * t,
        offsetX = offsetX + (target.offsetX - offsetX) * t,
        offsetY = offsetY + (target.offsetY - offsetY) * t
    )
}

private data class ReaderPanBounds(
    val maxX: Float,
    val maxY: Float
)

private data class ReaderInvertCacheKey(
    val model: Any,
    val whiteThreshold: Float
)

private data class ReaderPrefetchTarget(
    val model: String,
    val targetWidth: Int,
    val targetHeight: Int
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

private fun readerVisiblePageIndices(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>
): List<Int> {
    if (page !in 0 until pageCount) return emptyList()
    val layout = readerPageLayout(page, pageCount, portrait, pageDimensions)
    if (layout.singlePage) return listOf(page)
    return listOf(page, page + 1).filter { it in 0 until pageCount }
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
        result += readerVisiblePageIndices(next, pageCount, portrait, pageDimensions)
        cursor = next
        remainingTurns--
    }
    return result.toList()
}

private fun readerPrefetchPageIndicesAround(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    turns: Int
): List<Int> {
    if (pageCount <= 0 || page !in 0 until pageCount || turns <= 0) return emptyList()
    val result = LinkedHashSet<Int>()
    val layout = readerPageLayout(page, pageCount, portrait, pageDimensions)
    result += readerPrefetchPageIndices(page, pageCount, portrait, pageDimensions, turns)
    if (page > 0) {
        val previous = (page - layout.previousStep).coerceAtLeast(0)
        result += readerVisiblePageIndices(previous, pageCount, portrait, pageDimensions)
    }
    return result.toList()
}

private fun readerPrefetchSlotWidthPx(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    viewportWidthPx: Float
): Int {
    val layout = readerPageLayout(page, pageCount, portrait, pageDimensions)
    val width = if (layout.singlePage) viewportWidthPx else viewportWidthPx / 2f
    return width.roundToInt().coerceAtLeast(1)
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
    focalPoint: Offset,
    baseZoomScale: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float
): ReaderZoomPanState {
    val maxUserScale = (ReaderMaxZoomScale / baseZoomScale).coerceAtLeast(1f)
    val oldTotalScale = baseZoomScale * userScale
    val nextUserScale = (userScale * zoomChange).coerceIn(1f, maxUserScale)
    val nextTotalScale = baseZoomScale * nextUserScale
    val bounds = readerPanBoundsPx(viewportWidthPx, viewportHeightPx, nextTotalScale)
    val keepOffset = nextTotalScale > 1f + ReaderZoomEpsilon
    val center = Offset(viewportWidthPx / 2f, viewportHeightPx / 2f)
    val focalFromCenter = focalPoint - center
    val previousFocalFromCenter = focalFromCenter - panChange
    val scaleChange = if (oldTotalScale > 0f) nextTotalScale / oldTotalScale else 1f
    val nextOffsetX = focalFromCenter.x - (previousFocalFromCenter.x - offsetX) * scaleChange
    val nextOffsetY = focalFromCenter.y - (previousFocalFromCenter.y - offsetY) * scaleChange
    return ReaderZoomPanState(
        userScale = nextUserScale,
        offsetX = if (keepOffset) nextOffsetX.coerceIn(-bounds.maxX, bounds.maxX) else 0f,
        offsetY = if (keepOffset) nextOffsetY.coerceIn(-bounds.maxY, bounds.maxY) else 0f
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
    val fallbackImageLoader = ctx.imageLoader
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
    var zoomAnimationJob by remember { mutableStateOf<Job?>(null) }
    var closeDragOffsetY by remember { mutableFloatStateOf(0f) }
    var closeAnimationJob by remember { mutableStateOf<Job?>(null) }
    val invertDecisionCache = remember { mutableStateMapOf<ReaderInvertCacheKey, Boolean>() }
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
                val shadowEnvelope = 4f * progress * (1f - progress)
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
                            shadowElevation = 6.dp.toPx() * shadowEnvelope
                            shape = RectangleShape
                            clip = false
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
                            shadowElevation = 10.dp.toPx() * shadowEnvelope
                            shape = RectangleShape
                            clip = false
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
                zoneWidthPx = viewportWidthPx / 3f,
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
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>,
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
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache
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
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache
                )
            }
            key(spread.rightPage) {
                PageImage(
                    model = pageModel(spread.rightPage),
                    imageLoader = imageLoader,
                    label = "Right ${spread.rightPage}",
                    alignment = Alignment.CenterStart,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache
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
    whiteThreshold: Float = 0.5f,
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>
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
        val cacheKey = resolvedModel?.let { ReaderInvertCacheKey(it, whiteThreshold) }
        val shouldInvert by produceState<Boolean?>(
            initialValue = when (invertMode) {
                InvertMode.Off -> false
                InvertMode.Always -> true
                InvertMode.Smart -> cacheKey?.let(invertDecisionCache::get)
            },
            resolvedModel,
            invertMode,
            whiteThreshold,
            imageLoader
        ) {
            value = when (invertMode) {
                InvertMode.Off -> false
                InvertMode.Always -> true
                InvertMode.Smart -> {
                    val loadedModel = resolvedModel
                    if (loadedModel == null) {
                        null
                    } else {
                        val key = ReaderInvertCacheKey(loadedModel, whiteThreshold)
                        invertDecisionCache[key] ?: analyzeShouldInvert(
                            ctx,
                            imageLoader,
                            loadedModel,
                            whiteThreshold
                        ).also { invertDecisionCache[key] = it }
                    }
                }
            }
        }

        if (resolvedModel == null) {
            Text("-", color = Color.Gray)
        } else if (shouldInvert != null) {
            AsyncImage(
                model = resolvedModel,
                imageLoader = imageLoader,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                alignment = alignment,
                contentScale = contentScale,
                colorFilter = if (shouldInvert == true) NegativeColorFilter else null
            )
        }
    }
}

private suspend fun prefetchReaderPages(
    context: Context,
    imageLoader: ImageLoader,
    targets: List<ReaderPrefetchTarget>
) = coroutineScope {
    val semaphore = Semaphore(ReaderPrefetchConcurrency)
    targets.mapIndexed { index, target ->
        launch {
            semaphore.withPermit {
                val request = ImageRequest.Builder(context)
                    .data(target.model)
                    .size(target.targetWidth, target.targetHeight)
                    .memoryCachePolicy(
                        if (index < 4) CachePolicy.ENABLED else CachePolicy.DISABLED
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

private suspend fun preAnalyzeReaderPages(
    context: Context,
    imageLoader: ImageLoader,
    models: List<String>,
    whiteThreshold: Float,
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>
) = coroutineScope {
    val semaphore = Semaphore(ReaderPrefetchConcurrency)
    models.map { model ->
        launch {
            semaphore.withPermit {
                val key = ReaderInvertCacheKey(model, whiteThreshold)
                if (key !in invertDecisionCache) {
                    try {
                        invertDecisionCache[key] = analyzeShouldInvert(
                            context,
                            imageLoader,
                            model,
                            whiteThreshold
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Visible rendering retries the analysis if pre-analysis fails.
                    }
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
    turnVisualDistancePx: Float,
    zoneWidthPx: Float,
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

    Row(
        Modifier
            .fillMaxSize()
            .readerPinchZoom(onTransform = onTransform)
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .readerDrag(
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
                    onCloseDragCancel = onCloseDragCancel
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
                .readerDrag(
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
                    onCloseDragCancel = onCloseDragCancel
                )
                .pointerInput(zoneWidthPx) {
                    detectTapGestures(
                        onDoubleTap = { latestOnDoubleTap(Offset(it.x + zoneWidthPx, it.y)) },
                        onTap = { latestOnCenterTap() }
                    )
                }
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .readerDrag(
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
                    onCloseDragCancel = onCloseDragCancel
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
private fun Modifier.readerDrag(
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
    onCloseDragCancel: () -> Unit = {}
): Modifier {
    val latestPanOffsetX by rememberUpdatedState(panOffsetX)
    val latestPanOffsetY by rememberUpdatedState(panOffsetY)
    val latestOnTurnDrag by rememberUpdatedState(onTurnDrag)
    val latestOnTurnDragEnd by rememberUpdatedState(onTurnDragEnd)
    val latestOnTurnDragCancel by rememberUpdatedState(onTurnDragCancel)
    val latestOnCloseDrag by rememberUpdatedState(onCloseDrag)
    val latestOnCloseDragEnd by rememberUpdatedState(onCloseDragEnd)
    val latestOnCloseDragCancel by rememberUpdatedState(onCloseDragCancel)
    return pointerInput(rightToLeft, zoomPanEnabled, panMaxX, panMaxY, closeSwipeEnabled, closeVisualDistancePx) {
        var totalDragX = 0f
        var gestureDragX = 0f
        var gestureDragY = 0f
        var turnDragActive = false
        var closeDragActive = false
        var closeDragOffsetY = 0f
        var activePanX = 0f
        var activePanY = 0f
        var dragStartedAtNegativePanEdge = false
        var dragStartedAtPositivePanEdge = false
        val panEdgeTolerancePx = 1f
        val horizontalIntentSlopPx = 8f
        val turnIntentSlopPx = 4f
        val verticalCloseIntentSlopPx = 12f
        val closeMaxDragPx = closeVisualDistancePx.coerceAtLeast(1f)
        val closeCommitDistancePx = closeMaxDragPx * 0.18f

        fun resetDragState() {
            totalDragX = 0f
            gestureDragX = 0f
            gestureDragY = 0f
            turnDragActive = false
            closeDragActive = false
            closeDragOffsetY = 0f
            activePanX = latestPanOffsetX
            activePanY = latestPanOffsetY
            dragStartedAtNegativePanEdge = activePanX <= -panMaxX + panEdgeTolerancePx
            dragStartedAtPositivePanEdge = activePanX >= panMaxX - panEdgeTolerancePx
        }

        fun finishDrag() {
            when {
                closeDragActive -> latestOnCloseDragEnd(closeDragOffsetY >= closeCommitDistancePx)
                turnDragActive -> latestOnTurnDragEnd()
            }
        }

        fun cancelDrag() {
            when {
                closeDragActive -> latestOnCloseDragCancel()
                turnDragActive -> latestOnTurnDragCancel()
            }
        }

        fun handleDrag(change: PointerInputChange, dragAmount: Offset) {
            gestureDragX += dragAmount.x
            gestureDragY += dragAmount.y
            if (zoomPanEnabled) {
                if (turnDragActive) {
                    totalDragX += dragAmount.x
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, rightToLeft),
                        readerTurnProgress(totalDragX, turnVisualDistancePx)
                    )
                    change.consume()
                    return
                }

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
                val horizontalIntent =
                    abs(gestureDragX) >= horizontalIntentSlopPx &&
                        abs(gestureDragX) > abs(gestureDragY) * 1.2f
                if (!draggingOutFromStartEdge || !horizontalIntent) {
                    activePanX = (activePanX + dragAmount.x).coerceIn(-panMaxX, panMaxX)
                    activePanY = (activePanY + dragAmount.y).coerceIn(-panMaxY, panMaxY)
                    onPan(activePanX, activePanY)
                    change.consume()
                    return
                }

                totalDragX = gestureDragX
                turnDragActive = true
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, rightToLeft),
                    readerTurnProgress(totalDragX, turnVisualDistancePx)
                )
                change.consume()
                return
            }

            if (closeDragActive) {
                val horizontalTakeover =
                    abs(gestureDragX) >= horizontalIntentSlopPx &&
                        abs(gestureDragX) > abs(gestureDragY) * 1.05f
                if (horizontalTakeover) {
                    closeDragActive = false
                    closeDragOffsetY = 0f
                    latestOnCloseDrag(0f)
                    totalDragX = gestureDragX
                    turnDragActive = true
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, rightToLeft),
                        readerTurnProgress(totalDragX, turnVisualDistancePx)
                    )
                    change.consume()
                    return
                }
                closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx)
                latestOnCloseDrag(closeDragOffsetY)
                change.consume()
                return
            }

            if (turnDragActive) {
                totalDragX += dragAmount.x
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, rightToLeft),
                    readerTurnProgress(totalDragX, turnVisualDistancePx)
                )
                change.consume()
                return
            }

            val horizontalIntent = abs(gestureDragX) >= turnIntentSlopPx
            val downwardCloseIntent =
                closeSwipeEnabled &&
                    gestureDragY >= verticalCloseIntentSlopPx &&
                    gestureDragY > abs(gestureDragX) * 1.4f
            when {
                downwardCloseIntent -> {
                    closeDragActive = true
                    closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx)
                    latestOnCloseDrag(closeDragOffsetY)
                }
                horizontalIntent -> {
                    totalDragX = gestureDragX
                    turnDragActive = true
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, rightToLeft),
                        readerTurnProgress(totalDragX, turnVisualDistancePx)
                    )
                }
            }
            change.consume()
        }

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            resetDragState()
            var previousPosition = down.position
            val pointerId = down.id

            while (true) {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                if (pressedChanges.size > 1) {
                    cancelDrag()
                    break
                }

                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    finishDrag()
                    break
                }

                val dragAmount = change.position - previousPosition
                previousPosition = change.position
                if (dragAmount != Offset.Zero) {
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
