package li.mof.kamigura.reader

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.InvertMode
import li.mof.kamigura.PageBackground
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
    @Test fun defaultsAndDefaultKindProfileApplyToBooks() {
        assertEquals(NovelReaderPreferences(automaticWritingStyle = true), resolveNovelReaderPreferences(null, null))
        val resolved = resolveNovelReaderPreferences(UserReadingProfileDto(kind = 0,
            bookReaderWritingStyle = 1, bookReaderFontSize = 120, bookReaderLineSpacing = 150,
            bookReaderMargin = 20, bookReaderReadingDirection = 0), null)
        assertFalse(resolved.vertical)
        assertTrue(resolved.automaticWritingStyle)
        assertFalse(resolved.rightToLeft)
        assertEquals(120, resolved.fontSize)
        assertEquals(150, resolved.lineSpacing)
        assertEquals(20, resolved.margin)
    }

    @Test fun manualOverridesLeaveSpacingMarginAndHorizontalDirectionOnProfile() {
        val profile = UserReadingProfileDto(bookReaderWritingStyle = 1, bookReaderFontSize = 100,
            bookReaderLineSpacing = 125, bookReaderMargin = 10, bookReaderReadingDirection = 1)
        val resolved = resolveNovelReaderPreferences(profile, NovelReaderOverrides(0, 160))
        assertFalse(resolved.vertical)
        assertTrue(resolved.rightToLeft)
        assertEquals(160, resolved.fontSize)
        assertEquals(125, resolved.lineSpacing)
        assertEquals(10, resolved.margin)
        assertFalse(resolveNovelReaderPreferences(profile.copy(bookReaderReadingDirection = 0), NovelReaderOverrides(0)).rightToLeft)
    }

    @Test fun invalidValuesUseSafeFallbacks() {
        val resolved = resolveNovelReaderPreferences(UserReadingProfileDto(bookReaderWritingStyle = -1,
            bookReaderFontSize = 0, bookReaderLineSpacing = -1, bookReaderMargin = 100,
            bookReaderReadingDirection = 99), NovelReaderOverrides(99, -20))
        assertEquals(NovelReaderPreferences(margin = 45, automaticWritingStyle = true), resolved)
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
