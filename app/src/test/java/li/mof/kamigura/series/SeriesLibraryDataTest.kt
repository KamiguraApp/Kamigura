package li.mof.kamigura.series

import kotlinx.coroutines.runBlocking
import li.mof.kamigura.SeriesDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesLibraryDataTest {
    @Test
    fun loadPagedSeriesContinuesUntilShortPage() = runBlocking {
        val pages = listOf(
            listOf(series(1), series(2)),
            listOf(series(3), series(4)),
            listOf(series(5))
        )
        val requests = mutableListOf<Pair<Int, Int>>()

        val result = loadPagedSeries(pageSize = 2) { pageNumber, pageSize ->
            requests += pageNumber to pageSize
            pages.getOrElse(pageNumber) { emptyList() }
        }

        assertEquals(listOf(1, 2, 3, 4, 5), result.map { it.id })
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), requests)
    }

    @Test
    fun loadPagedSeriesStopsAfterFirstShortPage() = runBlocking {
        val requests = mutableListOf<Int>()

        val result = loadPagedSeries(pageSize = 3) { pageNumber, _ ->
            requests += pageNumber
            listOf(series(1), series(2))
        }

        assertEquals(listOf(1, 2), result.map { it.id })
        assertEquals(listOf(0), requests)
    }

    @Test
    fun normalizeSeriesSearchQueryTrimsAsciiAndFullWidthSpaces() {
        assertEquals("A  B", normalizeSeriesSearchQuery("  \u3000A  B\u3000 "))
    }

    @Test
    fun matchesSeriesTitleChecksAllKnownNamesIgnoringCase() {
        val item = SeriesDto(
            id = 1,
            name = "Display",
            localizedName = "駅で見た可愛い女の子",
            originalName = "Another Title"
        )

        assertTrue(item.matchesSeriesTitle("駅で見た"))
        assertTrue(item.matchesSeriesTitle("another"))
        assertFalse(item.matchesSeriesTitle("missing"))
    }

    @Test
    fun librarySeriesFilterUsesKavitaLibraryField() {
        val filter = librarySeriesFilter(35)

        assertEquals(1, filter.statements.size)
        assertEquals(0, filter.statements.single().comparison)
        assertEquals(19, filter.statements.single().field)
        assertEquals("35", filter.statements.single().value)
    }

    private fun series(id: Int): SeriesDto {
        return SeriesDto(id = id, name = "Series $id")
    }
}
