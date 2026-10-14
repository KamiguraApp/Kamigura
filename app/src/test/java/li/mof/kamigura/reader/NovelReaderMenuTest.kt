package li.mof.kamigura.reader

import li.mof.kamigura.reader.novel.novelCompletionPercent
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelReaderMenuTest {
    @Test fun progressUsesZeroBasedBookPagesAndRefinesWithinThePage() {
        assertEquals(0, novelCompletionPercent(0, 21, 0, 23))
        assertEquals(71, novelCompletionPercent(15, 21, 0, 23))
        assertEquals(73, novelCompletionPercent(15, 21, 10, 23))
        assertEquals(99, novelCompletionPercent(20, 21, 22, 23))
    }

    @Test fun invalidAndSinglePageCountsStaySafe() {
        assertEquals(0, novelCompletionPercent(-1, 0, -1, 0))
        assertEquals(50, novelCompletionPercent(0, 1, 1, 2))
        assertEquals(99, novelCompletionPercent(100, 21, 100, 23))
    }
}
