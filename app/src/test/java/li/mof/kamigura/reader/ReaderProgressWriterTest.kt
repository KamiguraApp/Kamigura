package li.mof.kamigura.reader

import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ProgressDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressWriterTest {
    private val fixture = Fixture()

    @Test fun onlyMatureProgressIsFlushedAndTheLatestAnchorWins() = runBlocking {
        val writer = fixture.writer(this)
        writer.submit(fixture.target(1, anchor = "//body/p[3]"))
        fixture.now = 2_900
        writer.submit(fixture.target(2, anchor = "id(\"p8\")"))
        fixture.now = 5_899
        writer.flushMatured()
        assertTrue(fixture.events.isEmpty())
        fixture.now = 5_900
        writer.flushMatured()
        writer.flushMatured()
        assertEquals(listOf("remote:4:2:id(\"p8\")"), fixture.events)
    }

    @Test fun timerWritesWithoutLifecycleFlush() = runBlocking {
        val seen = CompletableDeferred<ProgressDto>()
        val writer = ReaderProgressWriter(false, scope = this, delayMillis = 5, onSaved = { seen.complete(it) })
        val next = fixture.target(1)
        writer.submit(next)
        assertTrue(fixture.events.isEmpty())
        assertEquals(next.progress, withTimeout(1_000) { seen.await() })
    }

    @Test fun immediateExitSupersedesPendingAndSurvivesDetach() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSave = { entered.complete(Unit); release.await() }
        val writer = fixture.writer(this)
        writer.submit(fixture.target(1, anchor = "//body"))
        val closing = checkNotNull(writer.close(fixture.target(3, anchor = "//body/p[5]")))
        entered.await()
        writer.detach(flushMatured = false)
        assertTrue(closing.isActive)
        release.complete(Unit)
        closing.join()
        assertEquals(listOf("remote:4:3://body/p[5]"), fixture.events)
        writer.close(fixture.target(3, anchor = "//body/p[5]"))?.join()
        assertEquals(1, fixture.events.size)
    }

    @Test fun seededPageIsNotWrittenAndDetachDoesNotSendAnImmaturePage() = runBlocking {
        val writer = fixture.writer(this)
        writer.seed(fixture.target(0))
        writer.submit(fixture.target(0))
        fixture.now = 3_000
        writer.flushMatured()
        writer.submit(fixture.target(1))
        writer.detach(flushMatured = true)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun dropsAQueuedStaleRevisionWithoutCancellingAnInFlightWriteOrAnotherChapter() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSave = { if (it.pageNum == 1) { entered.complete(Unit); release.await() } }
        val first = fixture.writer(this)
        val inFlight = checkNotNull(first.close(fixture.target(1)))
        entered.await()
        val stale = checkNotNull(first.close(fixture.target(2)))
        val other = checkNotNull(first.close(fixture.target(0, chapter = 5)))
        val reopened = fixture.writer(this)
        reopened.seed(fixture.target(0))
        val latest = checkNotNull(reopened.close(fixture.target(0)))
        assertTrue(fixture.events.isEmpty())
        release.complete(Unit)
        listOf(inFlight, stale, other, latest).forEach { it.join() }
        assertEquals(listOf("remote:4:1:null", "remote:5:0:null", "remote:4:0:null"), fixture.events)
    }

    @Test fun unchangedReopenDoesNotCancelQueuedCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSave = { if (it.chapterId == 5) { entered.complete(Unit); release.await() } }
        val first = fixture.writer(this)
        val other = checkNotNull(first.close(fixture.target(1, chapter = 5)))
        entered.await()
        val completion = checkNotNull(first.complete(fixture.target(8)))
        val reopened = fixture.writer(this)
        val landing = fixture.target(0)
        reopened.seed(landing)
        reopened.submit(landing)
        reopened.detach(flushMatured = true)
        release.complete(Unit)
        other.join()
        completion.join()
        assertEquals(listOf("remote:5:1:null", "remote:4:8:null", "read:4"), fixture.events)
    }

    @Test fun completeKeepsTheAnchorAndStillMarksIfSavingThePositionFails() = runBlocking {
        val writer = fixture.writer(this)
        writer.submit(fixture.target(1, anchor = "//body/p[2]"))
        val final = fixture.target(5, anchor = "//body/p[30]")
        writer.complete(final)?.join()
        assertEquals(listOf("remote:4:5://body/p[30]", "read:4"), fixture.events)
        fixture.events.clear()
        fixture.beforeSave = { throw IOException() }
        writer.complete(final)?.join()
        assertEquals(listOf("read:4"), fixture.events)
    }

    @Test fun offlineCompleteAndResetOnlyAcknowledgeSuccessfulWrites() = runBlocking {
        val writer = fixture.writer(this)
        val target = fixture.target(5, offline = true)
        fixture.failRead = true
        writer.complete(target)?.join()
        assertEquals(listOf("local:5:true", "remote:4:5:null", "synced:5:false:false"), fixture.events)
        fixture.events.clear()
        fixture.failRead = false
        fixture.beforeSave = { throw IOException() }
        writer.complete(fixture.target(4, offline = true))?.join()
        assertEquals(listOf("local:4:true", "read:4"), fixture.events)
        fixture.events.clear()
        fixture.beforeSave = {}
        writer.complete(target)?.join()
        assertEquals(listOf("local:5:true", "remote:4:5:null", "read:4", "synced:5:true:false"), fixture.events)
        fixture.events.clear()
        fixture.failUnread = true
        writer.reset(target)?.join()
        assertEquals(listOf("localReset:4"), fixture.events)
        fixture.events.clear()
        fixture.failUnread = false
        writer.reset(target)?.join()
        assertEquals(listOf("localReset:4", "unread:4", "synced:0:false:true"), fixture.events)
    }

    @Test fun offlineProgressIsRecordedWithoutAnApiAndBeforeOlderNetworkWriteEnds() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSave = { entered.complete(Unit); release.await() }
        val writer = fixture.writer(this)
        val old = checkNotNull(writer.close(fixture.target(2)))
        entered.await()
        val offline = fixture.target(3, offline = true).copy(api = null)
        writer.recordLocal(offline)
        val close = checkNotNull(writer.close(offline))
        assertEquals(listOf("local:3:false", "local:3:false"), fixture.events)
        release.complete(Unit)
        old.join()
        close.join()
        assertFalse(fixture.events.any { it.startsWith("synced:") })
    }

    @Test fun incognitoNeverWritesOrSynchronizesOfflineQueue() = runBlocking {
        val writer = fixture.writer(this, incognito = true)
        val target = fixture.target(1, offline = true)
        writer.recordLocal(target)
        writer.submit(target)
        writer.close(target)
        writer.complete(target)
        writer.reset(target)
        writer.syncPending { error("Incognito must not sync pending progress") }
        writer.detach(flushMatured = true)
        assertTrue(fixture.events.isEmpty())
    }

    private class Fixture : ReaderLocalProgress {
        val events = mutableListOf<String>()
        var beforeSave: suspend (ProgressDto) -> Unit = {}
        var failRead = false
        var failUnread = false
        var now = 0L
        private val account = java.util.UUID.randomUUID().toString()
        private val session = KavitaSession(baseUrl = "https://books.example")
        private val unimplemented = Proxy.newProxyInstance(
            KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)
        ) { _, method, _ -> error("Unexpected call: ${method.name}") } as KavitaApi
        private val api = object : KavitaApi by unimplemented {
            override suspend fun saveProgress(dto: ProgressDto) {
                beforeSave(dto)
                events += "remote:${dto.chapterId}:${dto.pageNum}:${dto.bookScrollId}"
            }
            override suspend fun markChapterRead(dto: MarkChapterReadDto) {
                if (failRead) throw IOException()
                events += "read:${dto.chapterId}"
            }
            override suspend fun markChaptersUnread(dto: MarkVolumesReadDto) {
                if (failUnread) throw IOException()
                events += "unread:${dto.chapterIds?.single()}"
            }
        }

        fun writer(scope: CoroutineScope, incognito: Boolean = false) =
            ReaderProgressWriter(incognito, this, scope, nowMillis = { now }, logFailure = { _, _ -> })

        fun target(page: Int, anchor: String? = null, offline: Boolean = false, chapter: Int = 4) =
            ReaderProgressTarget(account, ProgressDto(1, 2, 3, chapter, page, anchor), api, session.takeIf { offline })

        override suspend fun record(session: KavitaSession, progress: ProgressDto, markRead: Boolean) {
            events += "local:${progress.pageNum}:$markRead"
        }
        override suspend fun reset(session: KavitaSession, chapterId: Int) {
            events += "localReset:$chapterId"
        }
        override suspend fun synced(session: KavitaSession, progress: ProgressDto, markedRead: Boolean, markedUnread: Boolean) {
            events += "synced:${progress.pageNum}:$markedRead:$markedUnread"
        }
    }
}
