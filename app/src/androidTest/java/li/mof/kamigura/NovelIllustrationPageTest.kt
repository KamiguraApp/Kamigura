package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelIllustrationPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: NovelPageView
    private var position: NovelPosition? = null
    private val wide = mutableStateOf(false)
    private val square = mutableStateOf(false)
    private val art by lazy { image(1215, 862) }
    private val spacer by lazy { image(1, 1) }

    @Test fun spacerAndIllustrationUseOneCenteredScreenAcrossWritingAndViewportChanges() {
        create()
        for (landscape in listOf(false, true)) for (vertical in listOf(false, true)) {
            compose.runOnIdle { wide.value = landscape }
            compose.waitForIdle()
            open(sheet(), vertical)
            assertSheet(1215.0 / 862)
            assertEquals(0.0, js("document.getElementById('spacer').getBoundingClientRect().width").toDouble(), .01)
            repeat(2) { js("window.novelReader.reflow()"); assertSheet(1215.0 / 862) }
            compose.runOnIdle { view.setFontSize(150) }
            assertSheet(1215.0 / 862)
            for (margin in NovelMargins.entries) {
                compose.runOnIdle { view.setMargins(margin) }
                compose.waitUntil(5_000) { geometry().getDouble("padding") == margin.dp.toDouble() }
                assertSheet(1215.0 / 862)
            }
            for (edge in listOf("left", "top", "right", "bottom")) {
                js("window.novelReader.setPageLayout(20,{$edge:48})")
                assertSheet(1215.0 / 862)
            }
            val anchor = position!!.anchor
            open(sheet(), vertical, anchor)
            assertSheet(1215.0 / 862)
            awaitNovelPaint(view)
            val g = geometry()
            val pixels = compose.onNodeWithTag("Illustration sheet").captureToImage().toPixelMap()
            val x = ((g.getDouble("left") + g.getDouble("width") * .5) / g.getDouble("viewportWidth") * pixels.width).toInt()
            for ((fraction, red) in listOf(.1 to true, .9 to false)) {
                val y = ((g.getDouble("top") + g.getDouble("height") * fraction) / g.getDouble("viewportHeight") * pixels.height).toInt()
                val color = pixels[x, y]
                assertTrue("Entire illustration must paint: $color", if (red) color.red > .8f && color.green < .2f else color.green > .8f && color.red < .2f)
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun substantialIllustrationCanGrowButSmallImagesAndOpeningSpacersKeepTheirLayout() {
        create()
        compose.runOnIdle { square.value = true }
        compose.waitForIdle()
        for (vertical in listOf(false, true)) {
            // Still substantial, but small enough to grow inside a narrow phone viewport.
            val sourceSize = 320
            open("<div><img id='art' src='${image(sourceSize, sourceSize)}'></div>", vertical)
            assertSheet(1.0)
            assertTrue(geometry().getDouble("width") > sourceSize)
            for ((html, expected) in listOf(
                "<div><img id='small' src='${image(96, 64)}'></div>" to 96.0,
                "<div><style>.book-content #small{width:60px;height:90px}</style><img id='small' src='$art'></div>" to 60.0,
                "<div><img id='small' src='$art' style='width:128px;height:128px'></div>" to 128.0,
                "<div><img id='small' class='gaiji' src='$art' style='width:1em;height:1em'></div>" to 16.0)) {
                open(html, vertical)
                assertEquals(expected, js("document.getElementById('small').getBoundingClientRect().width").toDouble(), .2)
            }
            open("<div><p><img id='spacer' src='$spacer'>Chapter opening</p><img id='art' src='$art'></div>", vertical)
            assertTrue(js("document.getElementById('spacer').getBoundingClientRect().width").toDouble() > 0)
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun illustrationLinksAndStructuralBookmarksSurviveReflowWithoutChangingTheDom() {
        create()
        val html = sheet(link = true)
        for (vertical in listOf(false, true)) {
            open(html, vertical)
            val original = js("window.novelReader.path(document.getElementById('art'))")
            assertEquals("true", js("window.novelReader.resolve('//body/div[2]/a[1]/img[1]')===document.getElementById('art')"))
            assertEquals("true", js("window.novelReader.bookmarkContext(['//body/div[2]/a[1]/img[1]']).visible.includes(0)"))
            val region = JSONObject(js("window.novelReader.state().links.find(l=>l.page==='2')"))
            val g = geometry()
            assertEquals(g.getDouble("left"), region.getDouble("left") * g.getDouble("viewportWidth"), .5)
            assertEquals(g.getDouble("width"), (region.getDouble("right") - region.getDouble("left")) * g.getDouble("viewportWidth"), .5)
            js("window.novelReader.setVertical(${!vertical})")
            js("window.novelReader.jump('//body/div[2]/a[1]/img[1]')")
            assertSheet(1215.0 / 862)
            assertEquals(original, js("window.novelReader.path(document.getElementById('art'))"))
            assertEquals("true", js("window.novelReader.bookmarkContext(['//body/div[2]/a[1]/img[1]']).visible.includes(0)"))
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun resizingAnOpenIllustrationDoesNotCreateAnotherScreenOrLoseItsAnchor() {
        create()
        open(sheet(), false)
        val anchor = position!!.anchor
        for (vertical in listOf(false, true)) {
            compose.runOnIdle { view.setVertical(vertical) }
            for (landscape in listOf(true, false)) {
                compose.runOnIdle { wide.value = landscape }
                compose.waitForIdle()
                val expectedWidth = compose.runOnIdle { view.width / view.resources.displayMetrics.density }
                compose.waitUntil(5_000) {
                    val g = geometry()
                    kotlin.math.abs(g.getDouble("viewportWidth") - expectedWidth) < .5 &&
                        kotlin.math.abs(g.getDouble("bookWidth") - expectedWidth) < .5 &&
                        kotlin.math.abs(g.getDouble("bookHeight") - g.getDouble("viewportHeight")) < .5
                }
                assertSheet(1215.0 / 862)
                assertEquals(anchor, JSONObject(js("window.novelReader.state()")).getString("anchor"))
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    private fun create() {
        compose.setContent {
            val config = LocalConfiguration.current
            AndroidView(factory = { NovelPageView(it).also { value -> view = value; view.onPosition = { position = it } } },
                modifier = Modifier.requiredSize(minOf(if (wide.value || square.value) 700 else 360, config.screenWidthDp).dp,
                    minOf(if (wide.value) 360 else 700, config.screenHeightDp).dp).testTag("Illustration sheet"))
        }
    }
    private fun open(html: String, vertical: Boolean, anchor: String? = null) {
        position = null
        compose.runOnIdle { view.open(html, "http://127.0.0.1", 1, anchor, false, vertical) }
        compose.waitUntil(20_000) { position != null }
    }
    private fun sheet(link: Boolean = false): String = """<div><style>
        .book-content .opening{display:block;margin-top:-.1em}
        .book-content #spacer{height:auto;max-width:100%;width:.25%}
        .book-content .illustration{display:block;page-break-inside:avoid}
        .book-content #art{height:auto;width:100%}
        </style><div class='opening'><img id='spacer' src='$spacer'></div>
        <div class='illustration'>${if (link) "<a kavita-page='2' href='javascript:void(0)'>" else ""}<img id='art' src='$art'>${if (link) "</a>" else ""}</div></div>"""
    private fun assertSheet(ratio: Double) {
        val g = geometry()
        assertEquals(g.toString(), 1, g.getInt("count")); assertEquals(0, g.getInt("screen"))
        assertEquals(ratio, g.getDouble("width") / g.getDouble("height"), .005)
        val scale = minOf(g.getDouble("availableWidth") / ratio, g.getDouble("availableHeight"))
        assertEquals(g.toString(), scale, g.getDouble("height"), .5)
        assertEquals(g.getDouble("padding") + g.getDouble("availableWidth") / 2, g.getDouble("left") + g.getDouble("width") / 2, .5)
        assertEquals(g.getDouble("paddingTop") + g.getDouble("availableHeight") / 2, g.getDouble("top") + g.getDouble("height") / 2, .5)
    }
    private fun geometry() = JSONObject(js("""(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),r=document.getElementById('art').getBoundingClientRect(),v=window.novelReader.state();return {
        width:r.width,height:r.height,left:r.left,top:r.top,padding:parseFloat(s.paddingLeft),paddingTop:parseFloat(s.paddingTop),count:v.count,screen:v.screen,
        bookWidth:parseFloat(s.width),bookHeight:parseFloat(s.height),
        availableWidth:innerWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),availableHeight:innerHeight-parseFloat(s.paddingTop)-parseFloat(s.paddingBottom),viewportWidth:innerWidth,viewportHeight:innerHeight};})()"""))
    private fun js(script: String): String {
        val done = CountDownLatch(1); var result = ""
        compose.runOnIdle { view.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result
    }
    private fun image(width: Int, height: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint()
        paint.color = Color.RED; canvas.drawRect(0f, 0f, width.toFloat(), height / 2f, paint)
        paint.color = Color.GREEN; canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), paint)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
