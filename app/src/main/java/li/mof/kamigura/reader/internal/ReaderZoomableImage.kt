package li.mof.kamigura.reader.internal

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ReaderImageZoomMotion(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f,
    val transition: Any? = null)
internal val LocalReaderImageZoomMotion = compositionLocalOf { ReaderImageZoomMotion() }
private val RegionDecodePermit = Semaphore(1)
private data class ReaderSharpTile(val rect: ReaderRegionRect, val bitmap: Bitmap)

/** Shared preview + bounded region tiles. The caller retains ownership of zoom and gestures. */
@Composable
internal fun ReaderZoomableImage(source: ReaderRegionModel?, alignment: Alignment = Alignment.Center,
    colorFilter: ColorFilter? = null, modifier: Modifier = Modifier,
    motion: ReaderImageZoomMotion = LocalReaderImageZoomMotion.current, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val direction = LocalLayoutDirection.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val enabled = source != null && motion.scale > 1f + ReaderZoomEpsilon
    val reader by produceState<ReaderRegionSource?>(null, source, enabled) {
        value = null
        if (!enabled) return@produceState
        var opened: ReaderRegionSource? = null
        try {
            withContext(Dispatchers.IO) { opened = openReaderRegionSource(context, requireNotNull(source)) { ensureActive() } }
            value = opened
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { RegionDecodePermit.withPermit { runCatching { opened?.close() } } }
        }
    }
    var tiles by remember(source, reader) { mutableStateOf<List<ReaderSharpTile>>(emptyList()) }
    var settled by remember(source, reader) { mutableStateOf<Pair<ReaderImageZoomMotion, IntSize>?>(null) }
    val sourceReader = reader
    val fit = sourceReader?.let { min(size.width.toFloat() / it.width, size.height.toFloat() / it.height) } ?: 0f
    val fittedSize = sourceReader?.let { IntSize((it.width * fit).roundToInt(), (it.height * fit).roundToInt()) } ?: IntSize.Zero
    val origin = alignment.align(fittedSize, size, direction)
    LaunchedEffect(sourceReader, enabled, motion, size, alignment, direction) {
        if (!enabled) return@LaunchedEffect
        val active = sourceReader ?: return@LaunchedEffect
        delay(150)
        val layout = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
        if (fit <= 0f) return@LaunchedEffect
        val window = layout.boundsInWindow()
        val start = layout.windowToLocal(window.topLeft)
        val end = layout.windowToLocal(window.bottomRight)
        val visible = Rect((start.x - origin.x) / (active.width * fit),
            (start.y - origin.y) / (active.height * fit), (end.x - origin.x) / (active.width * fit),
            (end.y - origin.y) / (active.height * fit))
        val budget = (Runtime.getRuntime().maxMemory() / 16 / 4).coerceIn(262_144L, 8_388_608L)
        val plan = readerRegionPlan(active.width, active.height, visible, fit * motion.scale, budget)
            ?: return@LaunchedEffect
        val decoded = mutableListOf<ReaderSharpTile>()
        try {
            withContext(Dispatchers.IO) {
                RegionDecodePermit.withPermit {
                    for (rect in plan.tiles) {
                        ensureActive()
                        val bitmap = active.decode(rect, plan.sample) ?: error("Region unavailable")
                        decoded += ReaderSharpTile(rect, bitmap)
                    }
                    ensureActive()
                }
            }
            tiles = decoded
            settled = motion to size
        } catch (cancelled: CancellationException) {
            decoded.forEach { it.bitmap.recycle() }
            throw cancelled
        } catch (_: Exception) {
            decoded.forEach { it.bitmap.recycle() }
        } catch (_: OutOfMemoryError) {
            decoded.forEach { it.bitmap.recycle() }
        }
    }
    Box(modifier.onGloballyPositioned { coordinates = it; size = it.size }, propagateMinConstraints = true) {
        content()
        if (enabled && sourceReader != null && tiles.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize().testTag(if (settled == (motion to size)) "Reader sharp image" else "Reader sharp preview")) {
                tiles.forEach { tile ->
                    val rect = tile.rect
                    withTransform({
                        translate(origin.x + rect.left * fit, origin.y + rect.top * fit)
                        scale(rect.width * fit / tile.bitmap.width, rect.height * fit / tile.bitmap.height, Offset.Zero)
                    }) { drawImage(tile.bitmap.asImageBitmap(), colorFilter = colorFilter) }
                }
            }
        }
    }
}
