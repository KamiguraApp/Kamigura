package li.mof.kamigura

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import li.mof.kamigura.reader.novel.NovelReaderPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelReaderProductionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun profileWritingStyleWinsOverImportantBookCss() {
        lateinit var view: NovelPageView
        var ready = false
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { ready = true }
            v.open("<div class='author'><style>.book-content .author{writing-mode:vertical-rl!important}</style><p>Original test prose.</p></div>",
                "http://127.0.0.1", 1, null, false, false)
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { ready }
        assertEquals("\"horizontal-tb\"", js(view, "getComputedStyle(document.querySelector('.author')).writingMode"))
        compose.runOnIdle { view.setVertical(true) }
        assertEquals("\"vertical-rl\"", js(view, "getComputedStyle(document.querySelector('.author')).writingMode"))
        compose.runOnIdle { view.dispose() }
    }

    @Test fun normalLineHeightIsMeasuredAlongTheBooksBlockAxis() {
        lateinit var view: NovelPageView
        var ready = false
        val fragment = "<div><p style='line-height:normal'>Original prose</p></div>"
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { view = it; it.onPosition = { ready = true } } },
            modifier = Modifier.fillMaxSize()) }
        for (vertical in listOf(false, true)) {
            ready = false
            compose.runOnIdle { view.open(fragment, "http://127.0.0.1", 1, null, false, vertical) }
            compose.waitUntil(20_000) { ready }
            assertEquals("\"normal\"", js(view, "document.querySelector('p').style.lineHeight"))
            val dimension = if (vertical) "width" else "height"
            val baseline = js(view, "document.querySelector('p').getBoundingClientRect().$dimension").toDouble()
            ready = false
            compose.runOnIdle { view.open(fragment, "http://127.0.0.1", 1, null, false, vertical,
                preferences = NovelReaderPreferences(lineSpacing = 150)) }
            compose.waitUntil(20_000) { ready }
            assertEquals(baseline * 1.5, js(view, "document.querySelector('p').getBoundingClientRect().$dimension").toDouble(), .2)
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun chapterBoundaryRestoresAndStillAllowsReturningToThePage() {
        ReaderServer(3, neighbor = true, spineCount = 1, bookFragment = { novelFixture(false, 1) }).use { server ->
            val visible = mutableStateOf(true)
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, incognito = true, onBack = { visible.value = false }) } }
            awaitLoaded()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithText("Next chapter").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Next chapter").fetchSemanticsNodes().isNotEmpty() &&
                    findView(compose.activity.window.decorView)?.let { js(it, "window.novelReader && window.novelReader.ready") == "true" } == true
            }
            Thread.sleep(200)
            key(KeyEvent.KEYCODE_PAGE_UP)
            awaitLoaded()
            assertTrue(state().contains("Screen 1 / 1"))
            close()
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun volumeKeysStayScopedToNovelReaderAndAreBlockedByItsMenu() {
        val settings = AppSettingsStore(context)
        val original = runBlocking { settings.flow.first().reader.turnPagesWithVolumeKeys }
        val callback = compose.activity.window.callback
        try {
            runBlocking { settings.setTurnPagesWithVolumeKeys(true) }
            ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
                val visible = mutableStateOf(true)
                open(server, visible, incognito = true)
                key(KeyEvent.KEYCODE_VOLUME_DOWN)
                compose.waitUntil(5_000) { state().contains("Screen 2 /") }
                key(KeyEvent.KEYCODE_VOLUME_UP)
                compose.waitUntil(5_000) { state().contains("Screen 1 /") }
                menu()
                key(KeyEvent.KEYCODE_VOLUME_DOWN)
                compose.onNodeWithContentDescription("Close menu").performClick()
                assertTrue(state().contains("Screen 1 /"))
                close()
                compose.waitUntil(5_000) { !visible.value }
                compose.runOnIdle { assertSame(callback, compose.activity.window.callback) }
                assertTrue(server.bookProgressWrites.isEmpty())
            }
        } finally { runBlocking { settings.setTurnPagesWithVolumeKeys(original) } }
    }

    @Test fun mixedImageAndNovelChaptersKeepTheirOwnReaderSurfaces() {
        lateinit var server: ReaderServer
        server = ReaderServer(3, neighbor = true, spineCount = 2, bookChapterFragment = { chapter, spine ->
            if (chapter == 1) "<div><img src='${server.baseUrl}/api/Book/1/book-resources?file=$spine.png'></div>"
            else novelFixture(false, 1)
        })
        server.use {
            val visible = mutableStateOf(true)
            open(server, visible, chapter = 2, incognito = true)
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Previous chapter").performClick()
            compose.waitUntil(20_000) { server.requests.any { it.path.orEmpty().endsWith("/book-resources") } }
            compose.waitUntil(5_000) { !loaded() }
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithText("Next chapter").performClick()
            awaitLoaded()
            assertTrue(state().startsWith("Section 1 /"))
            assertFalse(server.requests.any { it.path == "/api/Reader/image" })
            close()
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun closeSwipeAndThreeSecondSaveUseTheReachedBodyAnchor() {
        ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
            val visible = mutableStateOf(true)
            open(server, visible)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            assertTrue(server.bookProgressWrites.isEmpty())
            compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
            val saved = server.bookProgressWrites.last()
            assertEquals(0, saved.pageNum)
            assertTrue(saved.bookScrollId!!.startsWith("id("))
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onRoot().performTouchInput {
                swipe(Offset(width / 2f, height * .3f), Offset(width / 2f, height * .85f), 500)
            }
            compose.waitUntil(5_000) { !visible.value && server.bookProgressWrites.size >= 2 }
            assertNotEquals(saved.bookScrollId, server.bookProgressWrites.last().bookScrollId)
        }
    }

    @Test fun bodyAnchorsAndPreciseGlyphSurviveFontAndViewportReflow() {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val landscape = mutableStateOf(false)
        val fragment = "<div><p>" + "Original <ruby>novel<rt>ruby</rt></ruby> test prose. ".repeat(1600) + "</p></div>"
        compose.setContent {
            AndroidView(factory = { NovelPageView(it).also { v ->
                view = v; v.onPosition = { position = it }
                v.open(fragment, "http://127.0.0.1", 1, null, false, false)
            } }, modifier = if (landscape.value) Modifier.requiredSize(600.dp, 320.dp) else Modifier.fillMaxSize())
        }
        compose.waitUntil(20_000) { position != null }
        repeat(3) { index ->
            compose.runOnIdle { view.turn(1) { fail("Premature boundary") } }
            compose.waitUntil(5_000) { position!!.screen == index + 1 }
        }
        assertEquals("//body/p[1]", position!!.anchor)
        val before = position!!.location!!
        compose.runOnIdle { view.setFontSize(150) }
        compose.waitUntil(5_000) { js(view, "getComputedStyle(document.querySelector('.book-content')).fontSize") == "\"24px\"" }
        assertVisible(view, before)
        val afterFont = position!!.location!!
        compose.runOnIdle { landscape.value = true }
        compose.waitUntil(5_000) { js(view, "document.querySelector('.book-content').style.height") == "\"320px\"" }
        assertVisible(view, afterFont)
        compose.runOnIdle { view.setVertical(true) }
        compose.waitUntil(5_000) { position!!.vertical }
        assertVisible(view, afterFont)
        assertEquals("//body/p[1]", position!!.anchor)
        compose.runOnIdle { view.dispose() }
    }

    @Test fun profileTypographyUsesMeasuredFontAndMarginAndInheritedSpacing() {
        lateinit var view: NovelPageView
        var ready = false
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { ready = true }
            v.open("<div><p>Original test prose</p></div>", "http://127.0.0.1", 1, null, false, false,
                preferences = NovelReaderPreferences(fontSize = 150, lineSpacing = 150, margin = 25))
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { ready }
        val metrics = JSONObject(js(view, "(()=>{const s=getComputedStyle(document.querySelector('.book-content'));return {font:s.fontSize,line:s.lineHeight,margin:parseFloat(s.paddingLeft)/innerWidth};})()"))
        assertEquals("24px", metrics.getString("font"))
        assertEquals("36px", metrics.getString("line"))
        assertEquals("\"54px\"", js(view, "getComputedStyle(document.querySelector('p')).lineHeight"))
        assertEquals(.25, metrics.getDouble("margin"), .001)
        compose.runOnIdle { view.dispose() }
    }

    @Test fun defaultSpacingLeavesBookCssUntouchedAndOverridesScaleAuthoredValuesOnce() {
        lateinit var view: NovelPageView
        var ready = false
        val fragment = "<div style='line-height:1.8'><p style='line-height:2.2'>Original test prose.</p><p>Inherited prose.</p></div>"
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { ready = true }
            v.open(fragment, "http://127.0.0.1", 1, null, false, false)
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { ready }
        assertEquals("\"2.2\"", js(view, "document.querySelector('p').style.lineHeight"))
        assertEquals("\"35.2px\"", js(view, "getComputedStyle(document.querySelector('p')).lineHeight"))
        assertEquals("\"28.8px\"", js(view, "getComputedStyle(document.querySelectorAll('p')[1]).lineHeight"))
        ready = false
        compose.runOnIdle { view.open(fragment, "http://127.0.0.1", 1, null, false, false,
            preferences = NovelReaderPreferences(lineSpacing = 150)) }
        compose.waitUntil(20_000) { ready }
        assertEquals("\"52.8px\"", js(view, "getComputedStyle(document.querySelector('p')).lineHeight"))
        assertEquals("\"43.2px\"", js(view, "getComputedStyle(document.querySelectorAll('p')[1]).lineHeight"))
        compose.runOnIdle { view.setFontSize(150) }
        assertEquals("\"79.2px\"", js(view, "getComputedStyle(document.querySelector('p')).lineHeight"))
        compose.runOnIdle { view.setVertical(true) }
        assertEquals("\"79.2px\"", js(view, "getComputedStyle(document.querySelector('p')).lineHeight"))
        compose.runOnIdle { view.dispose() }
    }

    @Test fun chapterBoundariesReverseAndContinueWithoutImageRequests() {
        ReaderServer(3, neighbor = true, spineCount = 1, bookFragment = { novelFixture(false, 1) }).use { server ->
            val visible = mutableStateOf(true)
            open(server, visible)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithText("Next chapter").assertIsDisplayed()
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Next chapter").assertDoesNotExist()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithText("Next chapter").performClick()
            compose.waitUntil(20_000) { server.requests.any { it.path == "/api/Book/2/book-page" } && loaded() }
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Previous chapter").assertIsDisplayed()
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.waitUntil(20_000) { server.requests.count { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "1" } >= 2 && loaded() }
            assertTrue(server.requests.any { it.path == "/api/Reader/mark-chapter-read" })
            assertTrue(server.requests.any { it.path == "/api/Reader/mark-multiple-unread" })
            assertFalse(server.requests.any { it.path == "/api/Reader/image" })
            close()
        }
    }

    @Test fun previousChapterOpensItsLastScreenAndIncognitoNeverWrites() {
        ReaderServer(3, neighbor = true, spineCount = 1, bookFragment = { novelFixture(false) }).use { server ->
            val visible = mutableStateOf(true)
            open(server, visible, chapter = 2, incognito = true)
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Previous chapter").assertIsDisplayed()
            compose.onNodeWithText("Previous chapter").performClick()
            compose.waitUntil(20_000) { server.requests.any { it.path == "/api/Book/1/book-page" } && loaded() }
            val match = Regex("Screen (\\d+) / (\\d+)").find(state())!!
            assertTrue(match.groupValues[1].toInt() > 1)
            assertEquals(match.groupValues[2], match.groupValues[1])
            close()
            Thread.sleep(3_200)
            assertTrue(server.bookProgressWrites.isEmpty())
            assertFalse(server.requests.any { it.path.orEmpty().contains("mark-chapter") })
        }
    }

    @Test fun noNeighborKeepsImmediateReadAndUnreadExit() {
        ReaderServer(3, spineCount = 1, bookFragment = { novelFixture(false, 1) }).use { server ->
            val visible = mutableStateOf(true)
            val opening = mutableStateOf(0)
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            compose.setContent { MaterialTheme { androidx.compose.runtime.key(opening.value) {
                if (visible.value) ReaderScreen(store, AppSettingsStore(context), 1, 1, 1, 1, onBack = { visible.value = false })
            } } }
            awaitLoaded()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { !visible.value }
            compose.waitUntil(5_000) { server.requests.any { it.path == "/api/Reader/mark-chapter-read" } }
            compose.runOnIdle { opening.value++; visible.value = true }
            awaitLoaded()
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.waitUntil(5_000) { !visible.value }
            compose.waitUntil(5_000) { server.requests.any { it.path == "/api/Reader/mark-multiple-unread" } }
        }
    }

    @Test fun profileRtlAndLocalStyleOverrideSurviveReopeningAndStateRestoration() {
        ReaderServer(3, bookFragment = { novelFixture(false) }, readingProfile =
            """{"bookReaderWritingStyle":0,"bookReaderReadingDirection":1,"bookReaderFontSize":125}""").use { server ->
            val visible = mutableStateOf(true)
            val opening = mutableStateOf(0)
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { androidx.compose.runtime.key(opening.value) {
                if (visible.value) ReaderScreen(store, AppSettingsStore(context), 1, 1, 1, 1, incognito = true, onBack = { visible.value = false })
            } } }
            awaitLoaded()
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.waitUntil(5_000) { state().contains("Screen 2 /") }
            val before = findView(compose.activity.window.decorView)!!.let { js(it, "window.novelReader.state()") }
            restoration.emulateSavedInstanceStateRestore()
            awaitLoaded()
            assertVisible(findView(compose.activity.window.decorView)!!, JSONObject(before).getString("location"))
            menu()
            compose.onNodeWithText("Vertical").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Vertical") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !visible.value }
            compose.runOnIdle { opening.value++; visible.value = true }
            awaitLoaded()
            assertTrue(js(findView(compose.activity.window.decorView)!!, "window.novelReader.state()").contains("\"vertical\":true"))
            assertEquals("\"20px\"", js(findView(compose.activity.window.decorView)!!, "getComputedStyle(document.querySelector('.book-content')).fontSize"))
            menu()
            assertTrue(compose.onAllNodesWithContentDescription("Bookmark page 1").fetchSemanticsNodes().isEmpty())
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithContentDescription("Close menu").performClick()
            assertTrue(state().contains("Screen 1 /"))
            close()
            assertFalse(server.requests.any { it.path.orEmpty().contains("chapter-bookmarks") })
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun open(server: ReaderServer, visible: androidx.compose.runtime.MutableState<Boolean>, chapter: Int = 1, incognito: Boolean = false) {
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
            1, 1, 1, chapter, incognito = incognito, onBack = { visible.value = false }) } }
        awaitLoaded()
    }
    private fun loaded() = compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty()
    private fun awaitLoaded() = compose.waitUntil(20_000) { loaded() }
    private fun state() = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun menu() {
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun close() { menu(); compose.onNodeWithContentDescription("Back to series").performClick() }
    private fun key(code: Int) {
        compose.runOnIdle {
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
        Thread.sleep(100)
    }
    private fun findView(view: View): NovelPageView? = if (view is NovelPageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findView(view.getChildAt(it)) } else null
    private fun assertVisible(view: NovelPageView, location: String) {
        val geometry = JSONObject(js(view, "(()=>{const p=JSON.parse(${JSONObject.quote(location)}),nodes=[],w=document.createTreeWalker(document.querySelector('.book-content').firstElementChild,NodeFilter.SHOW_TEXT);while(w.nextNode()){const n=w.currentNode;if(n.textContent.trim()&&!n.parentElement.closest('style,script,rt,rp'))nodes.push(n);}const n=nodes[p.text],r=document.createRange();r.setStart(n,p.offset);r.setEnd(n,p.offset+1);const b=r.getBoundingClientRect();return {visible:b.right>0&&b.left<innerWidth&&b.bottom>0&&b.top<innerHeight,rect:b.toJSON(),viewport:[innerWidth,innerHeight],state:window.novelReader.state()};})()"))
        assertTrue("Retained glyph: $geometry", geometry.getBoolean("visible"))
    }
    private fun js(view: NovelPageView, script: String): String {
        val latch = CountDownLatch(1); var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
