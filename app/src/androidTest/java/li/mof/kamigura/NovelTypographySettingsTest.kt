package li.mof.kamigura

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import li.mof.kamigura.reader.novel.NovelPageView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NovelTypographySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = AppSettingsStore(context)
    private var hideReader: (() -> Unit)? = null
    private val fragment = "<div style='line-height:1.8'><p><span id='em' style='font-size:2em'>大</span>" +
        "<span id='percent' style='font-size:150%'>字</span></p>" +
        (1..180).joinToString("") { "<p id='p$it'>第 $it 段落。" + "出発した場所から同じ文章を読み続けます。".repeat(5) + "</p>" } + "</div>"

    @Test fun settingsPersistPercentagesWithoutChangingOtherReaderSettings() = withSettings {
        val before = runBlocking { store.flow.first().reader }
        compose.setContent { MaterialTheme { ReaderSettingsScreen(store, {}) } }
        setSlider("Font size", 150f)
        setSlider("Line spacing", 135f)
        compose.waitUntil { runBlocking { store.flow.first().reader.novel.let { it.fontSize == 150 && it.lineSpacing == 135 } } }
        val after = runBlocking { AppSettingsStore(context).flow.first().reader }
        assertEquals(before.image, after.image)
        assertEquals(before.common, after.common)
        assertEquals(before.novel.copy(fontSize = 150, lineSpacing = 135), after.novel)
        for ((label, range, steps) in listOf(Triple("Font size", NovelFontSizeRange, 0), Triple("Line spacing", NovelLineSpacingRange, NovelLineSpacingSteps))) {
            val info = slider(label).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(range.first.toFloat()..range.last.toFloat(), info.range)
            assertEquals(steps, info.steps)
        }
        capture("settings")
    }

    @Test fun deviceFallbackUpdatesLiveAndMenuOverrideIsAbsoluteAndSurvivesRestoreAndReopen() = withSettings {
        runBlocking { store.setNovelFontSize(150) }
        ReaderServer(3, bookFragment = { fragment },
            readingProfile = """{"kind":0,"bookReaderFontSize":220,"bookReaderLineSpacing":190}""").use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val opening = mutableStateOf(0)
            val visible = mutableStateOf(true)
            hideReader = { visible.value = false }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { key(opening.value) {
                if (visible.value) ReaderScreen(session, store, 1, 1, 1, 1, incognito = true, onBack = {})
            } } }
            loaded(); awaitFont(150)
            menu(); assertEquals(150f, sliderValue("Font size"), .01f)
            compose.onNodeWithContentDescription("Close menu").performClick()
            repeat(4) { turn() }
            val source = location()
            runBlocking { store.setNovelFontSize(160) }
            awaitFont(160); assertRetained(source)
            menu()
            assertEquals(160f, sliderValue("Font size"), .01f)
            setSlider("Font size", 170f)
            awaitFont(170)
            assertEquals(170f, sliderValue("Font size"), .01f)
            runBlocking { store.setNovelFontSize(200) }
            awaitFont(170)
            assertEquals(170f, sliderValue("Font size"), .01f)
            compose.onNodeWithContentDescription("Close menu").performClick()
            restoration.emulateSavedInstanceStateRestore()
            loaded(); awaitFont(170)
            compose.runOnIdle { opening.value++ }
            loaded(); awaitFont(170)
            menu()
            assertEquals(170f, sliderValue("Font size"), .01f)
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun bothSpacingEndpointsReflowBothWritingModesWithoutLosingTextOrRelativeFontSizes() = withSettings {
        ReaderServer(3, bookFragment = { fragment },
            readingProfile = """{"kind":2,"bookReaderFontSize":250,"bookReaderLineSpacing":180}""").use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val visible = mutableStateOf(true)
            hideReader = { visible.value = false }
            compose.setContent { MaterialTheme {
                if (visible.value) ReaderScreen(session, store, 1, 1, 1, 1, incognito = true, onBack = {})
            } }
            loaded()
            for (vertical in listOf(false, true)) {
                menu()
                compose.onNodeWithText(if (vertical) "Vertical" else "Horizontal").performClick()
                compose.onNodeWithContentDescription("Close menu").performClick()
                compose.waitUntil { JSONObject(js("window.novelReader.state()")).getBoolean("vertical") == vertical }
                repeat(3) { turn() }
                val source = location()
                for (size in listOf(100, 150)) for (spacing in listOf(100, 160, 100)) {
                    runBlocking { store.setNovelFontSize(size); store.setNovelLineSpacing(spacing) }
                    compose.waitUntil(10_000) { js("window.novelFontSize===$size&&window.novelLineSpacing===$spacing") == "true" }
                    assertRetained(source)
                    val metrics = JSONObject(js("""(()=>{const b=document.querySelector('.book-content'),p=document.getElementById('p1'),s=getComputedStyle(p);return {
                        base:parseFloat(getComputedStyle(b).fontSize),em:parseFloat(getComputedStyle(document.getElementById('em')).fontSize),
                        percent:parseFloat(getComputedStyle(document.getElementById('percent')).fontSize),line:parseFloat(s.lineHeight),
                        original:document.querySelector('.book-content').firstElementChild.style.lineHeight};})()"""))
                    val base = size * .16
                    assertEquals(base, metrics.getDouble("base"), .1)
                    assertEquals(base * 2, metrics.getDouble("em"), .1)
                    assertEquals(base * 1.5, metrics.getDouble("percent"), .1)
                    assertEquals(base * 1.8 * spacing / 100, metrics.getDouble("line"), .2)
                    if (spacing == 100) assertEquals("1.8", metrics.getString("original"))
                    assertEquals("true", js("""(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),l=parseFloat(s.paddingLeft),t=parseFloat(s.paddingTop),r=innerWidth-parseFloat(s.paddingRight),d=innerHeight-parseFloat(s.paddingBottom);
                        const w=document.createTreeWalker(b.firstElementChild,NodeFilter.SHOW_TEXT),rects=[];while(w.nextNode()){if(!w.currentNode.textContent.trim())continue;const q=document.createRange();q.selectNodeContents(w.currentNode);rects.push(...q.getClientRects());}
                        return rects.filter(q=>q.right>l+.5&&q.left<r-.5&&q.bottom>t+.5&&q.top<d-.5).every(q=>window.novelReader.state().vertical?(q.left>=l-1&&q.right<=r+1):(q.top>=t-1&&q.bottom<=d+1));})()"""))
                    if (size == 150) capture("${if (vertical) "vertical" else "horizontal"}-$spacing")
                }
            }
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun withSettings(block: () -> Unit) {
        val before = runBlocking { store.flow.first().reader.novel }
        val cache = context.cacheDir.resolve("reader_session_preferences.json")
        val originalCache = cache.takeIf { it.exists() }?.readBytes()
        try {
            runBlocking { store.setNovelFontSize(100); store.setNovelLineSpacing(100); store.setNovelWritingStyle(NovelWritingStyle.Horizontal) }
            block()
        } finally {
            compose.runOnIdle { hideReader?.invoke() }
            compose.waitForIdle()
            runBlocking { store.setNovelFontSize(before.fontSize); store.setNovelLineSpacing(before.lineSpacing); store.setNovelWritingStyle(before.writingStyle)
                ReaderSessionPreferenceCache.persist(context.cacheDir) }
            if (originalCache == null) cache.delete() else cache.writeBytes(originalCache)
            ReaderSessionPreferenceCache.clearMemoryForTest()
        }
    }
    private fun slider(label: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and hasAnyAncestor(hasContentDescription(label)))
    private fun capture(name: String) {
        view(compose.activity.window.decorView)?.let(::awaitNovelPaint)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.filesDir.resolve("test-novel-typography-$name.png")
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun setSlider(label: String, value: Float) {
        slider(label).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
    private fun sliderValue(label: String) = slider(label).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun loaded() = compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
    private fun menu() {
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitFont(size: Int) = compose.waitUntil(10_000) {
        js("parseFloat(getComputedStyle(document.querySelector('.book-content')).fontSize)").toDoubleOrNull()?.let { kotlin.math.abs(it - size * .16) < .1 } == true
    }
    private fun location(): String = JSONObject(js("window.novelReader.state()")).getString("location")
    private fun turn() {
        val screen = JSONObject(js("window.novelReader.state()")).getInt("screen")
        compose.runOnIdle {
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
            compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN))
        }
        compose.waitUntil { JSONObject(js("window.novelReader.state()")).getInt("screen") > screen }
    }
    private fun assertRetained(source: String) {
        assertEquals(source, location())
        assertEquals("true", js("""(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),p=JSON.parse(${JSONObject.quote(source)}),n=[],w=document.createTreeWalker(b.firstElementChild,NodeFilter.SHOW_TEXT);
            while(w.nextNode())if(w.currentNode.textContent.trim()&&!w.currentNode.parentElement.closest('style,script,rt,rp,.visually-hidden'))n.push(w.currentNode);
            const r=document.createRange();r.setStart(n[p.text],p.offset);r.setEnd(n[p.text],p.offset+1);return Array.from(r.getClientRects()).some(q=>q.right>parseFloat(s.paddingLeft)&&q.left<innerWidth-parseFloat(s.paddingRight)&&q.bottom>parseFloat(s.paddingTop)&&q.top<innerHeight-parseFloat(s.paddingBottom));})()"""))
    }
    private fun view(node: View): NovelPageView? = if (node is NovelPageView) node else if (node is ViewGroup)
        (0 until node.childCount).firstNotNullOfOrNull { view(node.getChildAt(it)) } else null
    private fun js(script: String): String {
        val done = CountDownLatch(1); var result = ""
        compose.runOnIdle { requireNotNull(view(compose.activity.window.decorView)).evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }
}

