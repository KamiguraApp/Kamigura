package li.mof.kamigura.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import li.mof.kamigura.BookChapterItemDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.reader.novel.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

class NovelContentsTest {
    @Test fun partIdsBecomeSafeXPathLiteralsWithoutDroppingQuotes() {
        assertNull(novelContentsAnchor(""))
        assertEquals("id(\"section-1\")", novelContentsAnchor("section-1"))
        assertEquals("id('section\"1')", novelContentsAnchor("section\"1"))
        assertEquals("id(\"section'1\")", novelContentsAnchor("section'1"))
        assertEquals("id(concat(\"section'\", '\"', \"1\"))", novelContentsAnchor("section'\"1"))
    }
    private fun api(handler: (String, Array<out Any?>) -> Any?): KavitaApi =
        Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, args -> handler(method.name, args)
        } as KavitaApi

    @Test fun treeDecodesAndFlattensInOrderWithoutLosingDepthOrPart() {
        val items = Json.decodeFromString<List<BookChapterItemDto>>("""[{"title":"Part","part":"","page":0,"children":[{"title":"One","part":"a","page":1,"children":[{"title":"Two","part":"b","page":1,"children":[]}]}]}]""")
        val flat = flattenNovelContents(items, 3)
        assertEquals(listOf("Part", "One", "Two"), flat.map { it.title })
        assertEquals(listOf(0, 1, 2), flat.map { it.depth })
        assertEquals(listOf("", "a", "b"), flat.map { it.part })
        assertEquals(0, currentNovelContentsEntry(flat, 0, emptySet()))
        assertEquals(0, currentNovelContentsEntry(flat, 1, emptySet()))
        assertEquals(1, currentNovelContentsEntry(flat, 1, setOf("a")))
        assertEquals(2, currentNovelContentsEntry(flat, 1, setOf("a", "b")))
        assertEquals(2, currentNovelContentsEntry(flat, 2, emptySet()))
    }

    @Test fun invalidParentStillKeepsValidChildrenAndEmptyTreesRemainEmpty() {
        val tree = listOf(BookChapterItemDto("Invalid", page = -1, children = listOf(BookChapterItemDto("Valid", page = 2))))
        assertEquals(listOf(NovelContentsEntry("Valid", "", 2, 1)), flattenNovelContents(tree, 3))
        assertTrue(flattenNovelContents(emptyList(), 3).isEmpty())
        assertNull(currentNovelContentsEntry(emptyList(), 0, emptySet()))
    }

    @Test fun loadingAnd500FailureRecoverIndependentlyOfPersonalContents() = runBlocking {
        var fail = true
        val contents = NovelContents(api { method, args ->
            assertEquals("bookContents", method); assertEquals(30, args[0])
            if (fail) throw HttpException(Response.error<Unit>(500, "broken NCX".toResponseBody()))
            listOf(BookChapterItemDto("One", page = 0))
        }, 30, 3)
        contents.refresh()
        assertFalse(contents.state.loading); assertFalse(contents.state.loaded)
        assertTrue(contents.state.error!!.contains("HTTP 500"))
        assertTrue(contents.state.error!!.contains("table of contents"))
        fail = false; contents.refresh()
        assertTrue(contents.state.loaded); assertNull(contents.state.error)
        assertEquals("One", contents.state.entries.single().title)
    }

    @Test fun duplicateLoadsAreGuardedAndCancellationPropagates() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val delegate = api { _, _ -> error("Unexpected API") }
        val contents = NovelContents(object : KavitaApi by delegate {
            override suspend fun bookContents(chapterId: Int): List<BookChapterItemDto> {
                calls++; entered.complete(Unit); release.await(); return emptyList()
            }
        }, 30, 3)
        val first = launch { contents.refresh() }; entered.await()
        contents.refresh(); assertEquals(1, calls)
        first.cancel(); first.join(); assertFalse(contents.state.loading)
        val cancelled = NovelContents(api { _, _ -> throw CancellationException() }, 30, 3)
        try { cancelled.refresh(); fail() } catch (_: CancellationException) { }
        assertFalse(cancelled.state.loading)
    }
}
