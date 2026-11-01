package li.mof.kamigura.reader

import java.io.File
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.InvertMode
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.reader.novel.NovelReaderOverrides
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderSmartThresholdTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun v029EntriesWithoutThresholdKeepDirectionInvertAndNovelOverrides() = withCache { directory ->
        directory.resolve("reader_session_preferences.json").writeText("""[
            {"key":"old","readingDirection":"Vertical","invertMode":"Smart","touchedAtMillis":100,
             "novelWritingStyle":1,"novelFontSize":150},
            {"key":"legacy","rightToLeft":false,"invertMode":"Always","touchedAtMillis":100}]
        """)
        ReaderSessionPreferenceCache.load(directory, 101)
        assertEquals(ReaderSessionPreferences(ReaderReadingDirection.Vertical, InvertMode.Smart), ReaderSessionPreferenceCache.get("old", 102))
        assertEquals(NovelReaderOverrides(1, 150), ReaderSessionPreferenceCache.getNovel("old", 102))
        assertEquals(ReaderSessionPreferences(false, InvertMode.Always), ReaderSessionPreferenceCache.get("legacy", 102))
        assertEquals(.2f, resolveReaderInvertWhiteThreshold(.2f, ReaderSessionPreferenceCache.get("old", 102)?.invertWhiteThreshold), 0f)
        assertEquals(.9f, resolveReaderInvertWhiteThreshold(.9f, ReaderSessionPreferenceCache.get("old", 102)?.invertWhiteThreshold), 0f)
    }

    @Test fun thresholdRoundTripsAndExpiresWithTheSeriesEntryAfterThirtyDays() = withCache { directory ->
        val value = ReaderSessionPreferences(ReaderReadingDirection.LeftToRight, InvertMode.Smart, .65f)
        ReaderSessionPreferenceCache.put("series", value, 100)
        ReaderSessionPreferenceCache.putNovel("series", NovelReaderOverrides(fontSize = 160), 100)
        ReaderSessionPreferenceCache.persist(directory)
        ReaderSessionPreferenceCache.clearMemoryForTest()
        ReaderSessionPreferenceCache.load(directory, 101)
        assertEquals(value, ReaderSessionPreferenceCache.get("series", 102))
        assertEquals(.65f, resolveReaderInvertWhiteThreshold(.9f, value.invertWhiteThreshold), 0f)
        assertEquals(NovelReaderOverrides(fontSize = 160), ReaderSessionPreferenceCache.getNovel("series", 102))
        assertNull(ReaderSessionPreferenceCache.get("series", 102 + ReaderSessionPreferenceTtlMillis))
        assertEquals(.9f, resolveReaderInvertWhiteThreshold(.9f, ReaderSessionPreferenceCache.get("series", 102 + ReaderSessionPreferenceTtlMillis)?.invertWhiteThreshold), 0f)
    }

    @Test fun changingDirectionOrInvertDoesNotTurnAFallbackIntoAThresholdOverride() {
        val fallback = ReaderSessionPreferences(ReaderReadingDirection.RightToLeft, InvertMode.Smart)
        val changed = fallback.copy(readingDirection = ReaderReadingDirection.Vertical, invertMode = InvertMode.Off)
        assertNull(changed.invertWhiteThreshold)
        assertEquals(.2f, resolveReaderInvertWhiteThreshold(.2f, changed.invertWhiteThreshold), 0f)
        assertEquals(.9f, resolveReaderInvertWhiteThreshold(.9f, changed.invertWhiteThreshold), 0f)
        val override = fallback.copy(invertWhiteThreshold = .4f)
            .copy(readingDirection = ReaderReadingDirection.LeftToRight, invertMode = InvertMode.Always)
        assertEquals(.4f, resolveReaderInvertWhiteThreshold(.9f, override.invertWhiteThreshold), 0f)
    }

    @Test fun invalidStoredThresholdFallsBackWithoutDiscardingOtherPreferences() = withCache { directory ->
        directory.resolve("reader_session_preferences.json").writeText("""[
            {"key":"low","readingDirection":"LeftToRight","invertMode":"Smart","invertWhiteThreshold":0.19,"touchedAtMillis":100},
            {"key":"high","readingDirection":"Vertical","invertMode":"Always","invertWhiteThreshold":0.91,"touchedAtMillis":100}]
        """)
        ReaderSessionPreferenceCache.load(directory, 101)
        assertEquals(ReaderSessionPreferences(ReaderReadingDirection.LeftToRight, InvertMode.Smart), ReaderSessionPreferenceCache.get("low", 102))
        assertEquals(ReaderSessionPreferences(ReaderReadingDirection.Vertical, InvertMode.Always), ReaderSessionPreferenceCache.get("high", 102))
        for (value in listOf(.19f, .91f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(.82f, resolveReaderInvertWhiteThreshold(.82f, value), 0f)
        for (value in listOf(.2f, .9f)) assertEquals(value, resolveReaderInvertWhiteThreshold(.82f, value), 0f)
    }

    private fun withCache(block: suspend (File) -> Unit) = runBlocking {
        ReaderSessionPreferenceCache.clearMemoryForTest()
        try { block(temporary.newFolder()) } finally { ReaderSessionPreferenceCache.clearMemoryForTest() }
    }
}
