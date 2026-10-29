package li.mof.kamigura.reader

import li.mof.kamigura.NovelMargins
import li.mof.kamigura.NovelSettings
import li.mof.kamigura.UserReadingProfileDto
import li.mof.kamigura.reader.novel.NovelPageInsets
import li.mof.kamigura.reader.novel.resolveNovelReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class NovelPageInsetsTest {
    @Test fun marginsAreFixedAndIgnoreTheServerMargin() {
        assertEquals(NovelMargins.Normal, NovelSettings().margins)
        assertEquals(listOf(12, 20, 32), NovelMargins.entries.map { it.dp })
        for (margin in NovelMargins.entries) for (serverMargin in listOf(0, 15, 45, 99)) {
            val preferences = resolveNovelReaderPreferences(UserReadingProfileDto(bookReaderMargin = serverMargin), null,
                novelSettings = NovelSettings(margins = margin))
            assertEquals(margin.dp, preferences.margin)
        }
    }

    @Test fun onlySeriesProfilesSupplyHorizontalPageDirection() {
        assertEquals(0, resolveNovelReaderPreferences(null, null).readingDirection)
        for (direction in 0..1) {
            assertEquals(0, resolveNovelReaderPreferences(UserReadingProfileDto(kind = 0, bookReaderReadingDirection = direction), null).readingDirection)
            for (kind in 1..2) assertEquals(direction,
                resolveNovelReaderPreferences(UserReadingProfileDto(kind = kind, bookReaderReadingDirection = direction), null).readingDirection)
        }
        assertEquals(0, resolveNovelReaderPreferences(UserReadingProfileDto(kind = 1, bookReaderReadingDirection = 9), null).readingDirection)
    }

    @Test fun cutoutsAreConvertedFromWindowPixelsToViewportCssPixels() {
        val raw = NovelPageInsets(60f, 120f, 90f, 150f)
        assertEquals(NovelPageInsets(20f, 40f, 30f, 50f), raw.relativeToView(0, 0, 0, 0, 3f))
        assertEquals(NovelPageInsets(10f, 30f, 20f, 40f), raw.relativeToView(30, 30, 30, 30, 3f))
        assertEquals(NovelPageInsets(), raw.relativeToView(100, 140, 100, 160, 3f))
    }

    @Test fun noCutoutAddsNoExtraInsets() {
        assertEquals(NovelPageInsets(), NovelPageInsets().relativeToView(10, 10, 10, 10, 2f))
        assertEquals(NovelPageInsets(), NovelPageInsets().relativeToView(-10, -10, -10, -10, 2f))
    }
}
