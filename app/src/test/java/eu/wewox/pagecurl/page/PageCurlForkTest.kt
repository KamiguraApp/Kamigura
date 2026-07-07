package eu.wewox.pagecurl.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig.DragInteraction.PointerBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalPageCurlApi::class)
class PageCurlForkTest {
    @Test
    fun mirrorXRoundTripsEdgeAndOffset() {
        val size = IntSize(width = 1000, height = 1400)
        val offset = Offset(125f, 300f)
        val edge = Edge(top = Offset(250f, 0f), bottom = Offset(400f, 1400f))

        assertEquals(offset, offset.mirrorX(size).mirrorX(size))
        assertEquals(edge, edge.mirrorX(size).mirrorX(size))
    }

    @Test
    fun clampLineXKeepsTopAndBottomIntersectionsOnAllowedSideOfSpine() {
        val size = IntSize(width = 1000, height = 1200)
        val edge = Edge(top = Offset(300f, -100f), bottom = Offset(460f, 1300f))

        val clamped = edge.clampLineX(500f..1000f, size)

        assertEquals(0f, clamped.top.y, 0.001f)
        assertEquals(1200f, clamped.bottom.y, 0.001f)
        assertTrue(clamped.top.x >= 500f)
        assertTrue(clamped.bottom.x >= 500f)
    }

    @Test
    fun clampLineXHandlesNearlyHorizontalCreaseWithoutCrossingRange() {
        val size = IntSize(width = 1000, height = 1200)
        val edge = Edge(top = Offset(-100f, 600f), bottom = Offset(1200f, 600.0005f))

        val clamped = edge.clampLineX(0f..500f, size)

        assertEquals(0f, clamped.top.x, 0.001f)
        assertEquals(500f, clamped.bottom.x, 0.001f)
    }

    @Test
    fun quietMiddleUsesTapHalfAndMirrorsBackwardPose() {
        val size = Size(width = 1000f, height = 1600f)

        val topTap = quietMiddle(size, tapPosition = Offset(800f, 200f), mirrored = false)
        val bottomTap = quietMiddle(size, tapPosition = Offset(800f, 1200f), mirrored = false)
        val mirroredTopTap = quietMiddle(size, tapPosition = Offset(200f, 200f), mirrored = true)

        assertEquals(700f, topTap.top.x, 0.001f)
        assertEquals(800f, topTap.bottom.x, 0.001f)
        assertEquals(800f, bottomTap.top.x, 0.001f)
        assertEquals(700f, bottomTap.bottom.x, 0.001f)
        assertEquals(300f, mirroredTopTap.top.x, 0.001f)
        assertEquals(200f, mirroredTopTap.bottom.x, 0.001f)
    }

    @Test
    fun interactiveCurlEdgeMatchesForwardDefaultCreator() {
        val size = IntSize(width = 1000, height = 1200)

        val edge = interactiveCurlEdgeForPointer(
            direction = PageCurlTurnDirection.Forward,
            pointerBehavior = PointerBehavior.Default,
            startOffset = Offset(900f, 900f),
            currentOffset = Offset(500f, 700f),
            size = size,
            leafTurn = false,
            forwardEndX = 0f,
            backwardEndX = 1000f
        )

        assertEquals(700f, edge.top.x, 0.001f)
        assertEquals(200f, edge.top.y, 0.001f)
        assertEquals(300f, edge.bottom.x, 0.001f)
        assertEquals(1200f, edge.bottom.y, 0.001f)
    }

    @Test
    fun interactiveCurlEdgeMirrorsAndClampsLeafBackward() {
        val size = IntSize(width = 1000, height = 1200)

        val edge = interactiveCurlEdgeForPointer(
            direction = PageCurlTurnDirection.Backward,
            pointerBehavior = PointerBehavior.Default,
            startOffset = Offset(100f, 900f),
            currentOffset = Offset(600f, 700f),
            size = size,
            leafTurn = true,
            forwardEndX = 500f,
            backwardEndX = 500f
        )

        assertTrue(edge.top.x <= 500f)
        assertTrue(edge.bottom.x <= 500f)
    }

    @Test
    fun snapToBeforeSetupDoesNotThrow() = runBlocking {
        // Regression: the host may snap while the composable is not yet mounted (a slide
        // transition still covering it), when max is 0; coerceIn(0, -1) used to throw.
        val state = PageCurlState()

        state.snapTo(1)

        assertEquals(0, state.current)
    }

    @Test
    fun leafTurnProgressNormalizesSpineEndAsCompleteTurn() = runBlocking {
        val state = PageCurlState(initialMax = 5, initialCurrent = 2, turnEndFractionX = 0.5f)
        state.setup(count = 5, constraints = Constraints.fixed(width = 1000, height = 1200))
        val internal = state.internalState!!

        internal.forward.snapTo(Edge(Offset(500f, 0f), Offset(500f, 1200f)))
        assertEquals(1f, state.progress, 0.001f)

        internal.reset()
        internal.backward.snapTo(Edge(Offset(500f, 0f), Offset(500f, 1200f)))
        assertEquals(-1f, state.progress, 0.001f)
    }

    @Test
    fun turnEndFractionControlsForwardTerminalFoldEdge() {
        val spreadState = PageCurlState(initialMax = 5, initialCurrent = 2, turnEndFractionX = 0.5f)
        spreadState.setup(count = 5, constraints = Constraints.fixed(width = 1000, height = 1200))

        assertEquals(500f, spreadState.internalState!!.forwardEndEdge.top.x, 0.001f)
        assertEquals(500f, spreadState.internalState!!.forwardEndEdge.bottom.x, 0.001f)

        val fullPageState = PageCurlState(initialMax = 5, initialCurrent = 2, turnEndFractionX = 0f)
        fullPageState.setup(count = 5, constraints = Constraints.fixed(width = 1000, height = 1200))

        assertEquals(0f, fullPageState.internalState!!.forwardEndEdge.top.x, 0.001f)
        assertEquals(0f, fullPageState.internalState!!.forwardEndEdge.bottom.x, 0.001f)
    }

    @Test
    fun rapidForwardTapCommitsRunningTurnBeforeContinuing() = runBlocking {
        val state = PageCurlState(initialMax = 5, initialCurrent = 0, turnEndFractionX = 0.5f)
        state.setup(count = 5, constraints = Constraints.fixed(width = 1000, height = 1200))

        val first = launch {
            state.next(block = { size ->
                snapTo(Edge(Offset(800f, 0f), Offset(700f, size.height)))
                delay(Long.MAX_VALUE)
            })
        }
        while (state.internalState?.forward?.value == state.internalState?.rightEdge) {
            yield()
        }

        state.next(block = { size ->
            snapTo(Edge(Offset(500f, 0f), Offset(500f, size.height)))
        })
        first.join()

        assertEquals(2, state.current)
        assertEquals(state.internalState?.rightEdge, state.internalState?.forward?.value)
    }
}
