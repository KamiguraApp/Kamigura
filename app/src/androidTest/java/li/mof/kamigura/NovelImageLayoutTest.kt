package li.mof.kamigura

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelImageLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val svg = "<svg id='art' xmlns='http://www.w3.org/2000/svg' width='600' height='900' viewBox='0 0 600 900'>" +
        "<rect width='600' height='450' fill='#ff0000'/><rect y='450' width='600' height='450' fill='#00ff00'/></svg>"

    @Test fun svgCoverFitsBothColumnAxesAndDoesNotShrinkAgainOnReflow() = fit(svg)
    @Test fun rasterIllustrationFitsBothAxesAndGaijiKeepsItsEmSize() {
        val image = android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        image.recycle()
        val src = "data:image/png;base64," + android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        fit("<img id='art' src='$src' width='600' height='900'>", src)
    }

    private fun fit(art: String, gaijiSrc: String? = null) {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val wide = mutableStateOf(false)
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v -> view=v; v.onPosition={position=it} } },
            modifier=Modifier.requiredSize(if(wide.value)700.dp else 360.dp,if(wide.value)360.dp else 700.dp)) }
        val extras = if (gaijiSrc == null) "" else "<p>Prose <img id='gaiji' class='gaiji' src='$gaijiSrc'></p><img id='small' src='$gaijiSrc' style='width:60px;height:90px'>"
        val html = "<div><style>.book-content #art {width:600px!important;height:900px!important;max-height:100%!important;}" +
            ".book-content img.gaiji{width:1em!important;height:1em!important;}</style>$art$extras</div>"
        for (landscape in listOf(false,true)) for (vertical in listOf(false,true)) {
            compose.runOnIdle { wide.value=landscape }; compose.waitForIdle(); Thread.sleep(300)
            position=null
            compose.runOnIdle { view.open(html,"http://127.0.0.1",1,null,false,vertical) }
            compose.waitUntil(20_000) { position!=null }
            val before = geometry(view)
            assertFit(before)
            if (gaijiSrc != null) {
                assertEquals(16.0,before.getDouble("gaiji"),.2)
                assertEquals(60.0,before.getDouble("small"),.2)
            } else assertEquals(1,position!!.count)
            repeat(2) { js(view,"window.novelReader.reflow()") }
            val after = geometry(view)
            assertEquals(before.getDouble("width"),after.getDouble("width"),.2)
            assertEquals(before.getDouble("height"),after.getDouble("height"),.2)
            compose.runOnIdle { view.setFontSize(150) }
            val enlarged = geometry(view); assertFit(enlarged)
            if (gaijiSrc != null) assertEquals(24.0,enlarged.getDouble("gaiji"),.2)
            compose.runOnIdle { view.setFontSize(100) }
            if (gaijiSrc == null) {
                val pixels=compose.onRoot().captureToImage().toPixelMap()
                val width=before.getDouble("viewportWidth"); val height=before.getDouble("viewportHeight")
                val x=(before.getDouble("left")+before.getDouble("width")/2)/width*pixels.width
                val y=(before.getDouble("top")+before.getDouble("height")*.9)/height*pixels.height
                val bottom=pixels[x.toInt(),y.toInt()]
                assertTrue("Bottom of SVG must actually paint: $bottom",bottom.green>.8f&&bottom.red<.2f)
            }
        }
        compose.runOnIdle { view.dispose() }
    }
    private fun assertFit(value: JSONObject) {
        assertTrue(value.toString(),value.getDouble("width")<=value.getDouble("availableWidth")+.5)
        assertTrue(value.toString(),value.getDouble("height")<=value.getDouble("availableHeight")+.5)
        assertEquals(2.0/3,value.getDouble("width")/value.getDouble("height"),.005)
    }
    private fun geometry(view: NovelPageView) = JSONObject(js(view,"""(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),r=document.getElementById('art').getBoundingClientRect();return {
        width:r.width,height:r.height,left:r.left,top:r.top,availableWidth:b.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight),availableHeight:b.clientHeight-parseFloat(s.paddingTop)-parseFloat(s.paddingBottom),
        viewportWidth:innerWidth,viewportHeight:innerHeight,gaiji:document.getElementById('gaiji')?.getBoundingClientRect().width,small:document.getElementById('small')?.getBoundingClientRect().width};})()"""))
    private fun js(view: NovelPageView,script:String):String {
        val done=CountDownLatch(1); var value=""
        compose.runOnIdle {view.evaluateJavascript(script){value=it;done.countDown()}}
        assertTrue(done.await(5,TimeUnit.SECONDS));return value
    }
}
