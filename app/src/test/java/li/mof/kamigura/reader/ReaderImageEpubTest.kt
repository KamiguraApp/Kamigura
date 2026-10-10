package li.mof.kamigura.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.reader.internal.EpubImagePageContent
import li.mof.kamigura.reader.internal.UnsupportedImageEpubException
import li.mof.kamigura.reader.internal.epubPageImageUrl
import li.mof.kamigura.reader.internal.loadImageEpubPages
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class ReaderImageEpubTest {
    private val root = "https://books.example/kavita"
    private fun image(file: String = "page.jpg", chapter: Int = 42) =
        "//books.example/kavita/api/book/$chapter/book-resources?apiKey=key&file=$file"
    private fun page(src: String = image(), text: String = "\n\uFFFC\u00A0\u200B\uFEFF\t") =
        EpubImagePageContent(listOf(src), text)

    @Test
    fun imageUrlUsesServerSchemeAndPreservesResourceQueryAndProxyPath() {
        assertEquals("https:${image()}", epubPageImageUrl(page(), root, 42))
        assertEquals("http://books.example/api/book/42/book-resources?file=cover.png",
            epubPageImageUrl(page("//books.example/api/book/42/book-resources?file=cover.png"),
                "http://books.example", 42))
    }

    @Test
    fun novelTextMultipleImagesBlankPagesAndActiveContentAreUnsupported() {
        for (content in listOf(page(text = "Novel text"), page(text = "Ruby reading"),
            EpubImagePageContent(emptyList(), ""),
            EpubImagePageContent(listOf(image(), image()), ""),
            page().copy(unsupportedContent = true))) {
            assertUnsupported { epubPageImageUrl(content, root, 42) }
        }
    }

    @Test
    fun onlySameOriginResourcesForThisChapterAreAccepted() {
        for (source in listOf("", "data:image/png;base64,AAAA", "javascript:alert(1)",
            "//other.example/kavita/api/book/42/book-resources?file=page.jpg",
            "http:${image()}", "//books.example:1234/kavita/api/book/42/book-resources?file=page.jpg",
            "https://user:password@books.example/kavita/api/book/42/book-resources?file=page.jpg",
            image(chapter = 43), "//books.example/api/book/42/book-resources?file=page.jpg",
            "//books.example/kavita/api/Reader/image?chapterId=42&page=0",
            "//books.example/kavita/api/book/42/book-resources?apiKey=key")) {
            assertUnsupported { epubPageImageUrl(page(source), root, 42) }
        }
    }

    @Test
    fun spineOrderIsPreservedEvenWhenRequestsCompleteOutOfOrder() = runBlocking {
        val api = api { chapter, index ->
            assertEquals(42, chapter)
            delay((5 - index) * 2L)
            "$index".toResponseBody()
        }
        val urls = loadImageEpubPages(api, 42, 6, root) { page(image("${it}.jpg")) }
        assertEquals((0 until 6).map { "https:${image("$it.jpg")}" }, urls)
    }

    @Test
    fun anImageOnlyCoverDoesNotAllowANovelToOpen() = runBlocking {
        val api = api { _, index -> "$index".toResponseBody() }
        try {
            loadImageEpubPages(api, 42, 6, root) {
                if (it == "0") page() else page(text = "Chapter text")
            }
            fail("A novel must not be exposed as an image chapter")
        } catch (_: UnsupportedImageEpubException) { }
    }

    @Test
    fun requestsAreBoundedAndCancellationReleasesTheWholePreparation() = runBlocking {
        val active = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val cancelled = AtomicInteger()
        val api = api { _, _ ->
            val count = active.incrementAndGet()
            check(count <= 4)
            if (count == 4) entered.complete(Unit)
            try { hold.await(); "page".toResponseBody() }
            finally { active.decrementAndGet(); cancelled.incrementAndGet() }
        }
        val load = async { loadImageEpubPages(api, 42, 100, root) { page() } }
        entered.await()
        load.cancel()
        try { load.await(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(0, active.get())
        assertEquals(4, cancelled.get())
    }

    @Test
    fun networkFailuresAreNotMisreportedAsUnsupportedContent() = runBlocking {
        try {
            loadImageEpubPages(api { _, _ -> throw java.io.IOException("Offline") }, 42, 1, root) { page() }
            fail("Failure must propagate")
        } catch (e: java.io.IOException) { assertEquals("Offline", e.message) }
    }

    private fun assertUnsupported(action: () -> Unit) {
        try { action(); fail("Unsupported EPUB content must be rejected") }
        catch (_: UnsupportedImageEpubException) { }
    }

    private fun api(load: suspend (Int, Int) -> ResponseBody): KavitaApi {
        val unused = Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, _ -> error("Unexpected API: ${method.name}")
        } as KavitaApi
        return object : KavitaApi by unused {
            override suspend fun bookPage(chapterId: Int, page: Int) = load(chapterId, page)
        }
    }
}
