package li.mof.kamigura.reader

import androidx.compose.ui.geometry.Offset
import li.mof.kamigura.reader.internal.ReaderPageRetryState
import li.mof.kamigura.reader.internal.readerPageRetryTargets
import li.mof.kamigura.reader.internal.retryPageAt
import org.junit.Assert.*
import org.junit.Test

class ReaderPageRetryTest {
    @Test
    fun retryIsPageLocalAndCannotBeStartedTwiceWhileLoading() {
        val state = ReaderPageRetryState()
        state.onFailure(2, 0)
        state.onFailure(3, 0)
        assertTrue(state.retry(2))
        assertFalse(state.retry(2))
        assertEquals(1, state.attempt(2))
        assertEquals(0, state.attempt(3))
        assertTrue(state.failed(3))
        assertTrue(state.needsFreshRequest(2))
        state.onSuccess(2, 1)
        assertFalse(state.needsFreshRequest(2))
    }

    @Test
    fun supersededCallbacksCannotHideOrCreateNewFailures() {
        val state = ReaderPageRetryState()
        state.onFailure(4, 0)
        state.retry(4)
        state.onFailure(4, 1)
        state.onSuccess(4, 0)
        assertTrue(state.failed(4))
        state.onSuccess(4, 1)
        state.onFailure(4, 0)
        assertFalse(state.failed(4))
    }

    @Test
    fun failureStaysUntilRetryOrSuccessIncludingLaterDecodeFailures() {
        val state = ReaderPageRetryState()
        state.onSuccess(0, 0)
        state.onFailure(0, 0)
        assertTrue(state.failed(0))
        state.retry(0)
        state.onFailure(0, 1)
        assertTrue(state.failed(0))
    }

    @Test
    fun remainingVisibleDoesNotLoopButReturningRetries() {
        val state = ReaderPageRetryState()
        state.updateVisiblePages(setOf(1, 2))
        state.onFailure(2, 0)
        state.updateVisiblePages(setOf(1, 2))
        assertEquals(0, state.attempt(2))
        state.updateVisiblePages(setOf(3, 4))
        state.updateVisiblePages(setOf(1, 2))
        assertEquals(1, state.attempt(2))
        assertEquals(0, state.attempt(1))
    }

    @Test
    fun networkRestorationRetriesOnlyFailuresAndDoesNotLoopWhileLoading() {
        val state = ReaderPageRetryState()
        state.onFailure(1, 0)
        state.onFailure(5, 0)
        state.onSuccess(3, 0)
        state.onNetworkRestored()
        state.onNetworkRestored()
        assertEquals(1, state.attempt(1))
        assertEquals(1, state.attempt(5))
        assertEquals(0, state.attempt(3))
    }

    @Test
    fun requestFailingAfterReconnectionGetsOneFreshAttempt() {
        val state = ReaderPageRetryState()
        val startedGeneration = state.networkGeneration
        state.onNetworkRestored()
        state.onFailure(2, 0, startedGeneration)
        assertEquals(1, state.attempt(2))
        state.onFailure(2, 0, startedGeneration)
        assertEquals(1, state.attempt(2))
        state.onFailure(2, 1, state.networkGeneration)
        assertTrue(state.failed(2))
        assertEquals(1, state.attempt(2))
    }

    @Test
    fun spreadHitTargetsFollowPhysicalOrderAndExcludeHealthyPage() {
        val targets = readerPageRetryTargets(listOf(2, 1), { it == 1 }, 1080f, 600f, 112f, 48f)
        assertEquals(1, targets.size)
        assertEquals(1, targets.retryPageAt(Offset(810f, 300f)))
        assertNull(targets.retryPageAt(Offset(270f, 300f)))
        assertNull(targets.retryPageAt(Offset(750f, 300f)))
        assertNull(targets.retryPageAt(Offset(810f, 325f)))
    }

    @Test
    fun singlePageTargetIsCenteredAndClampedToItsSlot() {
        val targets = readerPageRetryTargets(listOf(0), { true }, 80f, 40f, 112f, 48f)
        assertEquals(80f, targets.single().bounds.width, 0f)
        assertEquals(40f, targets.single().bounds.height, 0f)
        assertEquals(0, targets.retryPageAt(Offset(40f, 20f)))
        assertTrue(readerPageRetryTargets(emptyList(), { true }, 80f, 40f, 112f, 48f).isEmpty())
    }
}
