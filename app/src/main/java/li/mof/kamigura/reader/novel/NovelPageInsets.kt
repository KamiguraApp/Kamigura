package li.mof.kamigura.reader.novel

import kotlin.math.max

/** Physical edges in CSS pixels, independent of writing or page-turn direction. */
internal data class NovelPageInsets(val left: Float = 0f, val top: Float = 0f,
    val right: Float = 0f, val bottom: Float = 0f) {
    fun relativeToView(leftSpace: Int, topSpace: Int, rightSpace: Int, bottomSpace: Int, density: Float): NovelPageInsets =
        NovelPageInsets(edge(left, leftSpace, density), edge(top, topSpace, density),
            edge(right, rightSpace, density), edge(bottom, bottomSpace, density))

    private fun edge(cutout: Float, space: Int, density: Float) =
        if (cutout > 0f) max(0f, cutout - space) / density else 0f
}
