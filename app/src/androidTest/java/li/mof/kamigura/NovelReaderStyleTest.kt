package li.mof.kamigura

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelPosition
import li.mof.kamigura.reader.novel.NovelReaderPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelReaderStyleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun readerGeometryWinsOverImportantScopedBodyRulesInBothWritingModes() {
        lateinit var view: NovelPageView
        var position: NovelPosition? = null
        val wide = mutableStateOf(false)
        val html = "<div><style>.book-content {padding:0!important;column-gap:0!important;column-width:40px!important;" +
            "column-count:99!important;width:2400px!important;height:80px!important;box-sizing:content-box!important;" +
            "column-fill:balance!important;font-size:8px!important;writing-mode:vertical-rl!important;direction:rtl!important;" +
            "color:white!important;background:black!important;line-height:20px!important;transform:translateX(10000px)!important;}</style>" +
            (1..160).joinToString("") { "<p id='p$it'>Original paragraph $it for scoped CSS pagination and retained prose.</p>" } + "</div>"
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v -> view=v; v.onPosition={position=it} } },
            modifier = Modifier.requiredSize(if(wide.value)700.dp else 360.dp,if(wide.value)360.dp else 700.dp)) }
        for(landscape in listOf(false,true)) for(vertical in listOf(false,true)) {
            compose.runOnIdle { wide.value=landscape };compose.waitForIdle();Thread.sleep(300)
            position=null
            compose.runOnIdle { view.open(html,"http://127.0.0.1",1,null,false,vertical,
                preferences=NovelReaderPreferences(fontSize=125,lineSpacing=150)) }
            compose.waitUntil(20_000){position!=null}
            val geometry=JSONObject(js(view,"""(()=>{const b=document.querySelector('.book-content'),s=getComputedStyle(b),r=b.getBoundingClientRect();return {
                width:r.width,height:r.height,viewportWidth:innerWidth,viewportHeight:innerHeight,font:s.fontSize,top:s.paddingTop,bottom:s.paddingBottom,left:parseFloat(s.paddingLeft),gap:parseFloat(s.columnGap),
                line:getComputedStyle(b.firstElementChild).lineHeight,color:getComputedStyle(document.querySelector('p')).color,
                important:['padding','column-gap','column-width','column-count','width','height','box-sizing','column-fill','font-size','writing-mode','direction','transform'].every(p=>b.style.getPropertyPriority(p)==='important')};})()"""))
            assertEquals(geometry.getDouble("viewportWidth"),geometry.getDouble("width"),.5)
            assertEquals(geometry.getDouble("viewportHeight"),geometry.getDouble("height"),.5)
            assertEquals("20px",geometry.getString("font"));assertEquals("20px",geometry.getString("top"));assertEquals("40px",geometry.getString("bottom"))
            assertEquals(geometry.getDouble("viewportWidth")*.15,geometry.getDouble("left"),.5)
            assertEquals(if(vertical)60.0 else geometry.getDouble("viewportWidth")*.3,geometry.getDouble("gap"),.5)
            assertEquals("30px",geometry.getString("line"));assertEquals("rgb(16, 16, 16)",geometry.getString("color"))
            assertTrue(geometry.toString(),geometry.getBoolean("important"));assertTrue(position!!.count>6)
            compose.runOnIdle { view.turn(1){fail("Premature boundary")} };compose.waitUntil(5_000){position!!.screen==1}
            val anchor=position!!.anchor
            compose.runOnIdle { view.setFontSize(150) };js(view,"window.novelReader.reflow()")
            assertEquals("30px",JSONObject("{\"v\":"+js(view,"getComputedStyle(document.querySelector('.book-content').firstElementChild).lineHeight")+"}").getString("v"))
            assertTrue(anchor.isNotBlank())
        }
        compose.runOnIdle{view.dispose()}
    }
    private fun js(view:NovelPageView,script:String):String {
        val done=CountDownLatch(1);var value=""
        compose.runOnIdle{view.evaluateJavascript(script){value=it;done.countDown()}}
        assertTrue(done.await(5,TimeUnit.SECONDS));return value
    }
}
