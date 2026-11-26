package li.mof.kamigura.reader

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ProgressDto
import li.mof.kamigura.download.OfflineIssueRepository

private const val ReaderProgressSyncDelayMillis = 3_000L
private val ReaderProgressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal data class ReaderProgressTarget(
    val accountKey: String,
    val progress: ProgressDto,
    val api: KavitaApi?,
    val offlineSession: KavitaSession? = null
)

internal interface ReaderLocalProgress {
    suspend fun record(session: KavitaSession, progress: ProgressDto, markRead: Boolean)
    suspend fun reset(session: KavitaSession, chapterId: Int)
    suspend fun synced(session: KavitaSession, progress: ProgressDto, markedRead: Boolean = false, markedUnread: Boolean = false)
}

internal fun OfflineIssueRepository.readerLocalProgress(): ReaderLocalProgress = object : ReaderLocalProgress {
    override suspend fun record(session: KavitaSession, progress: ProgressDto, markRead: Boolean) =
        saveLocalProgress(session, progress.chapterId, progress.pageNum, markRead)

    override suspend fun reset(session: KavitaSession, chapterId: Int) = markLocalUnread(session, chapterId)

    override suspend fun synced(session: KavitaSession, progress: ProgressDto, markedRead: Boolean, markedUnread: Boolean) =
        markProgressSynced(session, progress.chapterId, progress.pageNum, markedRead, markedUnread)
}

private data class ProgressChapter(val account: String, val chapterId: Int)

private class ProgressClock {
    val revision = AtomicLong()
    val accepted = AtomicLong()
    val localWrites = Mutex()
}

private object ProgressOrder {
    val mutex = Mutex()
    private val chapters = mutableMapOf<ProgressChapter, WeakReference<ProgressClock>>()

    // Reopening while an exit is queued must share its clock, without retaining every
    // chapter ever read. A writer and its queued work hold the clock while in use.
    @Synchronized fun clock(chapter: ProgressChapter): ProgressClock {
        chapters[chapter]?.get()?.let { return it }
        chapters.values.removeAll { it.get() == null }
        return ProgressClock().also { chapters[chapter] = WeakReference(it) }
    }
}

internal class ReaderProgressWriter(
    private val incognito: Boolean,
    private val local: ReaderLocalProgress? = null,
    private val scope: CoroutineScope = ReaderProgressScope,
    private val delayMillis: Long = ReaderProgressSyncDelayMillis,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onSaved: (ProgressDto) -> Unit = {},
    private val logFailure: (String, Throwable) -> Unit = { message, error -> KamiguraLog.w(message, error) }
) {
    private data class Saved(val progress: ProgressDto, val accepted: Long)
    private enum class Action { Save, Close, Complete, Reset }
    private data class Write(val target: ReaderProgressTarget, val clock: ProgressClock, val revision: Long)
    private class Pending(val write: Write, val since: Long) {
        var timer: Job? = null
        var running = false
    }

    private val guard = Any()
    private val clocks = mutableMapOf<ProgressChapter, ProgressClock>()
    private val saved = mutableMapOf<ProgressChapter, Saved>()
    private var pending: Pending? = null

    private val ReaderProgressTarget.chapter get() = ProgressChapter(accountKey, progress.chapterId)
    private fun clock(chapter: ProgressChapter) = clocks.getOrPut(chapter) { ProgressOrder.clock(chapter) }
    private fun reserve(target: ReaderProgressTarget): Write {
        val clock = clock(target.chapter)
        return Write(target, clock, clock.revision.incrementAndGet())
    }

    private fun isConfirmed(chapter: ProgressChapter, clock: ProgressClock, progress: ProgressDto): Boolean =
        saved[chapter]?.let {
            it.accepted == clock.accepted.get() && it.progress.pageNum == progress.pageNum &&
                it.progress.bookScrollId == progress.bookScrollId
        } == true

    fun seed(target: ReaderProgressTarget) = synchronized(guard) {
        saved[target.chapter] = Saved(target.progress, clock(target.chapter).accepted.get())
    }

    suspend fun recordLocal(target: ReaderProgressTarget) {
        if (incognito) return
        val session = target.offlineSession ?: return
        val mutex = synchronized(guard) { clock(target.chapter).localWrites }
        mutex.withLock { local?.record(session, target.progress, markRead = false) }
    }

    suspend fun syncPending(sync: suspend () -> Unit) {
        if (!incognito) ProgressOrder.mutex.withLock { sync() }
    }

    fun submit(target: ReaderProgressTarget, timerScope: CoroutineScope = scope) {
        if (incognito || target.api == null) return
        synchronized(guard) {
            // An unchanged opening must not displace an exit queued by a previous reader.
            if (pending == null && isConfirmed(target.chapter, clock(target.chapter), target.progress)) return
            clearPending()
            val item = Pending(reserve(target), nowMillis())
            pending = item
            item.timer = timerScope.launch {
                delay(delayMillis)
                synchronized(guard) { if (pending === item) launchPending(item) }
            }
        }
    }

    fun flushMatured() = synchronized(guard) {
        pending?.takeIf { nowMillis() - it.since >= delayMillis }?.let(::launchPending)
    }

    fun detach(flushMatured: Boolean, chapterId: Int? = null) = synchronized(guard) {
        if (chapterId != null && pending?.write?.target?.progress?.chapterId != chapterId) return@synchronized
        if (flushMatured) flushMatured()
        clearPending()
    }

    fun close(target: ReaderProgressTarget): Job? = launchExit(target, Action.Close)
    fun complete(target: ReaderProgressTarget): Job? = launchExit(target, Action.Complete)
    fun reset(target: ReaderProgressTarget): Job? = launchExit(target, Action.Reset)

    private fun clearPending() {
        pending?.timer?.cancel()
        pending = null
    }

    private fun launchPending(item: Pending) {
        if (item.running) return
        item.running = true
        // The delay job owns only the timer, never the HTTP work.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val done = execute(item.write, Action.Save)
            synchronized(guard) {
                item.running = false
                if (done && pending === item) clearPending()
            }
        }
    }

    private fun launchExit(target: ReaderProgressTarget, action: Action): Job? {
        if (incognito) return null
        val write = synchronized(guard) {
            clearPending()
            reserve(target)
        }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val session = target.offlineSession
            if (session != null) {
                // Do not make local resume wait for an older network request.
                write.clock.localWrites.withLock {
                    if (write.clock.revision.get() != write.revision) return@launch
                    attempt("save final offline progress", target.progress) {
                        if (action == Action.Reset) local?.reset(session, target.progress.chapterId)
                        else local?.record(session, target.progress, markRead = action == Action.Complete)
                    }
                }
            }
            execute(write, action)
        }
    }

    private suspend fun execute(write: Write, action: Action): Boolean = ProgressOrder.mutex.withLock {
        if (write.clock.revision.get() != write.revision) return@withLock true
        val (chapterKey, progress, api, session) = write.target
        val chapter = ProgressChapter(chapterKey, progress.chapterId)
        fun accepted(): Long = write.clock.accepted.incrementAndGet()
        if (action == Action.Reset) {
            val marked = api != null && attempt("mark chapter unread", progress) {
                api.markChaptersUnread(MarkVolumesReadDto(seriesId = progress.seriesId, chapterIds = listOf(progress.chapterId)))
                accepted()
                synchronized(guard) { saved.remove(chapter) }
            }
            if (marked && session != null) attempt("acknowledge offline reset", progress) {
                local?.synced(session, progress.copy(pageNum = 0, bookScrollId = null), markedUnread = true)
            }
            return@withLock marked
        }

        val confirmed = synchronized(guard) { isConfirmed(chapter, write.clock, progress) }
        val progressSaved = confirmed || (api != null && attempt("save remote progress", progress) {
            api.saveProgress(progress)
            val version = accepted()
            synchronized(guard) { saved[chapter] = Saved(progress, version) }
            onSaved(progress)
        })
        // Marking is independent of the progress endpoint, but ordered with it and with
        // all newer writes for the chapter. Do not acknowledge an unaccepted offline write.
        val markedRead = action == Action.Complete && api != null && attempt("mark chapter read", progress) {
            api.markChapterRead(MarkChapterReadDto(progress.seriesId, progress.chapterId, generateReadingSession = false))
            accepted()
            synchronized(guard) { saved.remove(chapter) }
        }
        if (progressSaved && session != null && (action == Action.Complete || !confirmed)) {
            attempt("acknowledge offline progress", progress) { local?.synced(session, progress, markedRead = markedRead) }
        }
        progressSaved
    }

    private suspend fun attempt(operation: String, progress: ProgressDto, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("Could not $operation for chapter ${progress.chapterId}.", error)
            false
        }
}
