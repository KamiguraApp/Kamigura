package li.mof.kamigura.reader

import kotlinx.coroutines.runBlocking
import li.mof.kamigura.ChapterInfoDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.reader.internal.loadReaderChapterInfo
import li.mof.kamigura.reader.internal.readerLoadErrorMessage
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReaderChapterLoadingTest {
    @Test
    fun onlyPdfRequestsExtractionAndAlwaysLoadsDimensions() = runBlocking {
        for (format in listOf(null, 0, 1, 2, 4, 99)) {
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
    fun epubStopsBeforeAnyPageRequest() = runBlocking {
        val api = api { error("EPUB must not request chapter images") }
        try {
            loadReaderChapterInfo(api, 42, 3)
            fail("EPUB must be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("EPUB is not supported yet.", e.message)
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
