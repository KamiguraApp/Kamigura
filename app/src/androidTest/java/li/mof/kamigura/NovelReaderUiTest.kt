package li.mof.kamigura

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.view.View
import android.view.ViewGroup

internal fun novelFixture(vertical: Boolean, paragraphs: Int = 100): String = buildString {
    append("<div class='novel'><style>.book-content .novel { writing-mode:")
    append(if (vertical) "vertical-rl" else "horizontal-tb")
    append("; } .book-content p {margin:0 0 1em;}</style>")
    repeat(paragraphs) { index ->
        append("<p id='paragraph-$index'>")
        repeat(4) { append(if (vertical) "これは<ruby>試験<rt>しけん</rt></ruby>の文章です。縦書きのページを読みます。" else "This is a paragraph of original test prose with several words for pagination. ") }
        append("</p>")
    }
    append("</div>")
}

@RunWith(AndroidJUnit4::class)
class NovelReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeReaderMenuShowsCompactControlsAndScreenPosition() {
        ReaderServer(3, spineCount = 21, bookFragment = { novelFixture(true) },
            readingProfile = """{"bookReaderWritingStyle":1,"bookReaderFontSize":100}""").use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, incognito = true, initialPage = 15, onBack = { visible.value = false }) } }
            awaitSection(16)
            openMenu()
            compose.onNodeWithText("Page 16 / 21", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Screen 1 /", substring = true).assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("100%").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("complete", substring = true).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("Vertical").assertIsOn()
            compose.onNodeWithText("Writing").performScrollTo()
            val header = compose.onNodeWithContentDescription("Close menu").fetchSemanticsNode().boundsInRoot
            val panel = compose.onNodeWithText("Writing").fetchSemanticsNode().boundsInRoot
            assertTrue(header.bottom <= panel.top)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "b-ui2-native-${bitmap.width}x${bitmap.height}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !visible.value }
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun verticalMulticolKeepsRubyAndRestoresXPath() = surface(true)
    @Test fun horizontalMulticolRestoresXPath() = surface(false)

    private fun surface(vertical: Boolean) {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        compose.setContent {
            AndroidView(factory = { NovelPageView(it).also { created ->
                view = created
                created.onPosition = { position = it }
                created.open(novelFixture(vertical), "http://127.0.0.1", 1, null, false)
            } }, modifier = Modifier.fillMaxSize())
        }
        compose.waitUntil(20_000) { position != null }
        compose.waitForIdle()
        Thread.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        var dark = 0
        for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step 3) for (x in 20 until bitmap.width - 20 step 3) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) < 100) dark++
        }
        bitmap.recycle()
        assertTrue("Text must actually paint: $dark dark pixels", dark > 100)
        assertEquals(vertical, position!!.vertical)
        assertTrue("Multiple screens: $position", position!!.count > 3)
        val first = position!!
        compose.runOnIdle { view.turn(1) { fail("Premature boundary") } }
        compose.waitUntil(3_000) { position!!.screen == 1 }
        val second = position!!
        assertNotEquals(first.anchor, second.anchor)
        val path = js(view, "window.novelReader.path(window.novelReader.resolve(${JSONObject.quote(second.anchor)}))")
        assertEquals(second.anchor, JSONObject("{\"value\":$path}").getString("value"))
        assertEquals(vertical, js(view, "getComputedStyle(document.querySelector('.novel')).writingMode").contains("vertical"))
        assertEquals(vertical.toString(), js(view, "document.querySelector('ruby') !== null"))
        compose.runOnIdle { view.open(novelFixture(vertical), "http://127.0.0.1", 1, second.anchor, false) }
        position = null
        compose.waitUntil(20_000) { position != null }
        assertEquals("true", js(view, "Array.from(window.novelReader.resolve(${JSONObject.quote(second.anchor)}).getClientRects())" +
            ".some(r => r.right > 0 && r.left < innerWidth && r.bottom > 0 && r.top < innerHeight)"))
        compose.runOnIdle { view.dispose() }
    }

    @Test fun fragmentScriptsCannotRunAndStructuralXPathRoundTrips() {
        lateinit var view: NovelPageView
        var ready = false
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { ready = true }
            v.open("<div><div><p>A</p><span>B</span><p>C</p></div><script>window.epubExecuted=true</script></div>",
                "http://127.0.0.1", 1, null, false)
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { ready }
        assertEquals("false", js(view, "window.epubExecuted === true"))
        assertEquals("\"//body/div[1]/p[2]\"", js(view, "window.novelReader.path(document.querySelectorAll('p')[1])"))
        assertEquals("\"C\"", js(view, "window.novelReader.resolve('//body/div[1]/p[2]').textContent"))
        compose.runOnIdle { view.dispose() }
    }

    @Test fun readerTapsMenuCloseAndImmediateProgress() = reader(false, false)
    @Test fun readerVerticalTapsUseRightToLeftAndIncognitoDoesNotWrite() = reader(true, true)

    @Test fun writingModeSelectorChangesTapDirection() {
        ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, incognito = true, onBack = { visible.value = false }) } }
            awaitSection(1)
            openMenu()
            compose.onNodeWithText("Vertical").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Vertical") and isOn()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { click(Offset(width * .1f, height * .5f)) }
            compose.waitUntil(5_000) {
                compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("Screen 2 /")
            }
            openMenu()
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !visible.value }
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun spineTransitionsSaveTheReachedSpineInsteadOfTheScreenNumber() {
        ReaderServer(3, bookFragment = { novelFixture(false, 1) }).use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, initialPage = 1, onBack = { visible.value = false }) } }
            awaitSection(2)
            compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
            awaitSection(3)
            compose.onRoot().performTouchInput { click(Offset(width * .1f, height * .5f)) }
            awaitSection(2)
            openMenu()
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
            assertEquals(1, server.bookProgressWrites.last().pageNum)
            assertEquals("id(\"paragraph-0\")", server.bookProgressWrites.last().bookScrollId)
        }
    }

    @Test fun serverXPathResumesInsideSpineAndUntouchedCloseDoesNotWrite() {
        ReaderServer(3, bookFragment = { novelFixture(false) },
            bookProgress = ProgressDto(1, 1, 1, 1, 1, "id(\"paragraph-40\")")).use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, onBack = { visible.value = false }) } }
            awaitSection(2)
            val state = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            assertTrue(state.substringAfter("Screen ").substringBefore(" / ").toInt() > 1)
            openMenu()
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !visible.value }
            Thread.sleep(3_500)
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun closeDuringSectionLoadIgnoresOldLayoutCallbacks() {
        val hold = AtomicBoolean(false)
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        ReaderServer(3, bookFragment = { spine ->
            if (spine == 2 && hold.get()) {
                requested.countDown()
                try { release.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            novelFixture(false, 1)
        }).use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            try {
                compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                    1, 1, 1, 1, initialPage = 1, onBack = { visible.value = false }) } }
                awaitSection(2)
                hold.set(true)
                compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
                compose.waitUntil(5_000) { requested.count == 0L }
                compose.runOnIdle {
                    findNovelView(compose.activity.window.decorView)!!.onPosition(
                        NovelPosition(12, 27, false, "id(\"wrong-spine\")"))
                }
                compose.onNodeWithText("Back to series").performClick()
                compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
                assertEquals(1, server.bookProgressWrites.last().pageNum)
                assertEquals("id(\"paragraph-0\")", server.bookProgressWrites.last().bookScrollId)
            } finally { release.countDown() }
        }
    }

    private fun findNovelView(view: View): NovelPageView? = if (view is NovelPageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findNovelView(view.getChildAt(it)) } else null

    private fun openMenu() {
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSection(section: Int) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().firstOrNull()?.config?.get(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription)?.startsWith("Section $section /") == true
        }
    }

    private fun reader(vertical: Boolean, incognito: Boolean) {
        ReaderServer(3, bookFragment = { novelFixture(vertical) },
            readingProfile = """{"bookReaderWritingStyle":${if (vertical) 1 else 0}}""").use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (visible.value) ReaderScreen(store, AppSettingsStore(context),
                1, 1, 1, 1, incognito = incognito, onBack = { visible.value = false }) } }
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().performTouchInput { click(Offset(if (vertical) width * .1f else width * .9f, height * .5f)) }
            compose.waitUntil(3_000) {
                compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("Screen 2 /")
            }
            openMenu()
            compose.onNodeWithText("Page 1 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Screen 2 /", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close menu").performClick()
            openMenu()
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !visible.value }
            if (incognito) {
                Thread.sleep(3_500)
                assertTrue(server.bookProgressWrites.isEmpty())
            } else {
                compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
                assertEquals(0, server.bookProgressWrites.last().pageNum)
                assertTrue(server.bookProgressWrites.last().bookScrollId!!.startsWith("id("))
            }
            assertFalse(server.requests.any { it.path == "/api/Reader/image" })
        }
    }

    private fun js(view: NovelPageView, script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { value = it; latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return value
    }
}
