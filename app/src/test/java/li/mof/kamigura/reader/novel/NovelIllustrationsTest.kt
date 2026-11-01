package li.mof.kamigura.reader.novel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class NovelIllustrationsTest {
    @Test fun hitTestingUsesNativeCoordinatesAndNeverSelectsOutsideTheImage() {
        val images = listOf(NovelIllustration(Rect(.1f, .2f, .9f, .7f), "private-resource"))
        assertEquals("private-resource", novelIllustrationAt(images, Offset(500f, 500f), 1000, 1000))
        assertNull(novelIllustrationAt(images, Offset(500f, 800f), 1000, 1000))
        assertNull(novelIllustrationAt(images, Offset(Float.NaN, 500f), 1000, 1000))
        assertNull(novelIllustrationAt(images, Offset.Zero, 0, 1000))
    }
    @Test fun diagnosticTextNeverContainsTheResourceUrl() {
        assertFalse(NovelIllustration(Rect.Zero, "https://private.invalid?apiKey=secret").toString().contains("secret"))
        assertFalse(NovelIllustration(Rect.Zero, "https://private.invalid?apiKey=secret").toString().contains("private.invalid"))
    }
    @Test fun transformedImageRejectsBlankCornersOfItsBoundingRectangle() {
        val diamond = NovelIllustration(Rect(.1f, .1f, .9f, .9f), "private-resource",
            listOf(Offset(.5f, .1f), Offset(.9f, .5f), Offset(.5f, .9f), Offset(.1f, .5f)))
        assertEquals("private-resource", novelIllustrationAt(listOf(diamond), Offset(500f, 500f), 1000, 1000))
        assertNull(novelIllustrationAt(listOf(diamond), Offset(110f, 110f), 1000, 1000))
        assertNull(novelIllustrationAt(listOf(diamond), Offset(890f, 890f), 1000, 1000))
    }
    @Test fun clippedImagePolygonKeepsItsActualHitArea() {
        val clipped = NovelIllustration(Rect(0f, .1f, .8f, .9f), "private-resource",
            listOf(Offset(0f, .1f), Offset(.8f, .1f), Offset(.4f, .9f), Offset(0f, .7f)))
        assertEquals("private-resource", novelIllustrationAt(listOf(clipped), Offset(100f, 500f), 1000, 1000))
        assertNull(novelIllustrationAt(listOf(clipped), Offset(750f, 850f), 1000, 1000))
    }
}
