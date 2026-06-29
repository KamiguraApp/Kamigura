package li.mof.kamigura.reader.internal

import li.mof.kamigura.FileDimensionDto
import kotlin.math.roundToInt

/** Internal to reader, not for external use. */
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

/** Internal to reader, not for external use. */
internal fun readerPrefetchPageIndicesAround(
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

/** Internal to reader, not for external use. */
internal fun readerPrefetchSlotWidthPx(
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
