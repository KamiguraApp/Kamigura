package li.mof.kamigura.reader

import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.ProgressDto
import li.mof.kamigura.reader.novel.NovelProgressWriter
import li.mof.kamigura.reader.novel.novelResourceAllowed
import org.junit.Assert.*
import org.junit.Test

class NovelReaderTest {
    private fun progress(spine: Int, path: String) = ProgressDto(1, 2, 3, 4, spine, path)
    private fun api(writes: MutableList<ProgressDto>): KavitaApi = Proxy.newProxyInstance(
        KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)
    ) { _, method, args ->
        check(method.name == "saveProgress")
        writes += args[0] as ProgressDto
        Unit
    } as KavitaApi

    @Test fun resourcesAreRestrictedToCurrentServerProxyPrefixAndChapter() {
        val base = "https://books.example/kavita"
        assertTrue(novelResourceAllowed("$base/api/Book/4/book-resources?file=font.woff&apiKey=secret", base, 4))
        listOf("https://evil.example/api/Book/4/book-resources", "$base/api/Book/5/book-resources",
            "$base/api/Reader/image", "file:///private", "http://books.example/kavita/api/Book/4/book-resources",
            "https://user:pass@books.example/kavita/api/Book/4/book-resources").forEach {
            assertFalse(it, novelResourceAllowed(it, base, 4))
        }
    }

    @Test fun debounceCapturesSpineAndXPathAndCancelsTransientPositions() = runBlocking {
        val writes = CopyOnWriteArrayList<ProgressDto>()
        val writer = NovelProgressWriter(api(writes), false, this, 80)
        writer.submit(progress(0, "//body/p[1]"))
        delay(20)
        writer.submit(progress(1, "id(\"second\")"))
        delay(40)
        assertTrue(writes.isEmpty())
        delay(80)
        assertEquals(listOf(progress(1, "id(\"second\")")), writes)
    }

    @Test fun immediateExitWritesLatestPositionOnce() = runBlocking {
        val writes = CopyOnWriteArrayList<ProgressDto>()
        val writer = NovelProgressWriter(api(writes), false, this, 80)
        val latest = progress(3, "//body/div[1]/p[5]")
        writer.submit(progress(1, "//body/p[1]"))
        writer.submit(latest, immediately = true)
        delay(20)
        writer.submit(latest, immediately = true)
        delay(100)
        assertEquals(listOf(latest), writes)
    }

    @Test fun incognitoNeverSendsProgressOrBoundaryWrites() = runBlocking {
        val writes = CopyOnWriteArrayList<ProgressDto>()
        val writer = NovelProgressWriter(api(writes), true, this, 1)
        writer.submit(progress(1, "//body"))
        writer.submit(progress(2, "//body"), true)
        var marked = false
        writer.finish(progress(2, "//body")) { marked = true }
        delay(20)
        assertTrue(writes.isEmpty())
        assertFalse(marked)
    }

    @Test fun boundaryCancelsDebounceAndOrdersFinalProgressBeforeMark() = runBlocking {
        val writes = CopyOnWriteArrayList<ProgressDto>()
        val writer = NovelProgressWriter(api(writes), false, this, 80)
        writer.submit(progress(0, "//body/p[1]"))
        val last = progress(5, "//body/p[8]")
        writer.finish(last) { assertEquals(listOf(last), writes) }
        delay(100)
        assertEquals(listOf(last), writes)
    }
}
