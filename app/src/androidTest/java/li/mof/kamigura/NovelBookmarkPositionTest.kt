package li.mof.kamigura

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import li.mof.kamigura.reader.novel.NovelBookmarkContext
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelBookmarkPositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun deepWebBookmarksResolveAndRemainVisibleAcrossReflowAndViewportChanges() {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val wide = mutableStateOf(false)
        val fragment = "<div>" + (1..80).joinToString("") {
            "<p>Paragraph $it: original prose before <ruby>anchor<rt>reading</rt></ruby> and after it.</p>"
        } + "</div>"
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { position = it }; v.open(fragment, "http://127.0.0.1", 1, null, false)
        } }, modifier = Modifier.requiredSize(if (wide.value) 700.dp else 360.dp, if (wide.value) 360.dp else 700.dp)) }
        compose.waitUntil(20_000) { position != null }
        val items = listOf(
            PersonalToCDto(1, 1, 0, "Web selection", "//body/p[40]/ruby[1]", "anchor"),
            PersonalToCDto(2, 1, 0, "Text node", "//body/p[40]/ruby[1]/text()[1]"),
            PersonalToCDto(3, 1, 0, "Other", "//body/p[1]"),
            PersonalToCDto(4, 1, 0, "Unknown", "//body/p[999]"))
        for (vertical in listOf(false, true)) for (landscape in listOf(false, true)) {
            compose.runOnIdle { wide.value = landscape; view.setVertical(vertical); view.setFontSize(if (landscape) 125 else 100) }
            Thread.sleep(300)
            jump(view, items[0].bookScrollId)
            val jumped = position
            assertTrue("vertical=$vertical landscape=$landscape position=$jumped", position!!.screen > 0)
            val result = context(view, items)
            assertTrue("vertical=$vertical landscape=$landscape jumped=$jumped current=$position context=$result",
                result.visible.containsAll(listOf(1, 2)))
            assertFalse(3 in result.visible); assertFalse(4 in result.visible)
            assertTrue(result.anchor.matches(Regex("//body/p\\[\\d+\\]")))
            assertTrue(result.title.codePointCount(0, result.title.length) <= 20)
            jump(view, items[1].bookScrollId)
            assertTrue(2 in context(view, items).visible)
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun queryingDoesNotReportProgressAndMissingAnchorDoesNotJumpToTheBeginning() {
        lateinit var view: NovelPageView
        var reports = 0
        var latest: NovelPosition? = null
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { reports++; latest = it }
            v.open("<div><p>😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀</p><p>Other text.</p></div>",
                "http://127.0.0.1", 1, null, false)
        } }, modifier = Modifier.requiredSize(360.dp, 700.dp)) }
        compose.waitUntil(20_000) { reports > 0 }; Thread.sleep(300)
        val count = reports
        val result = context(view, listOf(PersonalToCDto(1, 1, 0, "Start", null)))
        assertEquals("//body/p[1]", result.anchor)
        assertEquals(20, result.title.codePointCount(0, result.title.length))
        assertEquals(setOf(1), result.visible)
        assertEquals(count, reports)
        val before = latest
        val complete = CountDownLatch(1)
        var error: String? = null
        compose.runOnIdle { view.jumpToBookmark("//body/p[999]") { error = it; complete.countDown() } }
        assertTrue(complete.await(5, TimeUnit.SECONDS)); assertNotNull(error)
        assertEquals(count, reports); assertEquals(before, latest)
        compose.runOnIdle { view.dispose() }
    }

    @Test fun pendingResizeDoesNotUndoABookmarkJump() {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val fragment = "<div>" + (1..80).joinToString("") {
            "<p>Paragraph $it: original prose before <ruby>anchor<rt>reading</rt></ruby> and after it.</p>"
        } + "</div>"
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { position = it }; v.open(fragment, "http://127.0.0.1", 1, null, false)
        } }, modifier = Modifier.requiredSize(360.dp, 700.dp)) }
        compose.waitUntil(20_000) { position != null }
        for (vertical in listOf(false, true)) {
            js(view, """
                window.resizeJumpResult = null;
                window.novelReader.setVertical($vertical);
                window.novelReader.jump('//body/p[1]');
                window.dispatchEvent(new Event('resize'));
                window.novelReader.jump('//body/p[40]/ruby[1]');
                setTimeout(() => { window.resizeJumpResult = window.novelReader.bookmarkContext(
                    ['//body/p[40]/ruby[1]', '//body/p[40]/ruby[1]/text()[1]']); }, 200);
            """.trimIndent())
            var result = "null"
            compose.waitUntil(5_000) { result = js(view, "window.resizeJumpResult"); result != "null" }
            val visible = JSONObject(result).getJSONArray("visible")
            assertEquals("vertical=$vertical result=$result", setOf(0, 1),
                (0 until visible.length()).map { visible.getInt(it) }.toSet())
        }
        compose.runOnIdle { view.dispose() }
    }

    private fun js(view: NovelPageView, script: String): String {
        val done = CountDownLatch(1); var result = ""
        compose.runOnIdle { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }

    private fun context(view: NovelPageView, items: List<PersonalToCDto>): NovelBookmarkContext {
        val done = CountDownLatch(1); var value: NovelBookmarkContext? = null
        compose.runOnIdle { view.bookmarkContext(items) { value = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(value)
    }

    private fun jump(view: NovelPageView, anchor: String?) {
        val done = CountDownLatch(1); var error: String? = null
        compose.runOnIdle { view.jumpToBookmark(anchor) { error = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertNull(error)
    }
}
