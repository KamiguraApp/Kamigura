package li.mof.kamigura.reader.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Chapter-local HTML only: bounded storage and shared foreground/prefetch requests, no reader state. */
internal class NovelSectionCache(
    parent: CoroutineScope,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val load: suspend (Int) -> String
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()
    private val slots = Semaphore(2)
    private val ready = LinkedHashMap<Int, String>()
    private val pending = mutableMapOf<Int, Deferred<String>>()
    private var window = IntRange.EMPTY
    private var closed = false

    fun retainAround(page: Int, count: Int) = synchronized(lock) {
        window = maxOf(0, page - 1)..minOf(count - 1, page + 1)
        ready.keys.retainAll(window.toSet())
        pending.filterKeys { it !in window }.values.toList().forEach { it.cancel() }
    }

    suspend fun get(page: Int): String {
        val request = synchronized(lock) {
            check(!closed) { "EPUB cache is closed" }
            check(page in window) { "EPUB section is outside the retained window" }
            ready[page]?.let { return it }
            pending[page] ?: scope.async(start = CoroutineStart.LAZY) {
                val html = slots.withPermit { load(page) }
                synchronized(lock) {
                    if (!closed && page in window && html.length.toLong() * 2 <= maxBytes) {
                        ready[page] = html
                        while (ready.values.sumOf { it.length.toLong() * 2 } > maxBytes) ready.remove(ready.keys.first())
                    }
                }
                html
            }.also { task ->
                pending[page] = task
                task.invokeOnCompletion { synchronized(lock) { if (pending[page] === task) pending.remove(page) } }
            }
        }
        return request.await()
    }

    fun prefetchAround(page: Int, count: Int) {
        for (target in listOf(page + 1, page - 1).filter { it in 0 until count }) {
            scope.launch {
                try { get(target) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Foreground retries failed speculative requests. */ }
            }
        }
    }

    fun close() {
        synchronized(lock) { closed = true; ready.clear(); pending.clear() }
        scope.cancel()
    }
}
