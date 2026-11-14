package li.mof.kamigura.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.reader.internal.readerChapterEntry
import li.mof.kamigura.reader.internal.readerMenuIssueLabel
import li.mof.kamigura.reader.internal.readerMenuPosition
import li.mof.kamigura.reader.internal.readerMenuSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderMenuTextTest {
    @Test fun issueOnly() = assertSubtitle(null, "1", "#1 \u2022 p. 8 / 181")

    @Test fun volumeOnly() = assertSubtitle("1", "-100000", "Vol. 1 \u2022 p. 8 / 181")

    @Test fun volumeAndIssue() = assertSubtitle("1", "3", "Vol. 1 \u2022 #3 \u2022 p. 8 / 181")

    @Test fun namedChapter() = assertSubtitle("1", "\u756a\u5916\u7de8", "Vol. 1 \u2022 \u756a\u5916\u7de8 \u2022 p. 8 / 181")

    @Test fun noRealNumber() {
        assertSubtitle("0", "-100000", "p. 8 / 181")
        assertSubtitle("100000", "100000", "p. 8 / 181")
        assertSubtitle(null, null, "p. 8 / 181")
    }

    @Test fun novelOnlyAddsScreenPositionWhenThereIsMoreThanOne() {
        assertEquals("p. 8 / 184", readerMenuPosition(7, 184, 0, 1))
        assertEquals("p. 8 / 184 \u2022 screen 1 / 31", readerMenuPosition(7, 184, 0, 31))
    }

    @Test fun newDownloadsKeepTheFullLabelAndLegacyDownloadsUseWhatTheyHave() {
        val record = OfflineIssueRecord("server", 1, 1, 1, 1, "Series", "Volume 1", 1, "archive")
        val fullLabel = entry("1", "3").menuIssueLabel
        val saved = record.copy(menuIssueLabel = fullLabel)
        assertEquals(fullLabel, Json.decodeFromString<OfflineIssueRecord>(Json.encodeToString(saved)).readerMenuIssueLabel())
        assertEquals("Vol. 1", record.readerMenuIssueLabel())
        assertEquals("#3", record.copy(issueName = "3").readerMenuIssueLabel())
        assertEquals("", record.copy(issueName = "Issue").readerMenuIssueLabel())
        assertEquals("", record.copy(issueName = "100000").readerMenuIssueLabel())
        assertEquals("", record.copy(menuIssueLabel = "").readerMenuIssueLabel())
    }

    private fun assertSubtitle(volume: String?, chapter: String?, expected: String) {
        assertEquals(expected, readerMenuSubtitle(entry(volume, chapter).menuIssueLabel, readerMenuPosition(7, 181)))
    }

    private fun entry(volume: String?, chapter: String?) = checkNotNull(readerChapterEntry(
        listOf(VolumeDto(1, number = volume?.let(::JsonPrimitive), chapters = listOf(ChapterDto(1, title = chapter)))),
        1
    ))
}
