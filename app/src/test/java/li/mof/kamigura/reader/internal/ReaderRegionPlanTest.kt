package li.mof.kamigura.reader.internal

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ReaderRegionPlanTest {
    @Test fun ordinaryScanAtHighZoomUsesOriginalPixelsOnlyInTheVisibleArea() {
        val plan = readerRegionPlan(2340, 3510, Rect(.4f, .4f, .6f, .6f), 2.3f, 4_000_000)!!
        assertEquals(1, plan.sample)
        assertTrue(plan.tiles.all { it.left >= 935 && it.right <= 1405 && it.top >= 1403 && it.bottom <= 2107 })
        assertTrue(plan.tiles.all { it.width <= 1024 && it.height <= 1024 })
    }
    @Test fun artBookNeverAllocatesAWholeSourceBitmapOrOvershootsTheBudget() {
        for (scale in listOf(.08f, .4f, 5f)) {
            val plan = readerRegionPlan(9921, 14031, Rect(0f, 0f, 1f, 1f), scale, 4_000_000)!!
            assertTrue(plan.tiles.size > 1)
            assertTrue(plan.tiles.all { (it.width + plan.sample - 1) / plan.sample <= 1024 &&
                (it.height + plan.sample - 1) / plan.sample <= 1024 })
            val pixels = plan.tiles.sumOf { ((it.width.toLong() + plan.sample - 1) / plan.sample) *
                ((it.height.toLong() + plan.sample - 1) / plan.sample) }
            assertTrue(pixels <= 4_000_000)
        }
    }
    @Test fun boundsAreClippedAndInvalidOrEmptyViewsDoNotDecode() {
        assertNull(readerRegionPlan(1, 1, Rect(2f, 2f, 3f, 3f), 1f, 100))
        assertNull(readerRegionPlan(0, 1, Rect(0f, 0f, 1f, 1f), 1f, 100))
        assertNull(readerRegionPlan(1, 1, Rect(0f, 0f, 1f, 1f), Float.NaN, 100))
        val plan = readerRegionPlan(4000, 6000, Rect(-1f, -.5f, .5f, .5f), 1f, 4_000_000)!!
        assertTrue(plan.tiles.all { it.left >= 0 && it.top >= 0 && it.right <= 2000 && it.bottom <= 3000 })
    }
    @Test fun pixelBudgetArithmeticDoesNotOverflowForExtremeDimensions() {
        val plan = readerRegionPlan(Int.MAX_VALUE, Int.MAX_VALUE, Rect(0f, 0f, 1f, 1f), 5f, 1000)!!
        assertTrue(plan.sample > 1)
        assertTrue(plan.tiles.size <= 1)
    }
}
