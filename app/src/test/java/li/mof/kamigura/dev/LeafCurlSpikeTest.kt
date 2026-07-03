package li.mof.kamigura.dev

import org.junit.Assert.assertEquals
import org.junit.Test

class LeafCurlSpikeTest {
    @Test
    fun backFaceIndexMatchesIncomingLeafForForwardAndBackwardSpreadTurns() {
        assertEquals(2, leafCurlSpikeBackPageIndex(current = 0, forward = true))
        assertEquals(6, leafCurlSpikeBackPageIndex(current = 2, forward = true))
        assertEquals(1, leafCurlSpikeBackPageIndex(current = 1, forward = false))
        assertEquals(5, leafCurlSpikeBackPageIndex(current = 3, forward = false))
    }
}
