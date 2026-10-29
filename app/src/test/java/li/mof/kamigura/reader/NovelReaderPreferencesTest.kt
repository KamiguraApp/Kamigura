package li.mof.kamigura.reader

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.InvertMode
import li.mof.kamigura.PageBackground
import li.mof.kamigura.NovelSettings
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.UserReadingProfileDto
import li.mof.kamigura.reader.novel.NovelReaderOverrides
import li.mof.kamigura.reader.novel.NovelReaderPreferences
import li.mof.kamigura.reader.novel.resolveNovelReaderPreferences
import li.mof.kamigura.reader.novel.novelReaderColors
import org.junit.Assert.*
import org.junit.Test

class NovelReaderPreferencesTest {
    @Test fun backgroundDefaultsAndManualChoiceRemainIndependentFromImageOverrides() {
        val result = resolveNovelReaderPreferences(null, NovelReaderOverrides(background = PageBackground.Paper),
            PageBackground.Dark, true)
        assertEquals(PageBackground.Paper, result.background)
        assertTrue(result.usePureColors)
    }

    @Test fun palettesMatchImageMarginsAndRequestedTextColors() {
        assertEquals("#faf7f2", novelReaderColors(PageBackground.Paper, false).backgroundCss)
        assertEquals("#101010", novelReaderColors(PageBackground.Paper, false).textCss)
        assertEquals("#101010", novelReaderColors(PageBackground.Dark, false).backgroundCss)
        assertEquals("#d9d4cb", novelReaderColors(PageBackground.Dark, false).textCss)
        assertEquals("#ffffff", novelReaderColors(PageBackground.Paper, true).backgroundCss)
        assertEquals("#000000", novelReaderColors(PageBackground.Paper, true).textCss)
        assertEquals("#000000", novelReaderColors(PageBackground.Dark, true).backgroundCss)
        assertEquals("#ffffff", novelReaderColors(PageBackground.Dark, true).textCss)
    }

    @Test fun backgroundOnlyCacheRoundTripsAndExpiresWithoutCreatingImagePreferences() = runBlocking {
        val directory = Files.createTempDirectory("novel-colors").toFile()
        try {
            ReaderSessionPreferenceCache.clear(directory)
            val value = NovelReaderOverrides(background = PageBackground.Dark)
            ReaderSessionPreferenceCache.putNovel("series", value, 100)
            ReaderSessionPreferenceCache.persist(directory)
            ReaderSessionPreferenceCache.clearMemoryForTest()
            ReaderSessionPreferenceCache.load(directory, 101)
            assertEquals(value, ReaderSessionPreferenceCache.getNovel("series", 102))
            assertNull(ReaderSessionPreferenceCache.get("series", 103))
            assertNull(ReaderSessionPreferenceCache.getNovel("series", 103 + ReaderSessionPreferenceTtlMillis))
        } finally { ReaderSessionPreferenceCache.clear(directory); directory.deleteRecursively() }
    }
    @Test fun defaultProfileTypographyIsIgnored() {
        assertEquals(NovelReaderPreferences(automaticWritingStyle = true), resolveNovelReaderPreferences(null, null))
        val resolved = resolveNovelReaderPreferences(UserReadingProfileDto(kind = 0,
            bookReaderWritingStyle = 1, bookReaderFontSize = 120, bookReaderLineSpacing = 150,
            bookReaderMargin = 20, bookReaderReadingDirection = 0), null)
        assertFalse(resolved.vertical)
        assertTrue(resolved.automaticWritingStyle)
        assertFalse(resolved.rightToLeft)
        assertEquals(100, resolved.fontSize)
        assertEquals(100, resolved.lineSpacing)
        assertEquals(20, resolved.margin)
    }

    @Test fun manualSizeWinsWhileSpacingComesFromDeviceSettings() {
        val profile = UserReadingProfileDto(bookReaderWritingStyle = 1, bookReaderFontSize = 100,
            bookReaderLineSpacing = 125, bookReaderMargin = 10, bookReaderReadingDirection = 1)
        val resolved = resolveNovelReaderPreferences(profile, NovelReaderOverrides(0, 160),
            novelSettings = NovelSettings(fontSize = 150, lineSpacing = 140))
        assertFalse(resolved.vertical)
        assertTrue(resolved.rightToLeft)
        assertEquals(160, resolved.fontSize)
        assertEquals(140, resolved.lineSpacing)
        assertEquals(20, resolved.margin)
        assertFalse(resolveNovelReaderPreferences(profile.copy(bookReaderReadingDirection = 0), NovelReaderOverrides(0)).rightToLeft)
    }

    @Test fun deviceTypographyUsesAnAbsoluteScaleAndIgnoresEveryProfileKind() {
        for (kind in listOf(null, 0, 1, 2)) {
            val profile = UserReadingProfileDto(kind = kind, bookReaderFontSize = 220, bookReaderLineSpacing = 190)
            val settings = NovelSettings(fontSize = 150, lineSpacing = 135)
            assertEquals(150, resolveNovelReaderPreferences(profile, null, novelSettings = settings).fontSize)
            assertEquals(135, resolveNovelReaderPreferences(profile, null, novelSettings = settings).lineSpacing)
            assertEquals(170, resolveNovelReaderPreferences(profile, NovelReaderOverrides(fontSize = 170), novelSettings = settings).fontSize)
        }
    }

    @Test fun expiredSizeOverrideReturnsToCurrentDeviceDefault() = runBlocking {
        val directory = Files.createTempDirectory("novel-typography").toFile()
        try {
            ReaderSessionPreferenceCache.clear(directory)
            ReaderSessionPreferenceCache.putNovel("series", NovelReaderOverrides(fontSize = 170), 100)
            val settings = NovelSettings(fontSize = 150)
            assertEquals(170, resolveNovelReaderPreferences(null, ReaderSessionPreferenceCache.getNovel("series", 101), novelSettings = settings).fontSize)
            assertEquals(150, resolveNovelReaderPreferences(null,
                ReaderSessionPreferenceCache.getNovel("series", 101 + ReaderSessionPreferenceTtlMillis), novelSettings = settings).fontSize)
        } finally { ReaderSessionPreferenceCache.clear(directory); directory.deleteRecursively() }
    }

    @Test fun deviceRangesDoNotDiscardExistingLargerMenuOverrides() {
        val settings = NovelSettings(fontSize = -20, lineSpacing = 900)
        val fallback = resolveNovelReaderPreferences(null, null, novelSettings = settings)
        assertEquals(50, fallback.fontSize)
        assertEquals(160, fallback.lineSpacing)
        assertEquals(1000, resolveNovelReaderPreferences(null, NovelReaderOverrides(fontSize = 1000), novelSettings = settings).fontSize)
    }

    @Test fun invalidValuesUseSafeFallbacks() {
        val resolved = resolveNovelReaderPreferences(UserReadingProfileDto(bookReaderWritingStyle = -1,
            bookReaderFontSize = 0, bookReaderLineSpacing = -1, bookReaderMargin = 100,
            bookReaderReadingDirection = 99), NovelReaderOverrides(99, -20))
        assertEquals(NovelReaderPreferences(automaticWritingStyle = true), resolved)
    }

    @Test fun novelOnlyEntryDoesNotCreateAnImageOverrideAndRoundTrips() = runBlocking {
        val directory = Files.createTempDirectory("novel-cache").toFile()
        try {
            ReaderSessionPreferenceCache.clear(directory)
            ReaderSessionPreferenceCache.putNovel("series", NovelReaderOverrides(1, 140), 100)
            assertNull(ReaderSessionPreferenceCache.get("series", 101))
            ReaderSessionPreferenceCache.persist(directory)
            ReaderSessionPreferenceCache.clearMemoryForTest()
            ReaderSessionPreferenceCache.load(directory, 102)
            assertNull(ReaderSessionPreferenceCache.get("series", 103))
            assertEquals(NovelReaderOverrides(1, 140), ReaderSessionPreferenceCache.getNovel("series", 104))
        } finally { ReaderSessionPreferenceCache.clear(directory); directory.deleteRecursively() }
    }

    @Test fun imageAndNovelEditsPreserveEachOtherAndExpireTogether() = runBlocking {
        val directory = Files.createTempDirectory("novel-cache").toFile()
        try {
            ReaderSessionPreferenceCache.clear(directory)
            val image = ReaderSessionPreferences(ReaderReadingDirection.LeftToRight, InvertMode.Smart)
            ReaderSessionPreferenceCache.put("series", image, 100)
            ReaderSessionPreferenceCache.putNovel("series", NovelReaderOverrides(1, 120), 101)
            assertEquals(image, ReaderSessionPreferenceCache.get("series", 102))
            ReaderSessionPreferenceCache.put("series", image.copy(invertMode = InvertMode.Always), 103)
            assertEquals(NovelReaderOverrides(1, 120), ReaderSessionPreferenceCache.getNovel("series", 104))
            assertNull(ReaderSessionPreferenceCache.getNovel("series", 104 + ReaderSessionPreferenceTtlMillis))
            assertNull(ReaderSessionPreferenceCache.get("series", 104 + ReaderSessionPreferenceTtlMillis))
        } finally { ReaderSessionPreferenceCache.clear(directory); directory.deleteRecursively() }
    }

    @Test fun oldBooleanDirectionCacheStillLoadsWithoutNovelOverride() = runBlocking {
        val directory = Files.createTempDirectory("novel-cache").toFile()
        try {
            ReaderSessionPreferenceCache.clear(directory)
            directory.resolve("reader_session_preferences.json").writeText(
                """[{"key":"legacy","rightToLeft":false,"invertMode":"Smart","touchedAtMillis":100}]""")
            ReaderSessionPreferenceCache.clearMemoryForTest()
            ReaderSessionPreferenceCache.load(directory, 101)
            assertEquals(ReaderSessionPreferences(ReaderReadingDirection.LeftToRight, InvertMode.Smart),
                ReaderSessionPreferenceCache.get("legacy", 102))
            assertNull(ReaderSessionPreferenceCache.getNovel("legacy", 102))
        } finally { ReaderSessionPreferenceCache.clear(directory); directory.deleteRecursively() }
    }
}
