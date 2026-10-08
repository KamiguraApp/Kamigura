package li.mof.kamigura.library

import kotlinx.coroutines.runBlocking
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.series.loadPagedSeries
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPagingTest {
    @Test
    fun appendDistinct_preservesExistingOrderAndDropsRepeatedIds() {
        val existing = listOf(SeriesDto(1, "Zebra"), SeriesDto(2, "The Apple"))
        val nextPage = listOf(SeriesDto(2, "Changed title"), SeriesDto(3, "Apricot"), series(3))

        val result = existing.appendDistinct(nextPage)

        assertEquals(listOf(1, 2, 3), result.map { it.id })
        assertEquals(listOf("Zebra", "The Apple", "Apricot"), result.map { it.name })
    }

    @Test
    fun loadPagedSeries_dropsSeriesRepeatedAcrossPageBoundaries() = runBlocking {
        // A tie in the server's sort order can hand back the same row on the next page.
        val pages = listOf(
            listOf(series(1), series(2)),
            listOf(series(2), series(3)),
            listOf(series(4))
        )

        val result = loadPagedSeries(pageSize = 2) { pageNumber, _ -> pages[pageNumber] }

        assertEquals(listOf(1, 2, 3, 4), result.map { it.id })
    }

    @Test
    fun loadPagedSeries_keepsTheFirstCopyOfADuplicate() = runBlocking {
        val pages = listOf(
            listOf(SeriesDto(1, "First"), series(2)),
            listOf(SeriesDto(1, "Second"))
        )

        val result = loadPagedSeries(pageSize = 2) { pageNumber, _ -> pages[pageNumber] }

        assertEquals(listOf("First", "Series 2"), result.map { it.name })
    }

    private fun series(id: Int) = SeriesDto(id = id, name = "Series $id")
}
