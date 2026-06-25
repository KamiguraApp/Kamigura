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
        assertEquals(0.25f, readerTurnProgress(250f, 1000f), 0.001f)
        assertEquals(1f, readerTurnProgress(2000f, 1000f), 0.001f)
        assertFalse(shouldCommitReaderTurn(0.31f))
        assertTrue(shouldCommitReaderTurn(0.32f))
    }
}
