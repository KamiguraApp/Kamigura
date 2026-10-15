package li.mof.kamigura

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.internal.ReaderChapterEntry
import li.mof.kamigura.reader.internal.ReaderChapterNeighbors
import li.mof.kamigura.reader.novel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NovelSectionLoadingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source="<div><svg width='240' height='120' viewBox='0 0 240 120'><rect width='240' height='120' fill='#d02020'/></svg><p>Source section.</p></div>"
    private val target="<div><p>Target section.</p></div>"

    @Test fun crossingSpinesKeepsTheActualFrameDuringNetworkWaitAndClosingSavesOnlyReachedPage() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var closed=false
        ReaderServer(3,bookFragment={page->if(page==1){entered.countDown();try{release.await(20,TimeUnit.SECONDS)}catch(_:InterruptedException){};target}else source}).use{server->
            try {
                open(server,onBack={closed=true});awaitPage(1)
                val before=compose.onRoot().captureToImage();assertTrue(redPixels(before)>50)
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                next();compose.onNodeWithContentDescription("Loading EPUB section").assertIsDisplayed()
                compose.onNodeWithText("Opening EPUB...").assertDoesNotExist()
                compose.onNodeWithText("Back to series").assertDoesNotExist()
                val during=compose.onRoot().captureToImage();assertTrue(redPixels(during)>50)
                assertEquals(redPixels(before),redPixels(during))
                compose.runOnIdle {find(compose.activity.window.decorView)!!.onPosition(NovelPosition(4,9,false,"id(\"stale\")"))}
                compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
                compose.waitUntil(5_000){closed&&server.bookProgressWrites.isNotEmpty()}
                assertEquals(0,server.bookProgressWrites.last().pageNum)
                assertNotEquals("id(\"stale\")",server.bookProgressWrites.last().bookScrollId)
            }finally{release.countDown()}
        }
    }

    @Test fun oldFrameStaysUntilTheNewDocumentsImagesAreReadyAndPrefetchIsShared() {
        val imageRequested=CountDownLatch(1);val release=CountDownLatch(1)
        ReaderServer(3,bookFragment={page->if(page==1)"<div><p>Target section.</p><img src='/api/Book/1/book-resources?page=1'></div>" else source},
            imageResponseGate={page->if(page==1){imageRequested.countDown();release}else null}).use{server->
            try{
                open(server);awaitPage(1)
                compose.waitUntil(5_000){server.requests.any{it.path=="/api/Book/1/book-page"&&it.getQueryParameter("page")=="1"}}
                next();compose.waitUntil(5_000){imageRequested.count==0L}
                compose.onNodeWithContentDescription("Loading EPUB section").assertIsDisplayed()
                assertTrue(redPixels(compose.onRoot().captureToImage())>50)
                release.countDown();awaitPage(2)
                assertEquals(0,redPixels(compose.onRoot().captureToImage()))
                assertEquals(1,server.requests.count{it.path=="/api/Book/1/book-page"&&it.getQueryParameter("page")=="1"})
            }finally{release.countDown()}
        }
    }

    @Test fun speculativeFailureIsQuietButForegroundFailureOffersRetryAndExit() {
        val failing=AtomicBoolean(true)
        ReaderServer(3,bookPageResponse={_,page->if(page==1&&failing.get())503 to "Unavailable" else 200 to if(page==1)target else source}).use{server->
            open(server);awaitPage(1)
            compose.waitUntil(5_000){server.requests.any{it.path=="/api/Book/1/book-page"&&it.getQueryParameter("page")=="1"}}
            Thread.sleep(200)
            compose.onNodeWithText("Could not load this EPUB section.").assertDoesNotExist()
            next()
            compose.waitUntil(5_000){compose.onAllNodesWithText("Could not load this EPUB section.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Back to series").assertIsDisplayed()
            compose.onNodeWithText("Retry").assertIsDisplayed()
            compose.onNodeWithText("Opening EPUB...").assertDoesNotExist()
            failing.set(false);compose.onNodeWithText("Retry").performClick();awaitPage(2)
        }
    }

    @Test fun adjacentSpinesAreWarmInBothDirectionsAndIncognitoPrefetchNeverSavesProgress() {
        ReaderServer(3,bookFragment={source}).use{server->
            open(server,incognito=true,initial=1);awaitPage(2)
            compose.waitUntil(5_000){listOf("0","2").all{p->server.requests.any{it.path=="/api/Book/1/book-page"&&it.getQueryParameter("page")==p}}}
            Thread.sleep(200);previous();awaitPage(1);next();awaitPage(2);next();awaitPage(3)
            assertEquals(1,server.requests.count{it.path=="/api/Book/1/book-page"&&it.getQueryParameter("page")=="1"})
            Thread.sleep(3_200);assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun open(server:ReaderServer,incognito:Boolean=false,initial:Int=0,onBack:()->Unit={}) {
        val store=KavitaSessionStore(context).apply{useTransient(KavitaSession(baseUrl=server.baseUrl))}
        val api=runBlocking { KavitaClient(context,store).buildApi().first }
        val key="section-loading-${System.nanoTime()}"
        compose.setContent{MaterialTheme{NovelReaderScreen(api,server.baseUrl,1,1,1,1,6,"Test book","First",
            ProgressDto(1,1,1,1,initial),incognito,false,{},onBack,null,key,PageBackground.Paper,false,
            ReaderChapterEntry(1,1,null,"First"),ReaderChapterNeighbors(null,null),false,false,false,null,{_,_->})}}
    }
    private fun next(){compose.onRoot().performTouchInput{click(Offset(width*.9f,height*.75f))}}
    private fun previous(){compose.runOnIdle{
        compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_PAGE_UP))
        compose.activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_PAGE_UP))
    }}
    private fun awaitPage(page:Int){compose.waitUntil(20_000){compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.StateDescription)?.startsWith("Page $page /")==true}}
    private fun redPixels(image:ImageBitmap):Int{val pixels=image.toPixelMap();var count=0
        for(y in 0 until pixels.height*4/5 step 4)for(x in 0 until pixels.width step 4){val p=pixels[x,y];if(p.red>.6f&&p.green<.3f&&p.blue<.3f)count++};return count}
    private fun find(view:View):NovelPageView?=if(view is NovelPageView)view else if(view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull{find(view.getChildAt(it))}else null
}
