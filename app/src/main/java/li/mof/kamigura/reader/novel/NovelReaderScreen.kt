package li.mof.kamigura.reader.novel

import android.view.ViewConfiguration
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ProgressDto
import li.mof.kamigura.PageBackground
import li.mof.kamigura.PersonalToCDto
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.UserReadingProfileDto
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import li.mof.kamigura.reader.ReaderTurnDirection
import li.mof.kamigura.reader.shouldCommitReaderTurn
import li.mof.kamigura.reader.internal.ReaderChapterBoundary
import li.mof.kamigura.reader.internal.ReaderChapterBoundaryScreen
import li.mof.kamigura.reader.internal.ReaderChapterEntry
import li.mof.kamigura.reader.internal.ReaderChapterNeighbors
import li.mof.kamigura.reader.internal.ReaderKeyEffect
import li.mof.kamigura.reader.internal.ReaderTapLayer
import li.mof.kamigura.reader.internal.ReaderBookmarkState
import li.mof.kamigura.reader.internal.readerCanManageBookmarks
import kotlin.coroutines.resume

private val NovelPreferenceWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
internal fun NovelReaderScreen(
    api: KavitaApi, baseUrl: String, libraryId: Int, seriesId: Int, volumeId: Int,
    chapterId: Int, spineCount: Int, seriesName: String, chapterName: String,
    initialProgress: ProgressDto, incognito: Boolean, volumeKeys: Boolean,
    onMenuChanged: (Boolean) -> Unit, onBack: () -> Unit,
    profile: UserReadingProfileDto?, preferenceKey: String,
    defaultBackground: PageBackground, usePureColors: Boolean,
    currentChapter: ReaderChapterEntry, neighbors: ReaderChapterNeighbors,
    initialLastScreen: Boolean, chapterEntered: Boolean,
    chapterSwitching: Boolean, switchError: String?,
    onSwitchChapter: (ReaderChapterEntry, Boolean) -> Unit,
    offline: Boolean = false
) {
    val context = LocalContext.current
    val initialOverrides = remember(preferenceKey) {
        ReaderSessionPreferenceCache.getNovel(preferenceKey, System.currentTimeMillis())
            ?: NovelReaderOverrides()
    }
    val initialPreferences = resolveNovelReaderPreferences(profile, initialOverrides, defaultBackground, usePureColors)
    LaunchedEffect(preferenceKey) {
        if (initialOverrides.writingStyle != null || initialOverrides.fontSize != null || initialOverrides.background != null) {
            NovelPreferenceWriteScope.launch { ReaderSessionPreferenceCache.persist(context.cacheDir) }
        }
    }
    var writingStyle by rememberSaveable { mutableIntStateOf(initialPreferences.writingStyle) }
    var fontSize by rememberSaveable { mutableIntStateOf(initialPreferences.fontSize) }
    var backgroundOverride by rememberSaveable { mutableStateOf(initialOverrides.background?.name) }
    val preferences = initialPreferences.copy(writingStyle = writingStyle, fontSize = fontSize,
        background = backgroundOverride?.let { PageBackground.valueOf(it) } ?: initialPreferences.background)
    val colors = novelReaderColors(preferences.background, preferences.usePureColors)
    var spine by rememberSaveable { mutableIntStateOf(initialProgress.pageNum.coerceIn(0, spineCount - 1)) }
    var savedAnchor by rememberSaveable { mutableStateOf(initialProgress.bookScrollId) }
    var savedLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var reachedAnchor by rememberSaveable { mutableStateOf(initialProgress.bookScrollId) }
    var openLast by rememberSaveable { mutableStateOf(initialLastScreen) }
    var menu by remember { mutableStateOf(false) }
    var contentsList by remember { mutableStateOf(false) }
    var contentsTab by rememberSaveable { mutableIntStateOf(0) }
    var currentContentsEntry by remember { mutableStateOf<Int?>(null) }
    var bookmarkContext by remember { mutableStateOf<NovelBookmarkContext?>(null) }
    var bookmarkPositionError by remember { mutableStateOf<String?>(null) }
    var checkingBookmark by remember { mutableStateOf(false) }
    var pendingBookmarkAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var previousFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf<NovelPosition?>(null) }
    var reachedSpine by rememberSaveable { mutableIntStateOf(spine) }
    var hasReachedPage by rememberSaveable { mutableStateOf(false) }
    var renderedSpine by remember { mutableIntStateOf(-1) }
    var moved by rememberSaveable { mutableStateOf(chapterEntered) }
    var turning by remember { mutableStateOf(false) }
    var queuedTurns by remember { mutableStateOf<List<Int>>(emptyList()) }
    var closing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragDirection by remember { mutableStateOf<ReaderTurnDirection?>(null) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var reload by remember { mutableIntStateOf(0) }
    var boundaryDirection by rememberSaveable { mutableStateOf<Int?>(null) }
    val boundary = boundaryDirection?.let { delta ->
        (if (delta > 0) neighbors.next else neighbors.previous)?.let {
            ReaderChapterBoundary(if (delta > 0) ReaderTurnDirection.Next else ReaderTurnDirection.Previous, currentChapter, it)
        }
    }
    // Retrofit proxies need identity keys; their Object.equals implementation is not reflexive.
    val apiIdentity = System.identityHashCode(api)
    val writer = remember(apiIdentity, incognito) { NovelProgressWriter(api, incognito) }
    val view = remember { NovelPageView(context).apply { setColors(colors) } }
    val scope = rememberCoroutineScope()
    val sections = remember(apiIdentity, chapterId) {
        NovelSectionCache(scope) { page -> withContext(Dispatchers.IO) { api.bookPage(chapterId, page).use { it.string() } } }
    }
    DisposableEffect(sections) { onDispose { sections.close() } }
    val bookmarks = remember(apiIdentity, chapterId, incognito, offline) {
        if (readerCanManageBookmarks(incognito, offline))
            NovelBookmarks(api, chapterId, volumeId, seriesId, libraryId, spineCount) else null
    }
    val contents = remember(apiIdentity, chapterId, offline) {
        if (!offline) NovelContents(api, chapterId, spineCount) else null
    }
    LaunchedEffect(colors) { view.setColors(colors) }
    val minimumFlingVelocity = remember(context) { ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }

    fun progress(anchor: String?) = ProgressDto(libraryId, seriesId, volumeId, chapterId, reachedSpine, anchor)
    fun persistOverrides(style: Int? = null, font: Int? = null, background: PageBackground? = null) {
        preferenceKey.let { key ->
            val old = ReaderSessionPreferenceCache.getNovel(key, System.currentTimeMillis()) ?: NovelReaderOverrides()
            ReaderSessionPreferenceCache.putNovel(key, old.copy(writingStyle = style ?: old.writingStyle,
                fontSize = font ?: old.fontSize, background = background ?: old.background), System.currentTimeMillis())
            NovelPreferenceWriteScope.launch { ReaderSessionPreferenceCache.persist(context.cacheDir) }
        }
    }
    fun close() {
        if (closing) return
        closing = true
        queuedTurns = emptyList()
        fun finishClose(anchor: String?) {
            if (moved && hasReachedPage && boundaryDirection == null) {
                writer.submit(progress(anchor), immediately = true)
            }
            onMenuChanged(false)
            onBack()
        }
        if (loading || failure != null || boundaryDirection != null || chapterSwitching) finishClose(reachedAnchor)
        else view.position { latest -> finishClose(latest?.anchor ?: reachedAnchor) }
    }
    fun loadSpine(target: Int, last: Boolean = false, anchor: String? = null) {
        if (!loading && renderedSpine >= 0) previousFrame = view.captureFrame()?.asImageBitmap()
        renderedSpine = -1
        view.beginSectionLoad()
        spine = target
        savedAnchor = anchor
        savedLocation = null
        openLast = last
        loading = true
        turning = false
        moved = true
    }
    fun openLocation(page: Int, anchor: String?) {
        if (loading || turning || chapterSwitching || closing || bookmarks?.state?.busy == true) return
        if (page !in 0 until spineCount) return
        bookmarkPositionError = null
        moved = true
        if (page != spine) {
            pendingBookmarkAnchor = anchor.orEmpty()
            loadSpine(page, anchor = anchor)
        } else {
            turning = true
            view.jumpToBookmark(anchor) { error ->
                turning = false
                bookmarkPositionError = error
                if (error != null && !menu) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                if (error == null) { contentsList = false; menu = false }
            }
        }
    }
    fun continueChapter() {
        boundary?.let { onSwitchChapter(it.neighbor, it.direction == ReaderTurnDirection.Previous) }
    }
    fun sectionBoundary(delta: Int) {
        if (closing) return
        val target = spine + delta
        if (target in 0 until spineCount) { loadSpine(target, delta < 0); return }
        turning = false
        menu = false
        queuedTurns = emptyList()
        writer.finishAsync(if (delta > 0) progress(reachedAnchor) else null) {
            if (delta > 0) api.markChapterRead(MarkChapterReadDto(seriesId, chapterId, false))
            else api.markChaptersUnread(MarkVolumesReadDto(seriesId = seriesId, chapterIds = listOf(chapterId)))
        }
        if ((if (delta > 0) neighbors.next else neighbors.previous) == null) {
            closing = true
            onMenuChanged(false)
            onBack()
        } else boundaryDirection = delta
    }
    fun turn(delta: Int) {
        if (menu || failure != null || chapterSwitching || closing) return
        if (loading || turning) { queuedTurns = queuedTurns + delta; return }
        boundaryDirection?.let {
            if (it == delta) continueChapter() else boundaryDirection = null
            return
        }
        turning = true
        moved = true
        view.turn(delta, ::sectionBoundary)
    }
    LaunchedEffect(loading, turning, dragging, queuedTurns, menu, failure, chapterSwitching, closing, boundaryDirection, pendingBookmarkAnchor) {
        if (menu || failure != null || closing || boundaryDirection != null) queuedTurns = emptyList()
        else if (!loading && !turning && !dragging && !chapterSwitching && pendingBookmarkAnchor == null && queuedTurns.isNotEmpty()) {
            val next = queuedTurns.first()
            queuedTurns = queuedTurns.drop(1)
            turn(next)
        }
    }
    val latestPosition by rememberUpdatedState<(NovelPosition) -> Unit>({ current ->
        if (renderedSpine == spine && !closing) {
            position = current
            savedAnchor = current.anchor
            savedLocation = current.location
            reachedAnchor = current.anchor
            reachedSpine = renderedSpine
            hasReachedPage = true
            openLast = false
            loading = false
            previousFrame = null
            turning = false
            if (moved && boundaryDirection == null) writer.submit(progress(current.anchor))
        }
    })
    val latestFailure by rememberUpdatedState<(String) -> Unit>({ failure = it; loading = false; turning = false })
    DisposableEffect(view) {
        view.onPosition = { latestPosition(it) }
        view.onFailure = { latestFailure(it) }
        onDispose { if (!closing) writer.cancelPending(); view.dispose() }
    }
    LaunchedEffect(spine, reload) {
        val targetSpine = spine
        renderedSpine = -1
        view.beginSectionLoad()
        loading = true
        failure = null
        sections.retainAround(targetSpine, spineCount)
        try {
            val html = sections.get(targetSpine)
            withContext(Dispatchers.Main.immediate) {
                renderedSpine = targetSpine
                view.open(html, baseUrl, chapterId, savedAnchor, openLast, preferences.vertical, savedLocation, preferences)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { failure = "Could not load this EPUB section."; loading = false }
    }
    LaunchedEffect(loading, failure, renderedSpine) {
        if (!loading && failure == null && renderedSpine == spine) sections.prefetchAround(spine, spineCount)
    }
    LaunchedEffect(menu) { onMenuChanged(menu); if (!menu) contentsList = false }
    LaunchedEffect(menu, bookmarks) { if (menu) bookmarks?.refresh() }
    LaunchedEffect(contentsList, contentsTab, contents) {
        if (contentsList && (bookmarks == null || contentsTab == 0) && contents?.state?.loaded == false) contents.refresh()
    }
    LaunchedEffect(position, spine, loading, writingStyle, fontSize, contents?.state?.entries) {
        currentContentsEntry = null
        if (!loading && contents?.state?.loaded == true) {
            val entries = contents.state.entries
            val parts = entries.filter { it.page == spine && it.part.isNotBlank() }.map { it.part }.distinct()
            val reached = suspendCancellableCoroutine<Set<String>?> { continuation ->
                view.contentsParts(parts) { if (continuation.isActive) continuation.resume(it) }
            }
            currentContentsEntry = currentNovelContentsEntry(entries, spine, reached.orEmpty())
        }
    }
    LaunchedEffect(menu, position, spine, loading, turning, writingStyle, fontSize,
        bookmarks?.state?.items, bookmarks?.state?.loaded, bookmarks?.state?.loading) {
        bookmarkContext = null
        if (menu && !loading && !turning && bookmarks?.state?.loaded == true) {
            val items = bookmarks.state.items.filter { it.pageNumber == spine }
            bookmarkContext = suspendCancellableCoroutine { continuation ->
                view.bookmarkContext(items) { if (continuation.isActive) continuation.resume(it) }
            }
            if (bookmarkContext == null) bookmarkPositionError = "Could not locate this page. Reopen the menu to retry."
        }
    }
    LaunchedEffect(pendingBookmarkAnchor, loading) {
        val anchor = pendingBookmarkAnchor ?: return@LaunchedEffect
        if (loading || closing || failure != null) return@LaunchedEffect
        pendingBookmarkAnchor = null
        turning = true
        view.jumpToBookmark(anchor) { error ->
            turning = false
            bookmarkPositionError = error
            if (error != null && !menu) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            if (error == null) { contentsList = false; menu = false }
        }
    }
    BackHandler { when { contentsList && menu -> contentsList = false; menu -> menu = false; else -> close() } }
    ReaderKeyEffect(
        if (preferences.rightToLeft) ReaderReadingDirection.RightToLeft else ReaderReadingDirection.LeftToRight,
        volumeKeys, isBlocked = { loading || turning || menu || dragging || chapterSwitching || closing || failure != null },
        onTurn = { turn(if (it == ReaderTurnDirection.Next) 1 else -1) }
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(colors.background))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        previousFrame?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        ReaderTapLayer(
            rightToLeft = preferences.rightToLeft,
            onNextSpread = { turn(1) }, onPreviousSpread = { turn(-1) },
            onNextSingle = { turn(1) }, onPreviousSingle = { turn(-1) },
            onCenterTap = { if (boundary == null && !chapterSwitching && !closing) menu = !menu },
            interceptTap = intercept@ { tap, fallback ->
                if (menu || chapterSwitching || closing || dragging) return@intercept
                val snapshot = position
                val link = if (!loading && !turning && failure == null && boundary == null && renderedSpine == spine &&
                    snapshot?.viewportWidthPx == view.width && snapshot.viewportHeightPx == view.height)
                    novelLinkAt(snapshot.links, tap.x, tap.y, view.width, view.height, spine, spineCount) else NovelLink.None
                when (link) {
                    NovelLink.None -> fallback()
                    is NovelLink.Internal -> openLocation(link.page, link.anchor)
                    is NovelLink.External -> {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }) }.onFailure { Toast.makeText(context, "Could not open this link in a browser.", Toast.LENGTH_SHORT).show() }
                    }
                    NovelLink.Ignored -> Unit
                }
            },
            turnVisualDistancePx = constraints.maxWidth.toFloat(),
            onTurnDragStart = { direction, _ -> dragging = true; dragDirection = direction; dragProgress = 0f },
            onTurnDrag = { direction, progress, _ -> dragDirection = direction; dragProgress = progress },
            onTurnDragEnd = { velocity ->
                dragging = false
                if (shouldCommitReaderTurn(dragProgress, velocity, dragDirection, preferences.rightToLeft, minimumFlingVelocity)) dragDirection?.let {
                    turn(if (it == ReaderTurnDirection.Next) 1 else -1)
                }
                dragDirection = null
            }, onTurnDragCancel = { dragging = false; dragDirection = null },
            closeSwipeEnabled = !menu && !loading && !turning && !chapterSwitching,
            closeVisualDistancePx = constraints.maxHeight.toFloat(),
            onCloseDrag = { dragging = true }, onCloseDragCancel = { dragging = false },
            onCloseDragEnd = { committed -> dragging = false; if (committed) close() }
        )
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = when {
                loading || failure != null -> "Loading EPUB"
                boundary != null -> "EPUB chapter boundary"
                else -> "EPUB page"
            }
            stateDescription = "Page ${spine + 1} / $spineCount • Screen ${(position?.screen ?: 0) + 1} / ${position?.count ?: 1}"
        })
        if (loading && previousFrame != null) {
            Text("...", color = Color(colors.text).copy(alpha = .45f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).semantics { contentDescription = "Loading EPUB section" })
        } else if (loading || failure != null) {
            Column(Modifier.align(Alignment.Center).background(Color(colors.background)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure ?: "Opening EPUB...", color = Color(colors.text))
                if (failure != null) TextButton(onClick = { reload++ }) { Text("Retry") }
                TextButton(onClick = ::close) { Text("Back to series") }
            }
        }
        boundary?.let {
            ReaderChapterBoundaryScreen(seriesName, it, chapterSwitching, switchError != null,
                onContinue = ::continueChapter, onBackToSeries = ::close)
        }
        if (menu && contentsList && contents != null) {
            NovelContentsScreen(seriesName, contents.state, bookmarks?.state, contentsTab, currentContentsEntry,
                enabled = !loading && !turning && !chapterSwitching && !closing,
                feedback = bookmarkPositionError,
                onBack = { contentsList = false }, onDismiss = { contentsList = false; menu = false },
                onRefresh = { bookmarkPositionError = null; scope.launch {
                    if (bookmarks == null || contentsTab == 0) contents.refresh() else bookmarks.refresh()
                } },
                onTab = { bookmarkPositionError = null; contentsTab = it },
                onOpenContents = { openLocation(it.page, novelContentsAnchor(it.part)) },
                onOpenBookmark = { openLocation(it.pageNumber, it.bookScrollId) },
                onRemove = { scope.launch { bookmarks?.remove(it) } })
        } else if (menu) {
            val bookmarkState = bookmarks?.state?.let { state -> ReaderBookmarkState(
                pages = if (bookmarkContext?.visible?.isNotEmpty() == true) setOf(spine) else emptySet(),
                loaded = state.loaded && bookmarkContext != null,
                loading = state.loading || checkingBookmark || loading || turning ||
                    (state.loaded && bookmarkContext == null && bookmarkPositionError == null),
                savingPage = spine.takeIf { state.saving }, message = state.message,
                error = bookmarkPositionError ?: state.error)
            }
            NovelReaderMenu(seriesName, chapterName, spine, spineCount, position, preferences,
                enabled = !loading && failure == null && !turning,
                onBack = ::close, onDismiss = { menu = false },
                onWritingStyle = { writingStyle = it; persistOverrides(style = it); view.setVertical(it == 1) },
                onFontSize = { fontSize = it; persistOverrides(font = it); view.setFontSize(it) },
                onJumpToPage = { loadSpine(it) },
                onBackground = { backgroundOverride = it.name; persistOverrides(background = it) },
                bookmarkState = bookmarkState,
                onOpenContents = { bookmarkPositionError = null; contentsList = true },
                showContents = contents != null,
                onBookmark = {
                    if (bookmarks != null && !checkingBookmark && !bookmarks.state.busy && !loading && !turning) {
                        checkingBookmark = true
                        val targetSpine = spine
                        scope.launch {
                            try {
                                if (!bookmarks.state.loaded) { bookmarkPositionError = null; bookmarks.refresh() }
                                else {
                                    val target = suspendCancellableCoroutine<NovelBookmarkContext?> { continuation ->
                                        view.bookmarkContext(bookmarks.state.items.filter { it.pageNumber == targetSpine }) {
                                            if (continuation.isActive) continuation.resume(it)
                                        }
                                    }
                                    if (target == null) bookmarkPositionError = "Could not locate this page. Reopen the menu to retry."
                                    else if (targetSpine == spine && menu && !closing && !loading) {
                                        bookmarks.toggle(targetSpine, target)
                                    }
                                }
                            } finally { checkingBookmark = false }
                        }
                    }
                })
        }
    }
}
