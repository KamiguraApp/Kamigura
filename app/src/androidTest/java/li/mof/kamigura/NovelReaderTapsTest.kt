package li.mof.kamigura

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.internal.ReaderChapterEntry
import li.mof.kamigura.reader.internal.ReaderChapterNeighbors
import li.mof.kamigura.reader.novel.NovelPageView
import li.mof.kamigura.reader.novel.NovelReaderScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelReaderTapsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sixRapidHorizontalTapsAdvanceSixScreensAndSixReverseTapsReturn() = burst(false)
    @Test fun sixRapidVerticalTapsAdvanceSixScreensAndSixReverseTapsReturn() = burst(true)
    private fun burst(vertical:Boolean) {
        ReaderServer(3,bookFragment={novelFixture(vertical,240)}).use{server->
            open(server,vertical);awaitState(1,1)
            assertEquals("\"undefined\"",js("typeof window.novelReader.linkAt"))
            js("""(()=>{const original=window.novelReader.turn;window.turnCalls=0;window.novelReader.turn=d=>{
                window.turnCalls++;const until=performance.now()+120;while(performance.now()<until){};return original(d);};return true;})()""")
            taps(6,forward=true,vertical=vertical);awaitState(1,7)
            assertEquals("6",js("window.turnCalls"))
            taps(6,forward=false,vertical=vertical);awaitState(1,1)
            assertEquals("12",js("window.turnCalls"))
            compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
            compose.runOnIdle{compose.activity.onBackPressedDispatcher.onBackPressed()}
            compose.waitUntil(5_000){server.bookProgressWrites.isNotEmpty()}
            assertEquals(0,server.bookProgressWrites.last().pageNum)
            assertNotNull(server.bookProgressWrites.last().bookScrollId)
        }
    }

    @Test fun tapsBeforeTheFirstSnapshotUseNormalZonesAndWaitUntilReady() {
        val gate=CountDownLatch(1)
        ReaderServer(3,bookFragment={page->if(page==0)try{gate.await(20,TimeUnit.SECONDS)}catch(_:InterruptedException){};novelFixture(false,240)}).use{server->
            try{
                open(server,false)
                compose.waitUntil(5_000){server.requests.any{it.path=="/api/Book/1/book-page"}}
                taps(6,forward=true,vertical=false)
                gate.countDown();awaitState(1,7)
            }finally{gate.countDown()}
        }
    }

    @Test fun queuedTapsCrossSpinesButDoNotAutoConfirmAChapterBoundary() {
        var switches=0
        ReaderServer(3,spineCount=2,bookFragment={"<div><p>One screen per spine.</p></div>"}).use{server->
            open(server,false,count=2,neighbor=true,onSwitch={switches++});awaitState(1,1)
            taps(6,forward=true,vertical=false)
            compose.waitUntil(10_000){compose.onAllNodesWithText("Next chapter").fetchSemanticsNodes().isNotEmpty()}
            Thread.sleep(300);assertEquals(0,switches)
            compose.onNodeWithText("Next chapter").assertIsDisplayed()
            compose.waitUntil(5_000){server.requests.any{it.path=="/api/Reader/mark-chapter-read"}}
            assertEquals(1,server.requests.count{it.path=="/api/Reader/mark-chapter-read"})
        }
    }

    @Test fun centreTapClosesTheMenuAndSideTapsDoNotTurnUnderIt() {
        ReaderServer(3,bookFragment={novelFixture(false,240)}).use{server->
            open(server,false);awaitState(1,1)
            tapAt(.5f);awaitMenu(true)
            // Mid-height, clear of the menu's bottom controls.
            tapAt(.9f);Thread.sleep(500);compose.waitForIdle()
            awaitMenu(true);awaitState(1,1)
            tapAt(.5f);awaitMenu(false)
            taps(1,forward=true,vertical=false);awaitState(1,2)
        }
    }

    private fun tapAt(x:Float){compose.onRoot().performTouchInput{click(Offset(width*x,height*.5f))}}
    private fun awaitMenu(open:Boolean)=compose.waitUntil(5_000){
        compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()==open}
    private fun open(server:ReaderServer,vertical:Boolean,count:Int=6,neighbor:Boolean=false,onSwitch:()->Unit={}) {
        val store=KavitaSessionStore(context).apply{useTransient(KavitaSession(baseUrl=server.baseUrl))}
        val api=runBlocking{KavitaClient(context,store).buildApi().first}
        val preferenceKey="tap-regression-${System.nanoTime()}"
        compose.setContent{MaterialTheme{NovelReaderScreen(api,server.baseUrl,1,1,1,1,count,"Test book","First",
            ProgressDto(1,1,1,1,0),false,false,{}, {}, UserReadingProfileDto(bookReaderWritingStyle=if(vertical)1 else 0),
            preferenceKey,PageBackground.Paper,false,ReaderChapterEntry(1,1,null,"First"),
            ReaderChapterNeighbors(null,if(neighbor)ReaderChapterEntry(2,1,null,"Second")else null),false,false,false,null,{_,_->onSwitch()})}}
    }
    private fun taps(count:Int,forward:Boolean,vertical:Boolean){compose.onRoot().performTouchInput{
        val x=if(forward!=vertical)width*.9f else width*.1f
        repeat(count){click(Offset(x,height*.8f));advanceEventTime(24)}
    }}
    private fun awaitState(page:Int,screen:Int){compose.waitUntil(20_000){compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.StateDescription)?.let{
        it.startsWith("Page $page /")&&it.contains("Screen $screen /") }==true}}
    private fun view():NovelPageView=requireNotNull(find(compose.activity.window.decorView))
    private fun find(view:View):NovelPageView?=if(view is NovelPageView)view else if(view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull{find(view.getChildAt(it))}else null
    private fun js(script:String):String{val done=CountDownLatch(1);var result=""
        compose.runOnIdle{view().evaluateJavascript(script){result=it;done.countDown()}}
        assertTrue(done.await(5,TimeUnit.SECONDS));return result}
}
