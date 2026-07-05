package li.mof.kamigura.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTransitionTest {
    @Test
    fun physicalDirectionMatchesRtlAndLtrReading() {
        assertEquals(1f, readerTurnPhysicalSign(true, ReaderTurnDirection.Next))
        assertEquals(-1f, readerTurnPhysicalSign(true, ReaderTurnDirection.Previous))
        assertEquals(-1f, readerTurnPhysicalSign(false, ReaderTurnDirection.Next))
        assertEquals(1f, readerTurnPhysicalSign(false, ReaderTurnDirection.Previous))
    }

    @Test
    fun dragDirectionMatchesExistingReaderSemantics() {
        assertEquals(ReaderTurnDirection.Next, readerTurnForDrag(100f, true))
        assertEquals(ReaderTurnDirection.Previous, readerTurnForDrag(-100f, true))
        assertEquals(ReaderTurnDirection.Next, readerTurnForDrag(-100f, false))
        assertEquals(ReaderTurnDirection.Previous, readerTurnForDrag(100f, false))
    }

    @Test
    fun progressAndCommitAreBounded() {
        assertEquals(0.5f, readerTurnProgress(500f, 1000f), 0.001f)
        assertEquals(1f, readerTurnProgress(1200f, 1000f), 0.001f)
        assertFalse(shouldCommitReaderTurn(0.119f))
        assertTrue(shouldCommitReaderTurn(0.12f))
    }

    @Test
    fun lockedProgressOnlyCountsDragInInitialDirection() {
        assertEquals(
            0.5f,
            readerLockedTurnProgress(
                dragX = 500f,
                rightToLeft = true,
                direction = ReaderTurnDirection.Next,
                visualDistancePx = 1000f
            ),
            0.001f
        )
        assertEquals(
            0f,
            readerLockedTurnProgress(
                dragX = -500f,
                rightToLeft = true,
                direction = ReaderTurnDirection.Next,
                visualDistancePx = 1000f
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            readerLockedTurnProgress(
                dragX = -500f,
                rightToLeft = false,
                direction = ReaderTurnDirection.Next,
                visualDistancePx = 1000f
            ),
            0.001f
        )
    }

    @Test
    fun flingCommitFollowsPhysicalReadingDirection() {
        assertFalse(
            shouldCommitReaderTurn(
                progress = 0f,
                velocityX = 5_000f,
                direction = ReaderTurnDirection.Next,
                rightToLeft = true,
                minimumFlingVelocity = 500f
            )
        )
        assertFalse(
            shouldCommitReaderTurn(
                progress = 0.039f,
                velocityX = 5_000f,
                direction = ReaderTurnDirection.Next,
                rightToLeft = true,
                minimumFlingVelocity = 500f
            )
        )
        assertTrue(
            shouldCommitReaderTurn(
                progress = 0.05f,
                velocityX = 600f,
                direction = ReaderTurnDirection.Next,
                rightToLeft = true,
                minimumFlingVelocity = 500f
            )
        )
        assertFalse(
            shouldCommitReaderTurn(
                progress = 0.05f,
                velocityX = -600f,
                direction = ReaderTurnDirection.Next,
                rightToLeft = true,
                minimumFlingVelocity = 500f
            )
        )
        assertTrue(
            shouldCommitReaderTurn(
                progress = 0.05f,
                velocityX = -600f,
                direction = ReaderTurnDirection.Next,
                rightToLeft = false,
                minimumFlingVelocity = 500f
            )
        )
    }
}
