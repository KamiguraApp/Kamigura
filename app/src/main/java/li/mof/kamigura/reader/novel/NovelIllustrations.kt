package li.mof.kamigura.reader.novel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

internal data class NovelIllustration(val bounds: Rect, val url: String, val points: List<Offset> = emptyList()) {
    override fun toString() = "NovelIllustration(bounds=$bounds, url=[redacted])"
}

internal fun novelIllustrationAt(images: List<NovelIllustration>, point: Offset, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || !point.x.isFinite() || !point.y.isFinite()) return null
    val normalized = Offset(point.x / width, point.y / height)
    return images.firstOrNull { image ->
        image.bounds.contains(normalized) && (image.points.isEmpty() || illustrationPolygonContains(image.points, normalized))
    }?.url
}

private fun illustrationPolygonContains(points: List<Offset>, point: Offset): Boolean {
    var inside = false
    var previous = points.last()
    for (current in points) {
        if ((current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x) inside = !inside
        previous = current
    }
    return inside
}
