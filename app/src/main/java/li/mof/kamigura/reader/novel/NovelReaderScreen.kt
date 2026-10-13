package li.mof.kamigura.reader.novel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ProgressDto
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.reader.ReaderTurnDirection
import li.mof.kamigura.reader.internal.ReaderKeyEffect

/** Branch-only experiment. Image EPUB, CBZ, PDF and offline reading stay on ReaderScreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderScreen(
    api: KavitaApi,
    baseUrl: String,
    libraryId: Int,
    seriesId: Int,
    volumeId: Int,
    chapterId: Int,
    spineCount: Int,
    seriesName: String,
    chapterName: String,
    initialProgress: ProgressDto,
    incognito: Boolean,
    volumeKeys: Boolean,
    onMenuChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spine by rememberSaveable { mutableIntStateOf(initialProgress.pageNum.coerceIn(0, spineCount - 1)) }
    var savedAnchor by rememberSaveable { mutableStateOf(initialProgress.bookScrollId) }
    var openLast by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf<NovelPosition?>(null) }
    var reachedSpine by rememberSaveable { mutableIntStateOf(spine) }
    var renderedSpine by remember { mutableIntStateOf(-1) }
    var moved by rememberSaveable { mutableStateOf(false) }
    var turning by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var writingOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val writer = remember(api, incognito) { NovelProgressWriter(api, incognito) }
    val view = remember { NovelPageView(context) }

    fun progress(at: NovelPosition) = ProgressDto(libraryId, seriesId, volumeId, chapterId, reachedSpine, at.anchor)
    fun close() {
        if (turning) return
        turning = true
        view.position { latest ->
            val reached = if (!loading) latest ?: position else position
            closing = true
            if (moved && reached != null) writer.submit(progress(reached), immediately = true)
            onMenuChanged(false)
            onBack()
        }
    }
    fun loadSpine(target: Int, last: Boolean = false) {
        renderedSpine = -1
        view.beginSectionLoad()
        spine = target
        savedAnchor = null
        openLast = last
        loading = true
        turning = false
        moved = true
    }
    fun boundary(delta: Int) {
        val target = spine + delta
        if (target in 0 until spineCount) { loadSpine(target, delta < 0); return }
        scope.launch {
            try {
                writer.cancelPending()
                if (!incognito) {
                    writer.finish(if (delta > 0) position?.let(::progress) else null) {
                        if (delta > 0) api.markChapterRead(MarkChapterReadDto(seriesId, chapterId, false))
                        else api.markChaptersUnread(MarkVolumesReadDto(seriesId = seriesId, chapterIds = listOf(chapterId)))
                    }
                }
                onMenuChanged(false)
                closing = true
                onBack()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { failure = "Could not update reading progress."; turning = false }
        }
    }
    fun turn(delta: Int) {
        if (loading || turning || menu || failure != null) return
        turning = true
        moved = true
        view.turn(delta, ::boundary)
    }
    val latestPosition by rememberUpdatedState<(NovelPosition) -> Unit>({ current ->
        if (renderedSpine == spine) {
            position = current
            savedAnchor = current.anchor
            reachedSpine = renderedSpine
            loading = false
            turning = false
            if (moved) writer.submit(progress(current))
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
                view.open(html, baseUrl, chapterId, savedAnchor, openLast, writingOverride)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { failure = "Could not load this EPUB section."; loading = false }
    }
    LaunchedEffect(menu) { onMenuChanged(menu) }
    BackHandler { if (menu) menu = false else close() }
    ReaderKeyEffect(
        if (position?.vertical == true) ReaderReadingDirection.RightToLeft else ReaderReadingDirection.LeftToRight,
        volumeKeys, isBlocked = { loading || turning || menu },
        onTurn = { turn(if (it == ReaderTurnDirection.Next) 1 else -1) }
    )

    Box(Modifier.fillMaxSize().background(Color(0xFFFAF7F2))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        // A simple tap-only overlay leaves the image reader's gesture machinery untouched.
        val latestTurn by rememberUpdatedState<(Int) -> Unit>(::turn)
        val latestVertical by rememberUpdatedState(position?.vertical == true)
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = if (loading || failure != null) "Loading EPUB" else "EPUB page"
            stateDescription = "Section ${spine + 1} / $spineCount · Screen ${(position?.screen ?: 0) + 1} / ${position?.count ?: 1}"
        }.pointerInput(Unit) {
            detectTapGestures { offset ->
                when {
                    offset.x in size.width / 3f..size.width * 2f / 3f -> menu = !menu
                    (offset.x < size.width / 3f) == latestVertical -> latestTurn(1)
                    else -> latestTurn(-1)
                }
            }
        })
        if (loading || failure != null) {
            Column(Modifier.align(Alignment.Center).background(Color(0xFFFAF7F2)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure ?: "Opening EPUB...", color = Color(0xFF171717))
                if (failure != null) TextButton(onClick = { reload++ }) { Text("Retry") }
                TextButton(onClick = ::close) { Text("Back to series") }
            }
        }
        if (menu) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to series") }
                Column(Modifier.weight(1f)) {
                    Text(seriesName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapterName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { menu = false }) { Icon(Icons.Default.Close, "Close menu") }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Section ${spine + 1} / $spineCount · Screen ${(position?.screen ?: 0) + 1} / ${position?.count ?: 1}")
                SingleChoiceSegmentedButtonRow {
                    listOf("Horizontal" to false, "Vertical" to true).forEachIndexed { index, (label, vertical) ->
                        SegmentedButton(selected = position?.vertical == vertical, enabled = !loading && failure == null,
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            onClick = { writingOverride = vertical; view.setVertical(vertical) }) { Text(label) }
                    }
                }
                if (spineCount > 1) Slider(value = spine.toFloat(), onValueChange = {
                    val target = it.toInt().coerceIn(0, spineCount - 1)
                    if (target != spine) loadSpine(target)
                }, valueRange = 0f..(spineCount - 1).toFloat(), enabled = !loading)
                TextButton(onClick = ::close) { Text("Back to series") }
            }
        }
    }
}
