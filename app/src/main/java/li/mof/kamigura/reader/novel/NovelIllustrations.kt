package li.mof.kamigura.reader.novel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class NovelIllustration(val bounds: Rect, val url: String) {
    override fun toString() = "NovelIllustration(bounds=$bounds, url=[redacted])"
}

internal fun novelIllustrationAt(images: List<NovelIllustration>, point: Offset, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || !point.x.isFinite() || !point.y.isFinite()) return null
    return images.firstOrNull { it.bounds.contains(Offset(point.x / width, point.y / height)) }?.url
}
