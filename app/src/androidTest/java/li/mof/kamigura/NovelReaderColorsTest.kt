package li.mof.kamigura

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.*
import li.mof.kamigura.ui.theme.KamiguraDarkColorScheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NovelReaderColorsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allPalettesOverrideAuthoredTextAndBackgroundButPreserveIllustrations() {
        lateinit var view: NovelPageView
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFE20A36.toInt())
        val bytes = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val png = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val fragment = """<div style="color:black!important;background:white!important">
            <style>#prose::before {content:'[';color:black!important;background:white!important}</style>
            <p id="prose" style="color:black!important;background:#fff!important;-webkit-text-fill-color:black!important">
            Text <ruby>Ruby<rt style="color:red!important">reading</rt></ruby> <span style="color:blue!important;text-decoration:underline red!important">link</span></p>
            <img src="data:image/png;base64,$png" style="width:80px;height:80px">
            <svg width="80" height="80"><rect width="80" height="80" fill="currentColor"/></svg></div>"""
        var ready = false
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { ready = true }
            v.open(fragment, "http://127.0.0.1", 1, null, false,
                preferences = NovelReaderPreferences(background = PageBackground.Dark))
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { ready }
        for (pure in listOf(false, true)) for (background in PageBackground.entries) {
            val colors = novelReaderColors(background, pure)
            compose.runOnIdle { view.setColors(colors) }
            val expected = rgb(colors.text)
            assertEquals(expected, js(view, "getComputedStyle(document.querySelector('#prose')).color"))
            assertEquals(expected, js(view, "getComputedStyle(document.querySelector('rt')).color"))
            assertEquals(expected, js(view, "getComputedStyle(document.querySelector('span')).webkitTextFillColor"))
            assertEquals(expected, js(view, "getComputedStyle(document.querySelector('span')).textDecorationColor"))
            assertEquals(expected, js(view, "getComputedStyle(document.querySelector('#prose'),'::before').color"))
            assertEquals("\"rgba(0, 0, 0, 0)\"", js(view, "getComputedStyle(document.querySelector('#prose')).backgroundColor"))
            assertEquals(rgb(colors.background), js(view, "getComputedStyle(document.body).backgroundColor"))
            assertEquals("\"none\"", js(view, "getComputedStyle(document.querySelector('img')).filter"))
            assertEquals("\"rgb(0, 0, 0)\"", js(view, "getComputedStyle(document.querySelector('svg rect')).fill"))
            assertEquals("[226,10,54,255]", js(view, "(()=>{const c=document.createElement('canvas');c.width=c.height=20;c.getContext('2d').drawImage(document.querySelector('img'),0,0,20,20);return Array.from(c.getContext('2d').getImageData(10,10,1,1).data)})()"))
            val painted = CountDownLatch(1)
            compose.runOnIdle { view.postVisualStateCallback(System.nanoTime(), object : android.webkit.WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            }) }
            assertTrue(painted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            val bounds = JSONObject(js(view, "(()=>{const r=document.querySelector('img').getBoundingClientRect();return {x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,ratio:devicePixelRatio}})()"))
            val offset = IntArray(2)
            compose.runOnIdle { view.getLocationOnScreen(offset) }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertEquals(0xFFE20A36.toInt(), screenshot.getPixel(
                (offset[0] + bounds.getDouble("x") * bounds.getDouble("ratio")).toInt(),
                (offset[1] + bounds.getDouble("y") * bounds.getDouble("ratio")).toInt()))
            screenshot.recycle()
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun colorChangesNeverReflowOrReportProgressInEitherWritingMode() {
        lateinit var view: NovelPageView
        var reports = 0
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v ->
            view = v; v.onPosition = { reports++ }
            v.open(novelFixture(false), "http://127.0.0.1", 1, null, false)
        } }, modifier = Modifier.fillMaxSize()) }
        compose.waitUntil(20_000) { reports > 0 }
        Thread.sleep(500)
        for (vertical in listOf(false, true)) {
            compose.runOnIdle { view.setVertical(vertical) }
            Thread.sleep(200)
            compose.runOnIdle { view.turn(1) { fail("Unexpected boundary") } }
            compose.waitForIdle()
            val before = js(view, "window.novelReader.state()")
            val count = reports
            compose.runOnIdle { view.setColors(novelReaderColors(PageBackground.Dark, true)) }
            assertEquals(before, js(view, "window.novelReader.state()"))
            assertEquals(count, reports)
            compose.runOnIdle { view.setColors(novelReaderColors(PageBackground.Paper, false)) }
            assertEquals(before, js(view, "window.novelReader.state()"))
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun globalDefaultsAndPureColorsApplyButManualBackgroundSurvivesReopening() {
        val settings = AppSettingsStore(context)
        val original = runBlocking { settings.flow.first().reader }
        val visible = mutableStateOf(true)
        val opening = mutableStateOf(0)
        ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
            try {
                runBlocking { settings.setPageBackground(PageBackground.Paper); settings.setInvertMode(InvertMode.Smart); settings.setUsePurePageBackgroundColors(true) }
                val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
                val restoration = StateRestorationTester(compose)
                restoration.setContent { MaterialTheme(colorScheme = KamiguraDarkColorScheme) { androidx.compose.runtime.key(opening.value) {
                    if (visible.value) ReaderScreen(store, settings, 1, 1, 1, 1, incognito = true, onBack = { visible.value = false })
                } } }
                loaded()
                assertEquals("\"rgb(0, 0, 0)\"", js(readerView(), "getComputedStyle(document.body).backgroundColor"))
                menu()
                compose.onNodeWithText("Dark").assertIsOn()
                val menuImage = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(context.cacheDir, "novel-dark-menu-${menuImage.width}x${menuImage.height}.png").outputStream().use {
                    menuImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                menuImage.recycle()
                compose.onNodeWithText("Paper").performClick().assertIsOn()
                assertEquals("\"rgb(255, 255, 255)\"", js(readerView(), "getComputedStyle(document.body).backgroundColor"))
                restoration.emulateSavedInstanceStateRestore()
                loaded()
                assertEquals("\"rgb(255, 255, 255)\"", js(readerView(), "getComputedStyle(document.body).backgroundColor"))
                menu()
                compose.onNodeWithContentDescription("Back to series").performClick()
                compose.waitUntil(5_000) { !visible.value }
                runBlocking { settings.setInvertMode(InvertMode.Always); settings.setUsePurePageBackgroundColors(false) }
                compose.runOnIdle { opening.value++; visible.value = true }
                loaded()
                assertEquals("\"rgb(250, 247, 242)\"", js(readerView(), "getComputedStyle(document.body).backgroundColor"))
                menu(); compose.onNodeWithText("Paper").assertIsOn()
                assertEquals(InvertMode.Always, runBlocking { settings.flow.first().reader.invertMode })
                compose.onNodeWithContentDescription("Back to series").performClick()
                assertTrue(server.bookProgressWrites.isEmpty())
            } finally {
                runBlocking { settings.setPageBackground(original.pageBackground); settings.setInvertMode(original.invertMode); settings.setUsePurePageBackgroundColors(original.usePurePageBackgroundColors) }
            }
        }
    }

    @Test fun darkLoadingAndChapterBoundaryNeverShowPaper() {
        val settings = AppSettingsStore(context)
        val original = runBlocking { settings.flow.first().reader }
        val armed = AtomicBoolean(true)
        val gate = CountDownLatch(1)
        ReaderServer(3, neighbor = true, spineCount = 2, bookFragment = { spine ->
            if (spine == 1 && armed.get()) try { gate.await(20, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            "<div><p>Original short novel prose.</p></div>"
        }).use { server ->
            try {
                runBlocking { settings.setPageBackground(PageBackground.Dark); settings.setInvertMode(InvertMode.Off); settings.setUsePurePageBackgroundColors(false) }
                val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
                compose.setContent { MaterialTheme(colorScheme = KamiguraDarkColorScheme) { ReaderScreen(store, settings, 1, 1, 1, 1, incognito = true, onBack = {}) } }
                loaded()
                key(KeyEvent.KEYCODE_PAGE_DOWN)
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Loading EPUB section").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Loading EPUB section").assertIsDisplayed()
                compose.onNodeWithText("Opening EPUB...").assertDoesNotExist()
                compose.onNodeWithText("Back to series").assertDoesNotExist()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertEquals(0xFF101010.toInt(), screenshot.getPixel(10, screenshot.height / 2))
                screenshot.recycle()
                gate.countDown(); loaded()
                key(KeyEvent.KEYCODE_PAGE_DOWN)
                compose.onNodeWithText("Next chapter").assertIsDisplayed()
                val boundary = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertEquals(0xFF111412.toInt(), boundary.getPixel(10, boundary.height / 2))
                boundary.recycle()
                assertTrue(server.bookProgressWrites.isEmpty())
            } finally {
                gate.countDown()
                runBlocking { settings.setPageBackground(original.pageBackground); settings.setInvertMode(original.invertMode); settings.setUsePurePageBackgroundColors(original.usePurePageBackgroundColors) }
            }
        }
    }

    private fun rgb(color: Int) = "\"rgb(${color shr 16 and 255}, ${color shr 8 and 255}, ${color and 255})\""
    private fun loaded() = compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
    private fun menu() {
        Thread.sleep(200)
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun key(code: Int) { compose.runOnIdle { compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }; compose.waitForIdle() }
    private fun readerView(): NovelPageView = find(compose.activity.window.decorView)!!
    private fun find(view: View): NovelPageView? = if (view is NovelPageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
    private fun js(view: NovelPageView, script: String): String {
        val latch = CountDownLatch(1); var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return result
    }
}
