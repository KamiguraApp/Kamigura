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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelAnchorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: NovelPageView
    private var position: NovelPosition? = null
    private val wide = mutableStateOf(false)
    private val prose = "Original paragraph with enough text for independently tested reading positions. "
    private val fragment = "<div><div class='single-wrapper'>" + (1..180).joinToString("") {
        "<p style='margin:0'><span>${prose.repeat(3)}Paragraph $it.</span></p>"
    } + "</div></div>"

    @Test fun singleWrapperRestoresTheContainingScreenWithoutChangingPagination() {
        create()
        for (landscape in listOf(false, true)) for (vertical in listOf(false, true)) {
            compose.runOnIdle { wide.value = landscape }
            open(fragment, vertical)
            val count = position!!.count
            repeat(5) { turn(1) }
            val sourceScreen = position!!.screen
            val anchor = requireNotNull(position).anchor
            assertTrue(anchor, anchor.matches(Regex("//body/div\\[1\\]/p\\[\\d+\\]")))
            assertEquals("true", js("document.evaluate('//*[@id=\"novel-viewport\"]/div[1]/div[1]'+${JSONObject.quote(anchor)}.slice(6),document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue===window.novelReader.resolve(${JSONObject.quote(anchor)})"))
            var restoredScreen = -1
            repeat(5) {
                open(fragment, vertical, anchor)
                assertVisible("window.novelReader.resolve(${JSONObject.quote(anchor)})")
                assertTrue(position!!.screen <= sourceScreen)
                assertEquals(count, position!!.count)
                assertEquals("361", js("document.querySelector('.book-content').firstElementChild.querySelectorAll('*').length"))
                if (restoredScreen >= 0) assertEquals(restoredScreen, position!!.screen)
                restoredScreen = position!!.screen
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun preciseLineSurvivesRepeatedWritingFontAndViewportChanges() {
        create(); open(fragment, false); repeat(5) { turn(1) }
        val location = requireNotNull(position!!.location)
        repeat(3) {
            for (vertical in listOf(true, false)) {
                compose.runOnIdle { view.setVertical(vertical) }
                compose.runOnIdle { view.setFontSize(if (vertical) 125 else 100) }
                compose.runOnIdle { wide.value = !wide.value }
                awaitGeometry()
                assertEquals(location, JSONObject(js("window.novelReader.state()")).getString("location"))
                assertVisible("(()=>{const l=JSON.parse(${JSONObject.quote(location)}),w=document.createTreeWalker(document.querySelector('.book-content').firstElementChild,NodeFilter.SHOW_TEXT),a=[];while(w.nextNode())if(w.currentNode.textContent.trim())a.push(w.currentNode);const r=document.createRange();r.setStart(a[l.text],l.offset);r.setEnd(a[l.text],l.offset+1);return r;})()")
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun longParagraphAnchorsBeforeVisibleTextAndShallowBookmarksAndLastScreenStillOpen() {
        create()
        val html = "<div><div><p style='margin:0'>${prose.repeat(350)}</p><p>Next paragraph.</p></div></div>"
        for (vertical in listOf(false, true)) {
            open(html, vertical); repeat(5) { turn(1) }
            assertEquals("//body/div[1]/p[1]", position!!.anchor)
            open(html, vertical, position!!.anchor)
            assertEquals(0, position!!.screen)
            jump("//body/div[1]")
            assertEquals(0, position!!.screen)
            jump("//body/div[1]/p[2]")
            assertVisible("window.novelReader.resolve('//body/div[1]/p[2]')")
            open(html, vertical, last = true)
            assertTrue(position!!.count > 6)
            assertEquals(position!!.count - 1, position!!.screen)
        }
        compose.runOnIdle { view.dispose() }
    }

    @Test fun visibleTargetsAndAuthoredRootPaddingSurviveJumps() {
        create()
        for (vertical in listOf(false, true)) {
            open("<div><p style='font-size:3px;line-height:3px;margin:0'>A</p><p style='margin:0'>Other text.</p></div>", vertical)
            assertEquals("//body/p[1]", position!!.anchor)
            val html = "<div>" + (1..30).joinToString("") { "<p>${prose.repeat(2)}</p>" } +
                "<section style='break-inside:avoid'><p>Block begins.</p><p id='target'>Target inside a no-break block.</p></section></div>"
            open(html, vertical); jump("id(\"target\")")
            val geometry = JSONObject(js("(()=>{const t=document.getElementById('target'),a=window.novelReader.resolve(window.novelReader.state().anchor);return{visible:Array.from(t.getClientRects()).some(r=>r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight),before:a===t||!!(a.compareDocumentPosition(t)&Node.DOCUMENT_POSITION_FOLLOWING)};})()"))
            assertTrue(geometry.toString(), geometry.getBoolean("visible"))
            assertTrue(geometry.toString(), geometry.getBoolean("before"))
            val hidden = "<div style='padding-block-start:17px!important'>" + (1..70).joinToString("") { "<p>${prose.repeat(2)}</p>" } +
                "<p id='hidden-prefix'><span style='display:none'>Not rendered.</span><span class='shown'>Restored text.</span></p></div>"
            open(hidden, vertical); val hiddenCount = position!!.count
            jump("id(\"hidden-prefix\")")
            assertVisible("document.querySelector('.shown')")
            assertEquals(hiddenCount, position!!.count)
            assertEquals("\"17px\"", js("document.querySelector('.book-content').firstElementChild.style.getPropertyValue('padding-block-start')"))
            assertEquals("\"important\"", js("document.querySelector('.book-content').firstElementChild.style.getPropertyPriority('padding-block-start')"))
            if (!vertical) {
                val art = "<div><svg style='display:block' width='40' height='2000' viewBox='0 0 40 2000'><rect width='40' height='2000'/></svg>" +
                    (1..40).joinToString("") { "<p ${if(it==20)"id='after-art'" else ""} style='margin:0'>Paragraph $it.</p>" } + "</div>"
                open(art, false); val count = position!!.count
                val imageAnchor = position!!.anchor
                assertEquals("true", js("document.evaluate('//*[@id=\"novel-viewport\"]/div[1]/div[1]'+${JSONObject.quote(imageAnchor)}.slice(6),document,null,XPathResult.FIRST_ORDERED_NODE_TYPE,null).singleNodeValue===window.novelReader.resolve(${JSONObject.quote(imageAnchor)})"))
                jump("id(\"after-art\")")
                assertEquals(count, position!!.count)
                assertEquals("true", js("Array.from(document.getElementById('after-art').getClientRects()).some(r=>r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight)"))
            }
        }
        compose.runOnIdle { view.dispose() }
    }

    private fun create() {
        compose.setContent { AndroidView(factory = { NovelPageView(it).also { v -> view = v; v.onPosition = { position = it } } },
            modifier = Modifier.requiredSize(if (wide.value) 700.dp else 360.dp, if (wide.value) 360.dp else 700.dp)) }
    }
    private fun open(html: String, vertical: Boolean, anchor: String? = null, last: Boolean = false) {
        position = null
        compose.runOnIdle { view.open(html, "http://127.0.0.1", 1, anchor, last, vertical) }
        compose.waitUntil(20_000) { position != null }
        awaitGeometry()
    }
    private fun awaitGeometry() {
        compose.waitForIdle()
        val size = compose.runOnIdle { view.width to view.height }
        compose.waitUntil(10_000) { js("(()=>{const s=document.querySelector('.book-content').style;return Math.abs(innerWidth-${size.first}/devicePixelRatio)<1&&Math.abs(innerHeight-${size.second}/devicePixelRatio)<1&&Math.abs(parseFloat(s.width)-innerWidth)<1&&Math.abs(parseFloat(s.height)-innerHeight)<1;})()") == "true" }
    }
    private fun turn(delta: Int) {
        val previous = position!!.screen
        compose.runOnIdle { view.turn(delta) { fail("Unexpected boundary") } }
        compose.waitUntil(5_000) { position!!.screen != previous }
    }
    private fun jump(anchor: String) {
        val done = CountDownLatch(1)
        compose.runOnIdle { view.jumpToBookmark(anchor) { assertNull(it); done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    private fun assertVisible(target: String) {
        val result = JSONObject(js("(()=>{const t=$target,n=t instanceof Range?null:document.createTreeWalker(t,NodeFilter.SHOW_TEXT).nextNode(),r=t instanceof Range?t:document.createRange();if(n){r.setStart(n,0);r.setEnd(n,1);}const b=r.getBoundingClientRect();return{visible:b.right>0&&b.left<innerWidth&&b.bottom>0&&b.top<innerHeight,rect:b.toJSON()};})()"))
        assertTrue(result.toString(), result.getBoolean("visible"))
    }
    private fun js(script: String): String {
        val done = CountDownLatch(1); var value = ""
        compose.runOnIdle { view.evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value
    }
}
