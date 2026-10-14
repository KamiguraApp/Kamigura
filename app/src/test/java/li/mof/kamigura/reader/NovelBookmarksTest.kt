package li.mof.kamigura.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import li.mof.kamigura.CreatePersonalToCDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.PersonalToCDto
import li.mof.kamigura.reader.novel.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

class NovelBookmarksTest {
    private fun item(id: Int = 1, page: Int = 2, title: String = "Opening") =
        PersonalToCDto(id, 30, page, title, "//body/p[5]/ruby[1]", "Web selection", "Part 1")
    private fun controller(api: KavitaApi) = NovelBookmarks(api, 30, 20, 10, 40, 10)
    private fun api(handler: (String, Array<out Any?>) -> Any?): KavitaApi =
        Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, args -> handler(method.name, args)
        } as KavitaApi
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "error".toResponseBody()))

    @Test fun refreshPreservesServerOrderDeepAnchorsAndSelectionButFiltersOtherChapters() = runBlocking {
        val entries = listOf(item(2), item(1, 0), item().copy(chapterId = 31), item(3, -1), item(4, 10))
        val bookmarks = controller(api { method, args ->
            assertEquals("personalTableOfContents", method); assertEquals(30, args[0]); entries
        })
        bookmarks.refresh()
        assertEquals(entries.take(2), bookmarks.state.items)
        assertTrue(bookmarks.state.loaded)
        assertEquals("Chapter Part 1, Page 3", novelBookmarkSubtitle(entries[0]))
        assertEquals("Page 1", novelBookmarkSubtitle(entries[1].copy(chapterTitle = null)))
    }

    @Test fun createAndDeleteUseSpineXPathAndCompositeKeyNotId() = runBlocking {
        var entries = emptyList<PersonalToCDto>()
        var request: CreatePersonalToCDto? = null
        var deleted: List<Any?>? = null
        val bookmarks = controller(api { method, args -> when (method) {
            "personalTableOfContents" -> entries
            "createPersonalToC" -> { request = args[0] as CreatePersonalToCDto; entries = listOf(item()); Unit }
            "deletePersonalToC" -> { deleted = args.take(3); entries = emptyList(); Unit }
            else -> error(method)
        } })
        bookmarks.refresh()
        bookmarks.toggle(2, NovelBookmarkContext("//body/p[5]", "Opening", emptySet()))
        assertEquals(CreatePersonalToCDto(30, 20, 10, 40, 2, "Opening", "//body/p[5]"), request)
        val json = Json.encodeToString(request!!)
        assertTrue(json.contains("\"pageNumber\":2")); assertTrue(json.contains("\"bookScrollId\":\"//body/p[5]\""))
        bookmarks.remove(bookmarks.state.items.single())
        assertEquals(listOf(30, 2, "Opening"), deleted)
        assertTrue(bookmarks.state.items.isEmpty())
    }

    @Test fun sameScreenToggleRemovesAllVisibleEntriesButLeavesOtherScreensAndSpines() = runBlocking {
        var entries = listOf(item(), item(2, title = "Another"), item(3, title = "Other screen"), item(4, 3))
        val bookmarks = controller(api { method, args -> when (method) {
            "personalTableOfContents" -> entries
            "deletePersonalToC" -> { entries = entries.filterNot { it.pageNumber == args[1] && it.title == args[2] }; Unit }
            else -> error(method)
        } })
        bookmarks.refresh()
        bookmarks.toggle(2, NovelBookmarkContext("//body/p[5]", "Opening", setOf(1, 2, 4)))
        assertEquals(listOf(3, 4), bookmarks.state.items.map { it.id })
    }

    @Test fun confirmedDuplicateGetsDistinctTitleButUnrelated400And500NeverReplay() = runBlocking {
        val titles = mutableListOf<String>()
        var entries = emptyList<PersonalToCDto>()
        val bookmarks = controller(api { method, args -> when (method) {
            "personalTableOfContents" -> entries
            "createPersonalToC" -> {
                val request = args[0] as CreatePersonalToCDto; titles += request.title
                if (titles.size == 1) { entries = listOf(item()); throw http(400) }
                entries += item(2, title = request.title); Unit
            }
            else -> error(method)
        } })
        bookmarks.refresh()
        bookmarks.toggle(2, NovelBookmarkContext("//body/p[5]", "Opening", emptySet()))
        assertEquals(listOf("Opening", "Opening (2)"), titles)
        for (code in listOf(400, 500)) {
            var writes = 0
            val failed = controller(api { method, _ ->
                if (method == "personalTableOfContents") emptyList<PersonalToCDto>()
                else { writes++; throw http(code) }
            })
            failed.refresh(); failed.toggle(2, NovelBookmarkContext("//body/p[5]", "Opening", emptySet()))
            assertEquals(1, writes); assertFalse(failed.state.loaded)
            assertTrue(failed.state.error!!.contains("HTTP $code"))
            if (code == 500) assertTrue(failed.state.error!!.contains("table of contents"))
        }
    }

    @Test fun uncertainWriteMustRefreshBeforeAnotherMutation() = runBlocking {
        var writes = 0
        val bookmarks = controller(api { method, _ ->
            if (method == "personalTableOfContents") if (writes > 0) listOf(item()) else emptyList()
            else { writes++; throw java.io.IOException("Lost response") }
        })
        bookmarks.refresh()
        val context = NovelBookmarkContext("//body/p[5]", "Opening", emptySet())
        bookmarks.toggle(2, context); bookmarks.toggle(2, context)
        assertEquals(1, writes); assertFalse(bookmarks.state.loaded)
        bookmarks.refresh(); assertTrue(bookmarks.state.loaded); assertEquals(1, bookmarks.state.items.size)
    }

    @Test fun busyAndCancellationDoNotAllowDuplicateWrites() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var writes = 0
        val delegate = api { _, _ -> emptyList<PersonalToCDto>() }
        val bookmarks = controller(object : KavitaApi by delegate {
            override suspend fun createPersonalToC(body: CreatePersonalToCDto) {
                writes++; entered.complete(Unit); finish.await()
            }
        })
        bookmarks.refresh()
        val context = NovelBookmarkContext("//body/p[5]", "Opening", emptySet())
        val first = launch { bookmarks.toggle(2, context) }; entered.await()
        bookmarks.toggle(2, context); bookmarks.refresh(); assertEquals(1, writes)
        first.cancel(); first.join(); assertFalse(bookmarks.state.busy); assertFalse(bookmarks.state.loaded)
        val cancelled = controller(api { _, _ -> throw CancellationException() })
        try { cancelled.refresh(); fail() } catch (_: CancellationException) { }
        assertFalse(cancelled.state.busy)
    }

    @Test fun unavailableInvalidAndTitleCollisionCases() = runBlocking {
        var reads = 0
        val bookmarks = controller(api { method, _ ->
            assertEquals("personalTableOfContents", method); reads++; emptyList<PersonalToCDto>()
        })
        val context = NovelBookmarkContext("//body", "", emptySet())
        bookmarks.toggle(0, context); assertEquals(0, reads)
        bookmarks.refresh(); bookmarks.toggle(-1, context); bookmarks.toggle(10, context); assertEquals(1, reads)
        assertEquals("Page 1", novelBookmarkTitle("", 0, emptyList()))
        assertEquals("Opening (3)", novelBookmarkTitle("Opening", 2, listOf(item(), item(2, title = "Opening (2)"))))
    }
}
