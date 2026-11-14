package li.mof.kamigura.reader

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReaderJumpBackTest {
    private fun page(index: Int) = ReaderJumpLocation(index)

    @Test fun onlyAnExplicitJumpInEitherDirectionOffersTheFurthestOrdinaryPosition() {
        val state = ReaderJumpBackState()
        state.opened(page(120), ReaderJumpOpening.Resume)
        state.turned(page(120), page(122))
        state.turned(page(122), page(121))
        assertNull(state.target(page(121)))
        for (target in listOf(4, 7, 299, 122, 6)) {
            state.jumped(page(target))
            assertEquals(page(122).takeIf { target != 122 }, state.target(page(target)))
        }
    }

    @Test fun returningTogglesWithoutTurningTheLookupIntoTheReadingPosition() {
        val state = ReaderJumpBackState(ReaderJumpBackRecord(page(122)))
        state.jumped(page(4))
        state.returned(page(4), page(122))
        assertEquals(page(4), state.target(page(122)))
        state.returned(page(122), page(4))
        assertEquals(page(122), state.target(page(4)))
        state.turned(page(4), page(5))
        assertNull(state.target(page(5)))
        state.jumped(page(3))
        assertEquals(page(122), state.target(page(3)))
        state.turned(page(3), page(123))
        state.jumped(page(3))
        assertEquals(page(123), state.target(page(3)))
    }

    @Test fun turningFromAPeekAheadCommitsItEvenWhenTurningBackOntoTheOldMark() {
        for ((landing, nextMark) in listOf(11 to 12, 12 to 12, 13 to 13)) {
            val state = ReaderJumpBackState(ReaderJumpBackRecord(page(11)))
            state.jumped(page(12))
            state.turned(page(12), page(landing))
            assertNull(state.target(page(landing)))
            state.jumped(page(2))
            assertEquals(page(nextMark), state.target(page(2)))
        }
    }

    @Test fun turningBehindTheMarkKeepsAnActiveLookupUntilReachingTheMark() {
        val state = ReaderJumpBackState(ReaderJumpBackRecord(page(12)))
        state.jumped(page(4))
        state.turned(page(4), page(5))
        state.turned(page(5), page(4))
        assertEquals(page(12), state.target(page(4)))
        state.turned(page(10), page(12))
        state.turned(page(12), page(10))
        assertNull(state.target(page(10)))
    }

    @Test fun aForwardLookupReopensWithTheSameMarkAndStillSupportsTheReciprocalReturn() {
        var record = ReaderJumpBackRecord(page(11))
        ReaderJumpBackState(record) { record = it }.jumped(page(12))
        val encoded = Json.encodeToString(record)
        assertTrue(encoded.contains("\"lookingBack\":true"))
        val state = ReaderJumpBackState(Json.decodeFromString<ReaderJumpBackRecord>(encoded))
        state.opened(page(12), ReaderJumpOpening.Resume)
        assertEquals(page(11), state.target(page(12)))
        state.returned(page(12), page(11))
        assertEquals(page(12), state.target(page(11)))
        state.returned(page(11), page(12))
        state.turned(page(12), page(11))
        assertNull(state.target(page(11)))
        state.jumped(page(2))
        assertEquals(page(12), state.target(page(2)))
    }

    @Test fun externalBookmarkAndRestoreNeverSeedFromTheirDestination() {
        val fresh = ReaderJumpBackState()
        fresh.opened(page(299), ReaderJumpOpening.Jump)
        fresh.jumped(page(4))
        assertNull(fresh.target(page(4)))
        val cached = ReaderJumpBackState(ReaderJumpBackRecord(page(122)))
        cached.opened(page(4), ReaderJumpOpening.Jump)
        assertEquals(page(122), cached.target(page(4)))
        cached.reset()
        cached.opened(page(299), ReaderJumpOpening.Restore)
        cached.jumped(page(4))
        assertNull(cached.target(page(4)))
    }

    @Test fun textPositionSurvivesRepaginationAndBeatsTheScreenIndex() {
        val earlier = ReaderJumpLocation(2, 5, "//body/p[1]", ReaderJumpTextPosition(0, 20))
        val later = ReaderJumpLocation(2, 1, "//body/p[1]", ReaderJumpTextPosition(0, 120))
        val state = ReaderJumpBackState()
        state.opened(later, ReaderJumpOpening.Resume)
        state.jumped(earlier)
        assertEquals(later, state.target(earlier))
        assertNull(state.target(later.copy(screen = 9)))
        state.returned(earlier, later)
        assertEquals(earlier, state.target(later))
    }

    @Test fun spreadCorrectionDoesNotAdvanceTheFurthestPosition() {
        val state = ReaderJumpBackState(ReaderJumpBackRecord(page(12)))
        state.jumped(page(4))
        state.returned(page(4), page(12))
        state.shifted()
        assertNull(state.target(page(13)))
        state.jumped(page(0))
        assertEquals(page(12), state.target(page(0)))
    }

    @Test fun chapterHistoryPersistsSeparatelyAndExpiresAfterThirtyDays() = runBlocking {
        val directory = Files.createTempDirectory("reader-jump-back").toFile()
        val origin = ReaderJumpLocation(2, 4, "//body/p[1]", ReaderJumpTextPosition(0, 121))
        try {
            ReaderSessionPreferenceCache.clear(directory)
            val key = "account:series:1:chapter:1:jump-back"
            val initialTime = 100L
            val record = ReaderJumpBackRecord(origin, lookingUp = true)
            ReaderSessionPreferenceCache.putJumpBack(key, record, initialTime)
            ReaderSessionPreferenceCache.persist(directory)
            ReaderSessionPreferenceCache.clearMemoryForTest()
            ReaderSessionPreferenceCache.load(directory, initialTime + 1)
            assertNull(ReaderSessionPreferenceCache.getJumpBack("account:series:1:chapter:2:jump-back", initialTime + 1))
            assertNull(ReaderSessionPreferenceCache.getJumpBack("another:series:1:chapter:1:jump-back", initialTime + 1))
            val restored = ReaderSessionPreferenceCache.getJumpBack(key, initialTime + 1)
            assertEquals(record, restored)
            val state = ReaderJumpBackState(restored)
            state.opened(page(0), ReaderJumpOpening.Resume)
            assertEquals(origin, state.target(page(0)))
            assertNull(ReaderSessionPreferenceCache.getJumpBack(key, initialTime + 1 + ReaderSessionPreferenceTtlMillis))
        } finally {
            ReaderSessionPreferenceCache.clear(directory)
            ReaderSessionPreferenceCache.clearMemoryForTest()
            directory.delete()
        }
    }
}
