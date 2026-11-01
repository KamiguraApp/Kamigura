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
}
