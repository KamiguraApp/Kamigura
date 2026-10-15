package li.mof.kamigura

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelLink
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelLinksUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val visible = mutableStateOf(true)

    @Test fun horizontalBodyLinkSavesAndReopensTheReachedSpineAndAnchor() = crossSpine(false)
    @Test fun verticalBodyLinkSavesAndReopensTheReachedSpineAndAnchor() = crossSpine(true)

    private fun crossSpine(vertical: Boolean) {
        ReaderServer(3, readingProfile = """{"bookReaderWritingStyle":${if (vertical) 1 else 0}}""",
            bookFragment = { page -> if (page == 0)
                "<div><p><a id='go' kavita-page='2' kavita-part='target&apos;&quot;' href='javascript:void(0)'><span>Open section</span></a></p></div>"
            else destination() }).use { server ->
            open(server); tap("go"); awaitPage(3)
            compose.waitUntil(5_000) { screen() > 1 }
            assertNoMenu()
            assertTrue(js("Array.from(document.getElementById(\"target'\\\"\").getClientRects()).some(r => r.right>0 && r.left<innerWidth && r.bottom>0 && r.top<innerHeight)") == "true")
            val reachedScreen = screen()
            menu(); compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
            assertEquals(2, server.bookProgressWrites.last().pageNum)
            assertNotNull(server.bookProgressWrites.last().bookScrollId)
            compose.runOnIdle { visible.value = true }; awaitPage(3)
            assertEquals(reachedScreen, screen())
        }
    }

    @Test fun sameSpinePartLinksAndReturnLinksDoNotTurnPagesOrOpenTheMenu() {
        ReaderServer(3, bookFragment = {
            "<div><p id='top'><a id='go' href='#target' kavita-part='target'>Forward</a></p>" +
                prose() + "<p id='target'><a id='return' href='#top' kavita-part='top'>Return</a></p></div>"
        }).use { server ->
            open(server)
            val requests = server.requests.count { it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "0" }
            tap("go"); compose.waitUntil(5_000) { screen() > 1 }; assertNoMenu()
            tap("return"); compose.waitUntil(5_000) { screen() == 1 }; assertNoMenu()
            assertTrue(state().startsWith("Page 1 /"))
            assertEquals(requests, server.requests.count { it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "0" })
        }
    }

    @Test fun externalLinksUseTheBrowserWhileNotesAndUnsafeSchemesDoNothing() {
        val launched = CopyOnWriteArrayList<Intent>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                launched += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ReaderServer(3, bookFragment = { """<div><style>.book-content p {text-align:center;}</style>
                <p><a id='https' href='https://example.org/book' target='_blank'>Web</a></p>
                <p><a id='http' href='http://example.org/book'>Web</a></p>
                <p><a id='note' href='#hidden' kavita-note-ref='#hidden'>Note</a></p>
                <p><a id='file' href='file:///sdcard/book'>File</a></p>
                <p><a id='content' href='content://book/1'>Content</a></p>
                <p><a id='script' href='javascript:window.epubExecuted=true'>Script</a></p>
                <p><a id='invalid' kavita-page='999' href='https://example.org'>Invalid</a></p>
                <p class='visually-hidden' id='hidden'>Hidden footnote text.</p>
                <script>window.epubExecuted=true</script>
                </div>""" }).use { server ->
                open(server); val before = state()
                for (id in listOf("https", "http", "note", "file", "content", "script", "invalid")) {
                    tap(id); Thread.sleep(400); assertEquals(before, state()); assertNoMenu()
                }
                assertEquals(listOf("https", "http"), launched.map { it.data!!.scheme })
                assertTrue(launched.all { it.hasCategory(Intent.CATEGORY_BROWSABLE) })
                assertEquals("false", js("window.epubExecuted === true"))
                assertEquals("rect(0px, 0px, 0px, 0px)", JSONObject("{\"v\":" + js("getComputedStyle(document.getElementById('hidden')).clip") + "}").getString("v"))
                assertTrue(server.bookProgressWrites.isEmpty())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun nonLinkTapZonesAndDragsStartingOnLinksKeepTheirExistingBehavior() {
        ReaderServer(3, bookFragment = {
            "<div><p><a id='go' kavita-page='2' href='javascript:void(0)'>Open section</a></p>" + prose() + "</div>"
        }).use { server ->
            open(server)
            val point = point("go")
            compose.onRoot().performTouchInput { swipe(point, Offset(width * .05f, point.y), 350) }
            compose.waitUntil(5_000) { screen() == 2 }
            assertTrue(state().startsWith("Page 1 /"))
            compose.onRoot().performTouchInput { click(Offset(width * .1f, height * .75f)) }
            compose.waitUntil(5_000) { screen() == 1 }
            menu(); compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .75f)) }
            compose.waitUntil(5_000) { screen() == 2 }
        }
    }

    @Test fun hitTestsFromAnOldDocumentAreDiscarded() {
        ReaderServer(3, bookFragment = { "<div><a id='go' kavita-page='2'>Go</a></div>" }).use { server ->
            open(server)
            var called = false
            var result: Any? = "not-called"
            compose.runOnIdle {
                val view = view()
                view.linkAt(0f, 0f, 0, 6) { result = it; called = true }
                view.beginSectionLoad()
            }
            compose.waitUntil(5_000) { called }; assertNull(result)
        }
    }

    @Test fun namedAnchorsAreNotLinksAndHitTestingNeverWritesProgress() {
        ReaderServer(3, bookFragment = { "<div><p style='text-align:right'><a id='named'>Named</a></p></div>" }).use { server ->
            open(server)
            val point = point("named")
            val done = CountDownLatch(1); var result: NovelLink? = null
            compose.runOnIdle { view().linkAt(point.x, point.y, 0, 6) { result = it; done.countDown() } }
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(NovelLink.None, result)
            Thread.sleep(3_200); assertTrue(server.bookProgressWrites.isEmpty())
            tap("named")
            compose.waitUntil(5_000) { server.requests.any {
                it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "1"
            } }
            awaitPage(2)
        }
    }

    private fun prose() = (1..100).joinToString("") { "<p>Paragraph $it: original prose for body link navigation and restoration.</p>" }
    private fun destination() = "<div>" + prose() + "<p id='target&apos;&quot;'>Destination prose.</p></div>"
    private fun open(server: ReaderServer) {
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
            1, 1, 1, 1, onBack = { visible.value = false }) } }
        awaitPage(1)
    }
    private fun awaitPage(page: Int) { compose.waitUntil(20_000) {
        compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().firstOrNull()?.config?.get(
            SemanticsProperties.StateDescription)?.startsWith("Page $page /") == true
    } }
    private fun state() = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun screen() = state().substringAfter("Screen ").substringBefore(" /").toInt()
    private fun assertNoMenu() = compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
    private fun menu() { compose.onRoot().performTouchInput { click(center) }; compose.waitUntil(5_000) {
        compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()
    } }
    private fun view(): NovelPageView = requireNotNull(find(compose.activity.window.decorView))
    private fun find(view: View): NovelPageView? = if (view is NovelPageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    private fun tap(id: String) { val position = point(id); compose.onRoot().performTouchInput { click(position) } }
    private fun point(id: String): Offset {
        val json = JSONObject(js("(() => { const r = Array.from(document.getElementById(${JSONObject.quote(id)}).getClientRects()).find(r => r.right>0 && r.left<innerWidth && r.bottom>0 && r.top<innerHeight); return {x:(r.left+r.right)/2/innerWidth,y:(r.top+r.bottom)/2/innerHeight}; })()"))
        val root = compose.onRoot().fetchSemanticsNode().boundsInWindow
        return compose.runOnIdle {
            val v = view(); val origin = IntArray(2); v.getLocationInWindow(origin)
            Offset(origin[0] - root.left + json.getDouble("x").toFloat() * v.width,
                origin[1] - root.top + json.getDouble("y").toFloat() * v.height)
        }
    }
    private fun js(script: String): String {
        val done = CountDownLatch(1); var result = ""
        compose.runOnIdle { view().evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }
}
