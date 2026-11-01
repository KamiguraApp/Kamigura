package li.mof.kamigura.reader.internal

import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor

internal data class ReaderRegionRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

internal data class ReaderRegionPlan(val sample: Int, val tiles: List<ReaderRegionRect>)

internal fun readerRegionPlan(width: Int, height: Int, visible: Rect, displayScale: Float,
    maxPixels: Long): ReaderRegionPlan? {
    if (width <= 0 || height <= 0 || !displayScale.isFinite() || displayScale <= 0f || maxPixels <= 0 ||
        listOf(visible.left, visible.top, visible.right, visible.bottom).any { !it.isFinite() }) return null
    val left = floor(visible.left.coerceIn(0f, 1f).toDouble() * width).toInt()
    val top = floor(visible.top.coerceIn(0f, 1f).toDouble() * height).toInt()
    val right = ceil(visible.right.coerceIn(0f, 1f).toDouble() * width).toInt().coerceAtMost(width)
    val bottom = ceil(visible.bottom.coerceIn(0f, 1f).toDouble() * height).toInt().coerceAtMost(height)
    if (right <= left || bottom <= top) return null
    var sample = 1
    while (sample < 1 shl 28 && displayScale * sample * 2 <= 1f) sample *= 2
    fun pixels() = ((right - left).toLong() + sample - 1) / sample *
        (((bottom - top).toLong() + sample - 1) / sample)
    while (pixels() > maxPixels && sample < 1 shl 28) sample *= 2
    if (pixels() > maxPixels) return null
    val edge = 1024L * sample
    val tiles = mutableListOf<ReaderRegionRect>()
    var y = top
    while (y < bottom) {
        val nextY = (y.toLong() + edge).coerceAtMost(bottom.toLong()).toInt()
        var x = left
        while (x < right) {
            val nextX = (x.toLong() + edge).coerceAtMost(right.toLong()).toInt()
            tiles += ReaderRegionRect(x, y, nextX, nextY)
            x = nextX
        }
        y = nextY
    }
    return ReaderRegionPlan(sample, tiles)
}
