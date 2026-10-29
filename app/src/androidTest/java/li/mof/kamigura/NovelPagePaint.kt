package li.mof.kamigura

import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

internal fun awaitNovelPaint(view: WebView) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ready = CountDownLatch(1)
    instrumentation.runOnMainSync {
        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                view.invalidate()
                view.postOnAnimation { view.postOnAnimation { ready.countDown() } }
            }
        })
    }
    assertTrue("WebView did not present its updated layout", ready.await(5, TimeUnit.SECONDS))
    instrumentation.waitForIdleSync()
}
