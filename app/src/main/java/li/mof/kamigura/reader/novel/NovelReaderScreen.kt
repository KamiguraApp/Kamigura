package li.mof.kamigura.reader.novel

import android.view.ViewConfiguration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.withContext
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ProgressDto
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
import li.mof.kamigura.reader.internal.ReaderMenuHeader
import li.mof.kamigura.reader.internal.ReaderTapLayer
import li.mof.kamigura.ui.ValueBubbleSlider
import kotlin.math.roundToInt

private val NovelPreferenceWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderScreen(
    api: KavitaApi, baseUrl: String, libraryId: Int, seriesId: Int, volumeId: Int,
    chapterId: Int, spineCount: Int, seriesName: String, chapterName: String,
    initialProgress: ProgressDto, incognito: Boolean, volumeKeys: Boolean,
    onMenuChanged: (Boolean) -> Unit, onBack: () -> Unit,
    profile: UserReadingProfileDto?, preferenceKey: String,
    currentChapter: ReaderChapterEntry, neighbors: ReaderChapterNeighbors,
    initialLastScreen: Boolean, chapterEntered: Boolean,
    chapterSwitching: Boolean, switchError: String?,
    onSwitchChapter: (ReaderChapterEntry, Boolean) -> Unit
) {
    val context = LocalContext.current
    val initialOverrides = remember(preferenceKey) {
        ReaderSessionPreferenceCache.getNovel(preferenceKey, System.currentTimeMillis())
            ?: NovelReaderOverrides()
    }
    val initialPreferences = resolveNovelReaderPreferences(profile, initialOverrides)
    LaunchedEffect(preferenceKey) {
        if (initialOverrides.writingStyle != null || initialOverrides.fontSize != null) {
            NovelPreferenceWriteScope.launch { ReaderSessionPreferenceCache.persist(context.cacheDir) }
        }
    }
    var writingStyle by rememberSaveable { mutableIntStateOf(initialPreferences.writingStyle) }
    var fontSize by rememberSaveable { mutableIntStateOf(initialPreferences.fontSize) }
    val preferences = initialPreferences.copy(writingStyle = writingStyle, fontSize = fontSize)
    var spine by rememberSaveable { mutableIntStateOf(initialProgress.pageNum.coerceIn(0, spineCount - 1)) }
    var savedAnchor by rememberSaveable { mutableStateOf(initialProgress.bookScrollId) }
    var savedLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var reachedAnchor by rememberSaveable { mutableStateOf(initialProgress.bookScrollId) }
    var openLast by rememberSaveable { mutableStateOf(initialLastScreen) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf<NovelPosition?>(null) }
    var reachedSpine by rememberSaveable { mutableIntStateOf(spine) }
    var hasReachedPage by rememberSaveable { mutableStateOf(false) }
    var renderedSpine by remember { mutableIntStateOf(-1) }
    var moved by rememberSaveable { mutableStateOf(chapterEntered) }
    var turning by remember { mutableStateOf(false) }
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
    val writer = remember(api, incognito) { NovelProgressWriter(api, incognito) }
    val view = remember { NovelPageView(context) }
    val minimumFlingVelocity = remember(context) { ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }

    fun progress(anchor: String?) = ProgressDto(libraryId, seriesId, volumeId, chapterId, reachedSpine, anchor)
    fun persistOverrides(style: Int? = null, font: Int? = null) {
        preferenceKey.let { key ->
            val old = ReaderSessionPreferenceCache.getNovel(key, System.currentTimeMillis()) ?: NovelReaderOverrides()
            ReaderSessionPreferenceCache.putNovel(key, old.copy(writingStyle = style ?: old.writingStyle,
                fontSize = font ?: old.fontSize), System.currentTimeMillis())
            NovelPreferenceWriteScope.launch { ReaderSessionPreferenceCache.persist(context.cacheDir) }
        }
    }
    fun close() {
        if (closing) return
        closing = true
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
    fun loadSpine(target: Int, last: Boolean = false) {
        renderedSpine = -1
        view.beginSectionLoad()
        spine = target
        savedAnchor = null
        savedLocation = null
        openLast = last
        loading = true
        turning = false
        moved = true
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
        if (loading || turning || menu || failure != null || chapterSwitching || closing) return
        boundaryDirection?.let {
            if (it == delta) continueChapter() else boundaryDirection = null
            return
        }
        turning = true
        moved = true
        view.turn(delta, ::sectionBoundary)
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
        try {
            val html = withContext(Dispatchers.IO) { api.bookPage(chapterId, targetSpine).use { it.string() } }
            withContext(Dispatchers.Main.immediate) {
                renderedSpine = targetSpine
                view.open(html, baseUrl, chapterId, savedAnchor, openLast, preferences.vertical, savedLocation, preferences)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { failure = "Could not load this EPUB section."; loading = false }
    }
    LaunchedEffect(menu) { onMenuChanged(menu) }
    BackHandler { if (menu) menu = false else close() }
    ReaderKeyEffect(
        if (preferences.rightToLeft) ReaderReadingDirection.RightToLeft else ReaderReadingDirection.LeftToRight,
        volumeKeys, isBlocked = { loading || turning || menu || dragging || chapterSwitching || closing || failure != null },
        onTurn = { turn(if (it == ReaderTurnDirection.Next) 1 else -1) }
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFFAF7F2))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        ReaderTapLayer(
            rightToLeft = preferences.rightToLeft,
            onNextSpread = { turn(1) }, onPreviousSpread = { turn(-1) },
            onNextSingle = { turn(1) }, onPreviousSingle = { turn(-1) },
            onCenterTap = { if (boundary == null && !chapterSwitching && !closing) menu = !menu },
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
            stateDescription = "Section ${spine + 1} / $spineCount · Screen ${(position?.screen ?: 0) + 1} / ${position?.count ?: 1}"
        })
        if (loading || failure != null) {
            Column(Modifier.align(Alignment.Center).background(Color(0xFFFAF7F2)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure ?: "Opening EPUB...", color = Color(0xFF171717))
                if (failure != null) TextButton(onClick = { reload++ }) { Text("Retry") }
                TextButton(onClick = ::close) { Text("Back to series") }
            }
        }
        boundary?.let {
            ReaderChapterBoundaryScreen(seriesName, it, chapterSwitching, switchError != null,
                onContinue = ::continueChapter, onBackToSeries = ::close)
        }
        if (menu) {
            Column(Modifier.fillMaxSize()) {
                ReaderMenuHeader(seriesName, "$chapterName | Section ${spine + 1} / $spineCount", ::close,
                    { menu = false }, backLabel = "Back to series")
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Section ${spine + 1} / $spineCount · Screen ${(position?.screen ?: 0) + 1} / ${position?.count ?: 1}", color = Color.White)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Horizontal", "Vertical").forEachIndexed { index, label ->
                            SegmentedButton(selected = writingStyle == index, enabled = !loading && failure == null && !turning,
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                onClick = { writingStyle = index; persistOverrides(style = index); view.setVertical(index == 1) }) { Text(label) }
                        }
                    }
                    Text("Font size", color = Color.White)
                    var selectedFont by remember(fontSize) { mutableIntStateOf(fontSize) }
                    ValueBubbleSlider(value = selectedFont.toFloat(), onValueChange = { selectedFont = it.roundToInt() },
                        onValueChangeFinished = { fontSize = selectedFont; persistOverrides(font = fontSize); view.setFontSize(fontSize) },
                        valueRange = minOf(50, fontSize).toFloat()..maxOf(250, fontSize).toFloat(), valueLabel = { "${it.roundToInt()}%" },
                        enabled = !loading && failure == null && !turning,
                        modifier = Modifier.semantics { contentDescription = "Font size" })
                    if (spineCount > 1) {
                        var jumpSpine by remember(spine) { mutableIntStateOf(spine) }
                        ValueBubbleSlider(value = (if (preferences.rightToLeft) spineCount - 1 - jumpSpine else jumpSpine).toFloat(),
                            onValueChange = { jumpSpine = if (preferences.rightToLeft) spineCount - 1 - it.roundToInt() else it.roundToInt() },
                            onValueChangeFinished = { if (jumpSpine != spine) loadSpine(jumpSpine) },
                            valueRange = 0f..(spineCount - 1).toFloat(),
                            valueLabel = { "${(if (preferences.rightToLeft) spineCount - 1 - it.roundToInt() else it.roundToInt()) + 1}" },
                            enabled = !loading, reverseTrackColors = preferences.rightToLeft,
                            modifier = Modifier.semantics { contentDescription = "EPUB section" })
                    }
                }
            }
        }
    }
}
