package li.mof.kamigura.library

import kotlinx.serialization.json.Json
import li.mof.kamigura.ReadingListItemDto
import org.junit.Assert.*
import org.junit.Test

class ReadingListItemTest {
    @Test
    fun decodesChapterQueueWithoutCollapsingSeriesOrSortingNames() {
        val items = Json.decodeFromString<List<ReadingListItemDto>>("""[
            {"id":30,"order":0,"chapterId":301,"volumeId":21,"seriesId":2,"libraryId":7,
             "seriesName":"Zebra","volumeNumber":"2","chapterNumber":"3.5",
             "chapterTitleName":"A chapter","title":"Title","pagesRead":4,"pagesTotal":26,
             "isSpecial":false,"seriesFormat":4},
            {"id":10,"order":1,"chapterId":101,"volumeId":11,"seriesId":1,"libraryId":8,
             "seriesName":"Apple","isSpecial":true,"title":"Extra","seriesFormat":3},
            {"id":31,"order":2,"chapterId":301,"volumeId":21,"seriesId":2,"libraryId":7,
             "seriesName":"Zebra","seriesFormat":4}
        ]""")
        assertEquals(listOf(30, 10, 31), items.map { it.id })
        assertEquals(listOf(301, 101, 301), items.map { it.chapterId })
        assertEquals(listOf(21, 11, 21), items.map { it.volumeId })
        assertEquals(listOf(7, 8, 7), items.map { it.libraryId })
        assertEquals(listOf(4, 3, 4), items.map { it.seriesFormat })
        assertEquals("Volume 2 | Chapter 3.5 | A chapter", items[0].readingListChapterLabel())
        assertEquals("Special | Extra", items[1].readingListChapterLabel())
        assertEquals(4, items[0].pagesRead)
        assertEquals(26, items[0].pagesTotal)
    }

    @Test
    fun missingLabelsStillIdentifyTheChapterAndSentinelsStayHidden() {
        val item = ReadingListItemDto(chapterId = 42, volumeId = 1, seriesId = 1, libraryId = 1,
            volumeNumber = "0", chapterNumber = "-100000", chapterTitleName = " ", title = "")
        assertEquals("Chapter 42", item.readingListChapterLabel())
    }
}
