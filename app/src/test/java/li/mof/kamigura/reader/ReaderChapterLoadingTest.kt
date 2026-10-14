package li.mof.kamigura.reader

import kotlinx.coroutines.runBlocking
import li.mof.kamigura.ChapterInfoDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.reader.internal.loadReaderChapterInfo
import li.mof.kamigura.reader.internal.loadReaderChapterPages
import li.mof.kamigura.reader.internal.EpubImagePageContent
import okhttp3.ResponseBody
import li.mof.kamigura.reader.internal.readerLoadErrorMessage
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReaderChapterLoadingTest {
    @Test fun proseEpubUsesNovelRouteWithoutDuplicatingChapterInfo() = runBlocking {
        var metadataCalls = 0
        val fullApi = object : KavitaApi by (api { metadataCalls++; ChapterInfoDto(pages = 3) }) {
            override suspend fun bookPage(chapterId: Int, page: Int) = "prose".toResponseBody()
        }
        val loaded = loadReaderChapterPages(fullApi, 42, 3, "http://server") {
            EpubImagePageContent(emptyList(), "Original novel prose")
        }
        assertTrue(loaded.novelEpub)
        assertNull(loaded.epubImageUrls)
        assertEquals(1, metadataCalls)
        assertEquals(3, loaded.info.pages)
    }

    @Test fun failedBookRequestsNeverMasqueradeAsNovelClassification() = runBlocking {
        for (failure in listOf(java.io.IOException("offline"), kotlinx.coroutines.CancellationException("cancelled"))) {
            val fullApi = object : KavitaApi by (api { ChapterInfoDto(pages = 3) }) {
                override suspend fun bookPage(chapterId: Int, page: Int): ResponseBody = throw failure
            }
            try {
                loadReaderChapterPages(fullApi, 42, 3, "http://server") { EpubImagePageContent(emptyList(), "prose") }
                fail("Request failure must propagate")
            } catch (actual: Throwable) {
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
            }
        }
    }
    @Test
    fun onlyPdfRequestsExtractionAndAlwaysLoadsDimensions() = runBlocking {
        for (format in listOf(null, 0, 1, 2, 3, 4, 99)) {
            var called = false
            val api = api { args ->
                called = true
                assertEquals(42, args[0])
                assertEquals(true, args[1])
                assertEquals(format == 4, args[2])
                ChapterInfoDto(pages = 126)
            }
            assertEquals(126, loadReaderChapterInfo(api, 42, format).pages)
            assertTrue(called)
        }
    }

    @Test
    fun onlyEpubResolvesSpineImagesAndNeverRequestsPdfExtraction() = runBlocking {
        for (format in listOf(null, 0, 1, 2, 3, 4, 99)) {
            val requestedPages = mutableListOf<Int>()
            val metadataApi = api { args ->
                assertEquals(format == 4, args[2])
                ChapterInfoDto(pages = 2)
            }
            val fullApi = object : KavitaApi by metadataApi {
                override suspend fun bookPage(chapterId: Int, page: Int): ResponseBody {
                    assertEquals(42, chapterId)
                    requestedPages += page
                    return "$page".toResponseBody()
                }
            }
            val loaded = loadReaderChapterPages(fullApi, 42, format, "http://server") {
                EpubImagePageContent(listOf("//server/api/book/42/book-resources?file=$it.jpg"), "")
            }
            assertEquals(2, loaded.info.pages)
            if (format == 3) {
                assertEquals(setOf(0, 1), requestedPages.toSet())
                assertEquals(listOf("http://server/api/book/42/book-resources?file=0.jpg",
                    "http://server/api/book/42/book-resources?file=1.jpg"), loaded.epubImageUrls)
            } else {
                assertTrue(requestedPages.isEmpty())
                assertNull(loaded.epubImageUrls)
            }
        }
    }

    @Test
    fun emptyExtractionNeverBecomesAReadyZeroPageChapter() = runBlocking {
        for (pages in listOf(null, 0)) {
            try {
                loadReaderChapterInfo(api { ChapterInfoDto(pages = pages) }, 42, 4)
                fail("Empty extraction must not be marked ready")
            } catch (e: IllegalStateException) {
                assertEquals("Chapter has no readable pages", e.message)
            }
        }
    }

    private fun api(onChapterInfo: (Array<out Any?>) -> ChapterInfoDto): KavitaApi =
        Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, args ->
            check(method.name == "chapterInfo")
            onChapterInfo(args)
        } as KavitaApi

    @Test
    fun proxyTimeoutsExplainThatTheServerIsStillWorking() {
        val message = readerLoadErrorMessage(
            HttpException(Response.error<Any>(504, "".toResponseBody(null)))
        )
        assertTrue(message.contains("wait a few minutes"))
        assertFalse(message.contains("504"))
    }

    @Test
    fun otherFailuresKeepTheirOwnMessage() {
        assertEquals("boom", readerLoadErrorMessage(IllegalStateException("boom")))
    }
}
