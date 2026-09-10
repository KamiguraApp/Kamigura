package li.mof.kamigura.library

import li.mof.kamigura.SeriesDto
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

    private fun series(id: Int) = SeriesDto(id = id, name = "Series $id")
}
