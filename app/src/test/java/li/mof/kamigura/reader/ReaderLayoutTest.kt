package li.mof.kamigura.reader

import li.mof.kamigura.FileDimensionDto
import li.mof.kamigura.reader.internal.readerJumpLandingPage
import li.mof.kamigura.reader.internal.readerPageLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLayoutTest {

    private fun wideAt(vararg pages: Int): Map<Int, FileDimensionDto> =
        pages.associateWith { page ->
            FileDimensionDto(width = 2000, height = 1400, pageNumber = page, isWide = true)
        }

    @Test
    fun previousStepIsFullSpreadInAPlainChain() {
        val layout = readerPageLayout(page = 6, pageCount = 30, portrait = false, pageDimensions = emptyMap())

        assertEquals(2, layout.previousStep)
    }

    @Test
    fun previousStepDoesNotSkipTheLonePageAfterAWidePage() {
        // Shifted chain: wide P4 alone, then [6,7]. Stepping back two would land on the
        // wide page and P5 would never be shown; step one instead.
        val layout = readerPageLayout(page = 6, pageCount = 30, portrait = false, pageDimensions = wideAt(3, 4))

        assertEquals(1, layout.previousStep)
    }

    @Test
    fun previousStepDoesNotSkipTheLonePageAfterTheCover() {
        // Shifted chain near the front: cover alone, then [2,3]. Stepping back two would
        // land on the cover and P1 would never be shown; step one instead.
        val layout = readerPageLayout(page = 2, pageCount = 30, portrait = false, pageDimensions = emptyMap())

        assertEquals(1, layout.previousStep)
    }

    @Test
    fun jumpsFollowForwardTurnsAcrossAWidePageAndKeepSingles() {
        val dimensions = wideAt(4)
        fun land(target: Int) = readerJumpLandingPage(1, target, 12, false, dimensions)

        assertEquals(1, land(2))
        assertEquals(3, land(3)) // The lone page before the wide page.
        assertEquals(4, land(4))
        assertEquals(5, land(6))
        assertEquals(11, land(11))
    }

    @Test
    fun backwardsJumpsFollowTheCurrentShiftAndDoNotSkipCoverOrWidePages() {
        val dimensions = wideAt(4)
        fun land(target: Int) = readerJumpLandingPage(8, target, 14, false, dimensions)

        assertEquals(8, land(9))
        assertEquals(6, land(7))
        assertEquals(5, land(5))
        assertEquals(4, land(4))
        assertEquals(1, land(2))
        assertEquals(0, land(0))
        assertEquals(10, land(11))
    }

    @Test
    fun bookmarkLandingFromTheCoverAndSinglePageModeUseTheSameRule() {
        val dimensions = wideAt(1)
        for ((target, origin) in listOf(7 to 6, 8 to 8, 12 to 12, 16 to 16)) {
            assertEquals(origin, readerJumpLandingPage(0, target, 181, false, dimensions))
            assertEquals(target, readerJumpLandingPage(0, target, 181, true, dimensions))
        }
    }
}
