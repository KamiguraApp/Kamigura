package li.mof.kamigura.reader.novel

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
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.ProgressDto

private val NovelExitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal class NovelProgressWriter(
    private val api: KavitaApi,
    private val incognito: Boolean,
    private val scope: CoroutineScope = NovelExitScope,
    private val delayMillis: Long = 3_000L,
    private val logFailure: (String, Throwable) -> Unit = { message, error -> KamiguraLog.w(message, error) }
) {
    private val mutex = Mutex()
    private val revision = AtomicLong()
    private var pending: Job? = null
    private var lastSaved: ProgressDto? = null

    fun submit(progress: ProgressDto, immediately: Boolean = false) {
        if (incognito) return
        val token = revision.incrementAndGet()
        pending?.cancel()
        fun enqueue() {
            // Debounce cancellation must not cancel an HTTP write already in flight.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock {
                if (revision.get() != token || lastSaved == progress) return@withLock
                try {
                    api.saveProgress(progress)
                    lastSaved = progress
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logFailure("Could not save novel EPUB progress.", error)
                }
            } }
        }
        if (immediately) enqueue() else pending = scope.launch {
            delay(delayMillis)
            enqueue()
        }
    }

    fun cancelPending() { revision.incrementAndGet(); pending?.cancel() }

    suspend fun finish(progress: ProgressDto?, mark: suspend () -> Unit) {
        cancelPending()
        if (incognito) return
        mutex.withLock {
            if (progress != null && progress != lastSaved) {
                try {
                    api.saveProgress(progress)
                    lastSaved = progress
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { logFailure("Could not save final novel EPUB progress.", error) }
            }
            mark()
        }
    }

    fun finishAsync(progress: ProgressDto?, mark: suspend () -> Unit) {
        cancelPending()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { finish(progress, mark) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { logFailure("Could not finish novel EPUB progress.", error) }
        }
    }
}
