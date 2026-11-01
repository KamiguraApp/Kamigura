package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import li.mof.kamigura.reader.novel.novelResourceAllowed
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelSvgViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var view: NovelPageView
    private var position: NovelPosition? = null

    @Test fun svgPaintedBoundsRespectAspectRatioAndSmallerImageInBothWritingModes() {
        ReaderServer(3, cacheImages = true, imageBytes = raster()).use { server ->
            surface()
            for (vertical in listOf(false, true)) {
                // x/y/width/height and independently calculated painted rectangle in SVG user units.
                for ((attributes, expected) in listOf(
                    "x='0' y='0' width='1000' height='1000'" to doubleArrayOf(0.0, (1000 - 1000 * 1453.0 / 2048) / 2, 1000.0, 1000 * 1453.0 / 2048),
                    "x='200' y='250' width='600' height='500'" to doubleArrayOf(200.0, 250 + (500 - 600 * 1453.0 / 2048) / 2, 600.0, 600 * 1453.0 / 2048),
                    "x='100' y='100' width='800' height='800' preserveAspectRatio='xMaxYMin meet'" to doubleArrayOf(100.0, 100.0, 800.0, 800 * 1453.0 / 2048),
                    "x='200' y='250' width='600' height='500' preserveAspectRatio='none'" to doubleArrayOf(200.0, 250.0, 600.0, 500.0),
                    "x='200' y='250' width='600' height='500' preserveAspectRatio='xMidYMid slice'" to doubleArrayOf(200.0, 250.0, 600.0, 500.0)
                )) {
                    open(svg(server.baseUrl, attributes), server.baseUrl, vertical)
                    val before = snapshot()
                    prime()
                    val region = regions().getJSONObject(0)
                    val geometry = JSONObject(js("""(()=>{const m=document.getElementById('art').getScreenCTM();return {a:m.a,d:m.d,e:m.e,f:m.f,w:innerWidth,h:innerHeight};})()"""))
                    val expectedLeft = expected[0] * geometry.getDouble("a") + geometry.getDouble("e")
                    val expectedTop = expected[1] * geometry.getDouble("d") + geometry.getDouble("f")
                    assertEquals(expectedLeft, region.getDouble("left") * geometry.getDouble("w"), .6)
                    assertEquals(expectedTop, region.getDouble("top") * geometry.getDouble("h"), .6)
                    assertEquals(expected[2] * geometry.getDouble("a"), (region.getDouble("right") - region.getDouble("left")) * geometry.getDouble("w"), .6)
                    assertEquals(expected[3] * geometry.getDouble("d"), (region.getDouble("bottom") - region.getDouble("top")) * geometry.getDouble("h"), .6)
                    assertTrue(novelResourceAllowed(hit(center(region))!!, server.baseUrl, 1))
                    // The smaller image and meet letterboxing must not turn the entire SVG into a hit.
                    if (!attributes.contains("slice") && !attributes.contains("none")) {
                        val outer = JSONObject(js("""(()=>{const r=document.querySelector('svg').getBoundingClientRect();return {x:(r.left+r.width*.5)/innerWidth,y:(r.top+r.height*.01)/innerHeight};})()"""))
                        if (outer.getDouble("y") < region.getDouble("top")) assertNull(hit(Offset((outer.getDouble("x") * view.width).toFloat(), (outer.getDouble("y") * view.height).toFloat())))
                    }
                    assertEquals(before, snapshot())
                }
            }
        }
    }

    @Test fun nestedSvgLetterboxingAndRotatedImageUseOnlyPaintedArea() {
        ReaderServer(3, cacheImages = true, imageBytes = raster()).use { server ->
            surface()
            for (vertical in listOf(false, true)) {
                val nested = svg(server.baseUrl, "width='1000' height='1000'", nested = true)
                open(nested, server.baseUrl, vertical); prime()
                val region = regions().getJSONObject(0)
                assertNotNull(hit(center(region)))
                val blank = JSONObject(js("""(()=>{const r=document.querySelector('svg').getBoundingClientRect();return {x:(r.left+r.width*.11)/innerWidth,y:(r.top+r.height*.5)/innerHeight};})()"""))
                assertNull(hit(Offset((blank.getDouble("x") * view.width).toFloat(), (blank.getDouble("y") * view.height).toFloat())))
                open(svg(server.baseUrl, "x='200' y='200' width='600' height='600' transform='rotate(30 500 500)'"), server.baseUrl, vertical)
                prime()
                val rotated = regions().getJSONObject(0)
                assertNotNull(hit(center(rotated)))
                val corner = Offset(((rotated.getDouble("left") + .002) * view.width).toFloat(), ((rotated.getDouble("top") + .002) * view.height).toFloat())
                assertNull(hit(corner))
            }
        }
    }

    @Test fun vectorOnlyGaijiSmallHiddenAndSpacerImagesDoNotOpen() {
        ReaderServer(3, cacheImages = true, imageBytes = raster()).use { server ->
            surface()
            for (vertical in listOf(false, true)) for (fragment in listOf(
                "<div><svg viewBox='0 0 1000 1000' width='1000' height='1000'><rect width='1000' height='1000' fill='blue'/></svg></div>",
                svg(server.baseUrl, "width='1000' height='1000' class='gaiji'"),
                svg(server.baseUrl, "width='20' height='20'"),
                svg(server.baseUrl, "width='1' height='1'"),
                svg(server.baseUrl, "width='1000' height='1000' style='visibility:hidden'"),
                svg(server.baseUrl, "x='1300' width='1000' height='1000'")
            )) {
                open(fragment, server.baseUrl, vertical); prime()
                assertEquals(0, regions().length())
                assertNull(hit(Offset(view.width / 2f, view.height / 2f)))
            }
        }
    }

    @Test fun horizontalSvgKuchieOpensSharpViewerAndKeepsBookmarksAndPosition() = reader(false)
    @Test fun verticalSvgCoverPinchOpensSharpViewerAndKeepsBookmarksAndPosition() = reader(true)

    private fun reader(vertical: Boolean) {
        val width = if (vertical) 1453 else 2048
        val height = if (vertical) 2048 else 1453
        ReaderServer(3, cacheImages = true, imageBytes = raster(width, height), bookFragment = {
            if (it == 0) "<div class='novel'><style>.book-content .novel{writing-mode:${if (vertical) "vertical-rl" else "horizontal-tb"}}</style>" +
                svgBody("", "width='$width' height='$height'", "0 0 $width $height",
                    file = if (vertical) "fixture-cover.jpg" else "kuchie-002-003.jpg") + "</div>" else novelFixture(vertical, 5)
        }).use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            compose.setContent { MaterialTheme { ReaderScreen(session, AppSettingsStore(context), 1, 1, 1, 1, incognito = true, onBack = {}) } }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { view = find(compose.activity.window.decorView)!! }
            prime()
            assertEquals(vertical, JSONObject(js("window.novelReader.state()")).getBoolean("vertical"))
            val before = snapshot()
            val path = js("window.novelReader.path(document.querySelector('svg'))")
            val imagePath = js("window.novelReader.path(document.getElementById('art'))")
            val outer = js("document.getElementById('art').outerHTML")
            assertEquals("true", js("window.novelReader.bookmarkContext([$path]).visible.includes(0)"))
            val region = regions().getJSONObject(0)
            val point = center(region)
            if (vertical) compose.onRoot().performTouchInput {
                down(0, point - Offset(60f, 0f)); down(1, point + Offset(60f, 0f))
                moveTo(0, point - Offset(100f, 0f), 150); moveTo(1, point + Offset(100f, 0f), 150)
                moveTo(0, point - Offset(140f, 0f), 150); moveTo(1, point + Offset(140f, 0f), 150)
                up(0); up(1)
            } else compose.onRoot().performTouchInput { doubleClick(point) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close illustration").fetchSemanticsNodes().isNotEmpty() }
            if (!vertical) compose.onNodeWithContentDescription("Illustration viewer").performTouchInput { doubleClick(center) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("Reader sharp image").fetchSemanticsNodes().isNotEmpty() }
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.getExternalFilesDir(null)!!.resolve("svg-viewer-${if (vertical) "vertical" else "horizontal"}-${screenshot.width}x${screenshot.height}.png")
                .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
            compose.onNodeWithContentDescription("Close illustration").performClick()
            assertEquals(before, snapshot()); assertTrue(outer == js("document.getElementById('art').outerHTML"))
            assertEquals(path, js("window.novelReader.path(document.querySelector('svg'))"))
            assertEquals(imagePath, js("window.novelReader.path(document.getElementById('art'))"))
            assertEquals("true", js("window.novelReader.bookmarkContext([$path]).visible.includes(0)"))
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun surface() { compose.setContent {
        AndroidView(factory = { NovelPageView(it).also { v -> view = v; v.onPosition = { position = it } } }, modifier = Modifier.fillMaxSize())
        DisposableEffect(Unit) { onDispose { view.dispose() } }
    } }
    private fun open(html: String, base: String, vertical: Boolean) {
        position = null
        compose.runOnIdle { view.open(html, base, 1, null, false, vertical) }
        compose.waitUntil(15_000) { position != null }
    }
    private fun prime() { hit(Offset.Zero) }
    private fun hit(point: Offset): String? {
        val done = CountDownLatch(1); var value: String? = null
        compose.runOnIdle { view.illustrationAt(point) { value = it; done.countDown() } }
        assertTrue(done.await(12, TimeUnit.SECONDS)); return value
    }
    private fun regions() = JSONArray(js("window.novelReader.illustrations().map(({url,...region})=>region)"))
    private fun center(region: JSONObject) = Offset(((region.getDouble("left") + region.getDouble("right")) * view.width / 2).toFloat(),
        ((region.getDouble("top") + region.getDouble("bottom")) * view.height / 2).toFloat())
    private fun snapshot(): String = js("JSON.stringify((({screen,count,vertical,anchor,location})=>({screen,count,vertical,anchor,location}))(window.novelReader.state()))")
    private fun js(script: String): String {
        val done = CountDownLatch(1); var value = ""
        compose.runOnIdle { view.evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value
    }
    private fun find(v: View): NovelPageView? {
        if (v is NovelPageView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun svg(base: String, attrs: String, nested: Boolean = false) = "<div>" + svgBody(base, attrs, "0 0 1000 1000", nested) + "</div>"
    private fun svgBody(base: String, attrs: String, box: String, nested: Boolean = false, file: String = "kuchie-002-003.jpg"): String =
        """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="$box" width="1000" height="1000"><rect width="100%" height="100%" fill="blue"/>${if (nested) "<svg x='100' y='200' width='800' height='600' viewBox='0 0 1000 1000'>" else ""}<image id="art" $attrs xlink:href="$base/api/Book/1/book-resources?file=OEBPS/Images/$file&amp;apiKey=fixture-svg-secret"/>${if (nested) "</svg>" else ""}</svg>"""
    private fun raster(width: Int = 2048, height: Int = 1453): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.RED)
        canvas.drawRect(0f, height / 2f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.GREEN })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
}
