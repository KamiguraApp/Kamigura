package li.mof.kamigura.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import li.mof.kamigura.BookmarkDto
import li.mof.kamigura.BookmarkPageDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.reader.internal.ReaderBookmarks
import li.mof.kamigura.reader.internal.readerCanManageBookmarks
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReaderBookmarksTest {
    @Test
    fun readOnlyRefreshLoadsOnlyValidPagesForThisChapter() = runBlocking {
        val calls = mutableListOf<String>()
        val bookmarks = controller(api { method, args ->
            calls += method
            assertEquals(30, args[0])
            listOf(BookmarkDto(chapterId = 30, page = 0), BookmarkDto(chapterId = 30, page = 4),
                BookmarkDto(chapterId = 31, page = 1), BookmarkDto(chapterId = 30, page = -1),
                BookmarkDto(chapterId = 30, page = 10))
        })
        bookmarks.refresh()
        assertEquals(listOf("chapterBookmarks"), calls)
        assertEquals(setOf(0, 4), bookmarks.state.pages)
        assertTrue(bookmarks.state.loaded)
    }

    @Test
    fun addAndRemoveUseTheSameZeroBasedOriginPageAndCapturedChapter() = runBlocking {
        val requests = mutableListOf<Pair<String, BookmarkPageDto>>()
        val bookmarks = controller(api { method, args ->
            if (method == "chapterBookmarks") emptyList<BookmarkDto>()
            else { requests += method to args[0] as BookmarkPageDto; Unit }
        })
        bookmarks.refresh()
        bookmarks.toggle(3)
        assertEquals(setOf(3), bookmarks.state.pages)
        assertEquals("Page 4 bookmarked.", bookmarks.state.message)
        bookmarks.toggle(3)
        assertTrue(bookmarks.state.pages.isEmpty())
        assertEquals("Bookmark removed from page 4.", bookmarks.state.message)
        assertEquals(listOf("bookmarkPage", "unbookmarkPage"), requests.map { it.first })
        assertTrue(requests.all { it.second == BookmarkPageDto(10, 20, 30, 3) })
        assertEquals("""{"seriesId":10,"volumeId":20,"chapterId":30,"page":0}""",
            Json.encodeToString(BookmarkPageDto(10, 20, 30, 0)))
    }

    @Test
    fun failedMutationRequiresStatusRefreshInsteadOfReplayingThePost() = runBlocking {
        var writes = 0
        val bookmarks = controller(api { method, _ ->
            if (method == "chapterBookmarks") {
                if (writes > 0) listOf(BookmarkDto(chapterId = 30, page = 2)) else emptyList()
            } else { writes++; throw IllegalStateException("Server committed but response was lost") }
        })
        bookmarks.refresh()
        bookmarks.toggle(2)
        assertFalse(bookmarks.state.loaded)
        assertNotNull(bookmarks.state.error)
        assertFalse(bookmarks.state.pages.contains(2))
        bookmarks.toggle(2)
        assertEquals(1, writes)
        bookmarks.refresh()
        assertTrue(bookmarks.state.loaded)
        assertEquals(setOf(2), bookmarks.state.pages)
        assertNull(bookmarks.state.error)
    }

    @Test
    fun duplicateActionsAreIgnoredUntilTheFirstRequestFinishes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var writes = 0
        val delegate = api { _, _ -> emptyList<BookmarkDto>() }
        val bookmarks = controller(object : KavitaApi by delegate {
            override suspend fun bookmarkPage(body: BookmarkPageDto) {
                writes++
                entered.complete(Unit)
                finish.await()
            }
        })
        bookmarks.refresh()
        val first = launch { bookmarks.toggle(0) }
        entered.await()
        bookmarks.toggle(0)
        bookmarks.refresh()
        assertEquals(1, writes)
        assertEquals(0, bookmarks.state.savingPage)
        finish.complete(Unit)
        first.join()
        assertEquals(setOf(0), bookmarks.state.pages)
    }

    @Test
    fun unavailableStateAndInvalidPagesNeverSendMutations() = runBlocking {
        var reads = 0
        val bookmarks = controller(api { method, _ ->
            check(method == "chapterBookmarks")
            reads++
            emptyList<BookmarkDto>()
        })
        bookmarks.toggle(0)
        assertEquals(0, reads)
        bookmarks.refresh()
        bookmarks.toggle(-1)
        bookmarks.toggle(10)
        assertEquals(1, reads)
    }

    @Test
    fun cancellationIsPropagatedAndDoesNotLeaveTheButtonBusy() = runBlocking {
        val bookmarks = controller(api { _, _ -> throw CancellationException("Reader left") })
        try { bookmarks.refresh(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertFalse(bookmarks.state.busy)
        assertFalse(bookmarks.state.loaded)
    }

    @Test
    fun incognitoAndDownloadedChaptersCannotManageBookmarks() {
        assertTrue(readerCanManageBookmarks(incognito = false, offline = false))
        assertFalse(readerCanManageBookmarks(incognito = true, offline = false))
        assertFalse(readerCanManageBookmarks(incognito = false, offline = true))
        assertFalse(readerCanManageBookmarks(incognito = true, offline = true))
    }

    private fun controller(api: KavitaApi) = ReaderBookmarks(api, 10, 20, 30, 10)

    private fun api(handler: (String, Array<out Any?>) -> Any?): KavitaApi =
        Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, args -> handler(method.name, args)
        } as KavitaApi
}
