package li.mof.kamigura.library

import kotlinx.serialization.json.JsonPrimitive
import li.mof.kamigura.BookmarkDto
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.VolumeDto
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkEntriesTest {
    // Chapter 2 was scanned first, so its database id is lower than chapter 1's.
    private val volumes = listOf(
        VolumeDto(id = 1, name = "1", chapters = listOf(ChapterDto(id = 52, number = JsonPrimitive("1")))),
        VolumeDto(id = 2, name = "2", chapters = listOf(ChapterDto(id = 51, number = JsonPrimitive("2"))))
    )

    private fun bookmark(id: Int, chapterId: Int, page: Int, seriesId: Int = 7) =
        BookmarkDto(id = id, page = page, seriesId = seriesId, chapterId = chapterId)

    @Test
    fun imageIndexFollowsCreationOrderWithinEachSeries() {
        val entries = bookmarkEntries(
            listOf(bookmark(30, 51, 200), bookmark(10, 52, 5), bookmark(40, 99, 3, seriesId = 8), bookmark(20, 52, 50)),
            mapOf(7 to volumes)
        )
        val indexById = entries.associate { it.bookmark.id to it.imageIndex }
        assertEquals(mapOf(10 to 0, 20 to 1, 30 to 2, 40 to 0), indexById)
    }

    @Test
    fun ordersByReadingOrderAndLabelsWithTheChapterNotItsId() {
        val entries = bookmarkEntries(
            listOf(bookmark(30, 51, 200), bookmark(20, 52, 50), bookmark(10, 52, 5)),
            mapOf(7 to volumes)
        )
        assertEquals(listOf(10, 20, 30), entries.map { it.bookmark.id })
        assertEquals(listOf("1 • Vol 1", "1 • Vol 1", "2 • Vol 2"), entries.map { it.chapterLabel })
    }

    @Test
    fun seriesWhoseChaptersFailedToLoadHaveNoLabel() {
        val entries = bookmarkEntries(listOf(bookmark(1, 51, 0)), emptyMap())
        assertEquals(listOf(null), entries.map { it.chapterLabel })
    }
}
