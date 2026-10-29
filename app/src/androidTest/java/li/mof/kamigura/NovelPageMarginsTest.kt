package li.mof.kamigura

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import li.mof.kamigura.ui.theme.KamiguraTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NovelPageMarginsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fragment = "<div><style>.book-content p{margin:0 0 1em;line-height:1.6}</style>" +
        (1..180).joinToString("") { "<p id='p$it'>第 $it 段落。${"ページの余白と読み位置を確かめるための本文です。".repeat(5)}</p>" } + "</div>"

    @Test fun marginsPersistWithoutChangingImageSettings() {
        val store = AppSettingsStore(context)
        val original = runBlocking { store.flow.first().reader }
        try {
            compose.setContent { KamiguraTheme { ReaderSettingsScreen(store, {}) } }
            compose.onNodeWithText("Margins").performScrollTo().assertIsDisplayed()
            for (margin in NovelMargins.entries) {
                compose.onNodeWithText(margin.name).performScrollTo().performClick()
                compose.waitUntil { runBlocking { AppSettingsStore(context).flow.first().reader.novel.margins == margin } }
                assertEquals(original.image, runBlocking { store.flow.first().reader.image })
                assertEquals(original.common, runBlocking { store.flow.first().reader.common })
            }
        } finally { runBlocking { store.setNovelMargins(original.novel.margins) } }
    }

    @Test fun asymmetricPaddingPreservesColumnStrideLinksAndIllustrationFit() {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val html = fragment.replace("</div>", "<p><a id='link' kavita-page='1'>Open next section</a></p>" +
            "<svg id='art' width='2000' height='3000' viewBox='0 0 2000 3000'><rect width='2000' height='3000' fill='green'/></svg></div>")
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v -> view = v; v.onPosition = { position = it } } },
            modifier = Modifier.requiredSize(360.dp, 620.dp)) }
        for (vertical in listOf(false, true)) {
            position = null
            compose.runOnIdle { view.open(html, "http://127.0.0.1", 1, null, false, vertical) }
            compose.waitUntil(20_000) { position != null }
            Thread.sleep(400)
            for (margin in NovelMargins.entries) for (edge in listOf("none", "left", "top", "right", "bottom")) {
                val cutout = JSONObject().put("left", 0).put("top", 0).put("right", 0).put("bottom", 0)
                if (edge != "none") cutout.put(edge, 48)
                js(view, "window.novelReader.setPageLayout(${margin.dp},$cutout)")
                js(view, "window.novelReader.jump('id(\"p1\")')")
                val geometry = JSONObject(js(view, """(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),a=document.getElementById('art').getBoundingClientRect();return {
                    left:parseFloat(s.paddingLeft),top:parseFloat(s.paddingTop),right:parseFloat(s.paddingRight),bottom:parseFloat(s.paddingBottom),
                    gap:parseFloat(s.columnGap),width:innerWidth,height:innerHeight,artWidth:a.width,artHeight:a.height};})()"""))
                for (name in listOf("left", "top", "right", "bottom")) assertEquals(if (edge == name) 56.0 else margin.dp.toDouble(), geometry.getDouble(name), .1)
                assertEquals(if (vertical) geometry.getDouble("top") + geometry.getDouble("bottom")
                    else geometry.getDouble("left") + geometry.getDouble("right"), geometry.getDouble("gap"), .1)
                assertTrue(geometry.getDouble("artWidth") <= geometry.getDouble("width") - geometry.getDouble("left") - geometry.getDouble("right") + 1)
                assertTrue(geometry.getDouble("artHeight") <= geometry.getDouble("height") - geometry.getDouble("top") - geometry.getDouble("bottom") + 1)
                val before = JSONObject(js(view, "window.novelReader.state()"))
                val first = JSONObject(js(view, "document.getElementById('p1').getBoundingClientRect().toJSON()"))
                js(view, "window.novelReader.turn(1)")
                val second = JSONObject(js(view, "document.getElementById('p1').getBoundingClientRect().toJSON()"))
                val axis = if (vertical) "top" else "left"
                assertEquals(geometry.getDouble(if (vertical) "height" else "width"), first.getDouble(axis) - second.getDouble(axis), .5)
                assertEquals(before.getInt("count"), JSONObject(js(view, "window.novelReader.state()")).getInt("count"))
                js(view, "window.novelReader.jump('id(\"link\")')")
                assertEquals("true", js(view, "window.novelReader.state().links.some(r=>r.page==='1'&&r.left>=0&&r.right<=1&&r.top>=0&&r.bottom<=1)"))
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun nativeCutoutsMarginsAndRotationKeepTheReachedCharacter() {
        val store = AppSettingsStore(context)
        val original = runBlocking { store.flow.first().reader }
        val oldAuto = shell("settings get system accelerometer_rotation").trim()
        val oldRotation = shell("settings get system user_rotation").trim()
        val app = compose.activity.application
        var active: ComponentActivity = compose.activity
        var attach: (ComponentActivity) -> Unit = {}
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is ComponentActivity && activity !== active) { active = activity; attach(activity) }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val results = JSONArray()
        ReaderServer(3, bookFragment = { fragment }, readingProfile = """{"kind":0,"bookReaderReadingDirection":1,"bookReaderMargin":45}""").use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val content: @androidx.compose.runtime.Composable () -> Unit = {
                KamiguraTheme { ReaderScreen(session, store, 1, 1, 1, 1, incognito = true, onBack = {}) }
            }
            attach = { activity -> activity.setContent(content = content) }
            try {
                runBlocking { store.setNovelBackground(PageBackground.Paper); store.setNovelWritingStyle(NovelWritingStyle.Horizontal); store.setUsePurePageBackgroundColors(false) }
                app.registerActivityLifecycleCallbacks(callbacks)
                compose.setContent(content)
                shell("settings put system accelerometer_rotation 0")
                for (vertical in listOf(false, true)) {
                    loaded(active)
                    compose.onRoot().performTouchInput { click(center) }
                    compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText(if (vertical) "Vertical" else "Horizontal").performClick()
                    compose.onNodeWithContentDescription("Close menu").performClick()
                    repeat(4) {
                        val before = state(view(active)).getInt("screen")
                        instrumentation.runOnMainSync { active.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN))
                            active.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)) }
                        compose.waitUntil(5_000) { state(view(active)).getInt("screen") > before }
                    }
                    val source = state(view(active)).getString("location")
                    for (rotation in listOf(0, 1, 3)) {
                        val previous = active
                        val previousRotation = active.window.decorView.display?.rotation
                        shell("settings put system user_rotation $rotation")
                        compose.waitUntil(10_000) { active.window.decorView.display?.rotation == rotation &&
                            (previousRotation?.rem(2) == rotation % 2 || active !== previous) }
                        loaded(active)
                        for (margin in NovelMargins.entries) {
                            runBlocking { store.setNovelMargins(margin) }
                            compose.waitUntil(10_000) { geometryMatches(view(active), margin) }
                            val v = view(active)
                            assertEquals(source, state(v).getString("location"))
                            assertEquals("true", js(v, """(()=>{const book=document.querySelector('.book-content'),s=getComputedStyle(book),p=JSON.parse(${JSONObject.quote(source)}),w=document.createTreeWalker(book.firstElementChild,NodeFilter.SHOW_TEXT),n=[];while(w.nextNode())if(w.currentNode.textContent.trim()&&!w.currentNode.parentElement.closest('style,script,rt,rp,.visually-hidden'))n.push(w.currentNode);const r=document.createRange();r.setStart(n[p.text],p.offset);r.setEnd(n[p.text],p.offset+1);return Array.from(r.getClientRects()).some(b=>b.right>parseFloat(s.paddingLeft)&&b.left<innerWidth-parseFloat(s.paddingRight)&&b.bottom>parseFloat(s.paddingTop)&&b.top<innerHeight-parseFloat(s.paddingBottom))})()"""))
                            val raw = JSONObject(js(v, "window.novelCutout"))
                            if (InstrumentationRegistry.getArguments().getString("expectDisplayCutout") == "true") assertTrue(raw.toString(), listOf("left", "top", "right", "bottom").any { raw.getDouble(it) > 0 })
                            results.put(JSONObject().put("vertical", vertical).put("rotation", rotation).put("margin", margin.name).put("cutout", raw).put("state", state(v)))
                            awaitNovelPaint(v)
                            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                            val device = if (InstrumentationRegistry.getArguments().getString("expectDisplayCutout") == "true") "phone" else "tablet"
                            File(context.cacheDir, "margins-$device-$vertical-$rotation-${margin.name}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        }
                    }
                }
                assertTrue(server.bookProgressWrites.isEmpty())
            } finally {
                File(context.cacheDir, "margins-native-results.json").writeText(results.toString(2))
                app.unregisterActivityLifecycleCallbacks(callbacks)
                shell("settings put system user_rotation $oldRotation"); shell("settings put system accelerometer_rotation $oldAuto")
                runBlocking { store.setNovelMargins(original.novel.margins); store.setNovelWritingStyle(original.novel.writingStyle)
                    store.setNovelBackground(original.novel.background); store.setUsePurePageBackgroundColors(original.common.usePurePageBackgroundColors) }
            }
        }
    }

    private fun geometryMatches(v: NovelPageView, margin: NovelMargins): Boolean = runCatching {
        val value = JSONObject(js(v, """(()=>{const s=getComputedStyle(document.querySelector('.book-content'));return {width:innerWidth,height:innerHeight,ratio:devicePixelRatio,left:parseFloat(s.paddingLeft),top:parseFloat(s.paddingTop),right:parseFloat(s.paddingRight),bottom:parseFloat(s.paddingBottom)}})()"""))
        val point = IntArray(2); val root = IntArray(2)
        var expected = li.mof.kamigura.reader.novel.NovelPageInsets()
        instrumentation.runOnMainSync {
            v.getLocationOnScreen(point); v.rootView.getLocationOnScreen(root)
            if (Build.VERSION.SDK_INT >= 28) v.rootWindowInsets?.displayCutout?.let { c -> expected =
                li.mof.kamigura.reader.novel.NovelPageInsets(c.safeInsetLeft.toFloat(), c.safeInsetTop.toFloat(), c.safeInsetRight.toFloat(), c.safeInsetBottom.toFloat())
                    .relativeToView(point[0] - root[0], point[1] - root[1], root[0] + v.rootView.width - point[0] - v.width,
                        root[1] + v.rootView.height - point[1] - v.height, v.resources.displayMetrics.density) }
        }
        val insets = listOf(expected.left, expected.top, expected.right, expected.bottom)
        listOf("left", "top", "right", "bottom").withIndex().all { (index, edge) ->
            kotlin.math.abs(value.getDouble(edge) - maxOf(margin.dp.toDouble(), if (insets[index] > 0) insets[index] + 8.0 else 0.0)) < .6
        } && kotlin.math.abs(value.getDouble("width") * value.getDouble("ratio") - v.width) < 2 &&
            kotlin.math.abs(value.getDouble("height") * value.getDouble("ratio") - v.height) < 2
    }.getOrDefault(false)
    private fun loaded(activity: ComponentActivity) = compose.waitUntil(25_000) {
        compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() &&
            runCatching { state(view(activity)).getInt("count") > 1 }.getOrDefault(false)
    }
    private fun view(activity: ComponentActivity): NovelPageView = find(activity.window.decorView) ?: error("Novel view not ready")
    private fun find(v: View): NovelPageView? = if (v is NovelPageView) v else if (v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) } else null
    private fun state(v: NovelPageView) = JSONObject(js(v, "window.novelReader && window.novelReader.ready ? window.novelReader.state() : null"))
    private fun js(v: NovelPageView, script: String): String {
        val done = CountDownLatch(1); var value = ""
        instrumentation.runOnMainSync { v.evaluateJavascript(script) { value = it; done.countDown() } }
        check(done.await(3, TimeUnit.SECONDS)); return value
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
