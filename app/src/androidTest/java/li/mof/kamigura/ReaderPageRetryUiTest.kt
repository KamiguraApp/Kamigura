package li.mof.kamigura

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import coil.ImageLoader
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import li.mof.kamigura.reader.internal.ReaderPageRetryState
import li.mof.kamigura.reader.internal.ReaderPageView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class ReaderPageRetryUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var readerVisible: androidx.compose.runtime.MutableState<Boolean>? = null

    @Test
    fun failedPageRemountRendersRetryWithoutRequestingOrLoading() {
        ReaderServer(1, failPage = { true }).use { server ->
            val loader = ImageLoader(context)
            val retries = ReaderPageRetryState().apply { onFailure(1, 0) }
            val remount = mutableIntStateOf(0)
            try {
                compose.setContent {
                    MaterialTheme {
                        key(remount.intValue) {
                            ReaderPageView(1, 6, true, emptyMap(), false,
                                { "${server.baseUrl}/api/Reader/image?chapterId=1&page=$it" },
                                loader, InvertMode.Off, 0.5f, mutableMapOf(),
                                retryState = retries, interactiveRetry = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                repeat(5) {
                    compose.onNodeWithText("Retry").assertIsDisplayed()
                    compose.onNodeWithText("...").assertDoesNotExist()
                    assertTrue(server.requests.isEmpty())
                    compose.runOnIdle { remount.intValue++ }
                }
            } finally { loader.shutdown() }
        }
    }

    @Test
    fun pagedRetryKeepsDisabledButtonWhileLoading() = heldRetry("LTR", succeeds = true)

    @Test
    fun verticalRetryKeepsDisabledButtonWhileLoading() = heldRetry("Vertical", succeeds = true)

    @Test
    fun failedRetryReturnsToEnabledButtonWithoutLoadingDots() = heldRetry("LTR", succeeds = false)

    private fun heldRetry(direction: String, succeeds: Boolean) = withSettings(PageTurnMode.Slide) {
        val holding = AtomicBoolean(false)
        val release = CountDownLatch(1)
        ReaderServer(1,
            failPage = { it == 1 && (!succeeds || !holding.get()) },
            imageResponseGate = { if (it == 1 && holding.get()) release else null }
        ).use { server ->
            try {
                open(server, direction, 1)
                awaitRetry()
                holding.set(true)
                retryTouch()
                compose.waitUntil(5_000) { imageRequests(server, 1) >= 2 }
                val button = if (direction == "Vertical") compose.onNodeWithText("Retry")
                    else compose.onNodeWithContentDescription("Retry page 2")
                button.assertIsNotEnabled()
                repeat(4) {
                    compose.onNodeWithText("...", useUnmergedTree = true).assertDoesNotExist()
                    compose.onNodeWithText("Retry", useUnmergedTree = true).assertIsDisplayed()
                    retryTouch()
                    compose.mainClock.advanceTimeBy(100)
                    compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
                    button.assertIsNotEnabled()
                }
                release.countDown()
                if (succeeds) awaitRecovered() else {
                    compose.waitUntil(5_000) {
                        !button.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
                    compose.onNodeWithText("...").assertDoesNotExist()
                    compose.onNodeWithText("Retry", useUnmergedTree = true).assertIsDisplayed()
                }
            } finally { release.countDown() }
        }
    }

    @Test
    fun curlFailedTurnsNeverReplaceRetryWithLoadingDots() = failedTurns(PageTurnMode.Curl)

    @Test
    fun slideFailedTurnsNeverReplaceRetryWithLoadingDots() = failedTurns(PageTurnMode.Slide)

    private fun failedTurns(mode: PageTurnMode) = withSettings(mode) {
        ReaderServer(1, failPage = { it > 0 }).use { server ->
            open(server, "LTR", 1)
            // Prime all the sheets the renderer may compose during the measured turn.
            for (page in 1..5) {
                openMenu()
                jump(page)
                closeMenu()
                awaitReadyRetry(page)
            }
            openMenu()
            jump(1)
            closeMenu()
            awaitReadyRetry(1)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            try {
                compose.onRoot().performTouchInput {
                    down(Offset(width * 0.85f, height * 0.5f))
                    moveTo(Offset(width * 0.65f, height * 0.5f), 300)
                }
                repeat(6) {
                    compose.mainClock.advanceTimeBy(100)
                    assertNoLoadingDots()
                }
                compose.onRoot().performTouchInput {
                    moveTo(Offset(width * 0.2f, height * 0.5f), 300)
                    up()
                }
                repeat(12) {
                    compose.mainClock.advanceTimeBy(100)
                    assertNoLoadingDots()
                }
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    private fun assertNoLoadingDots() {
        compose.onAllNodesWithText("...", useUnmergedTree = true).assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("Retry", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun awaitReadyRetry(page: Int) = compose.waitUntil(10_000) {
        compose.onAllNodesWithContentDescription("Retry page ${page + 1}").fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }

    @Test
    fun manualRetryBypassesBadCacheWithoutReloadingHealthyHalf() = manualRetry("LTR")

    @Test
    fun verticalManualRetryKeepsMenuClosed() = manualRetry("Vertical")

    @Test
    fun rtlManualRetryUsesTheCorrectPhysicalHalf() = manualRetry("RTL")

    @Test
    fun smartInvertStillAppliesAfterRetry() = withSettings(PageTurnMode.Slide) {
        val failing = AtomicBoolean(true)
        ReaderServer(1, cacheImages = true, failPage = { it == 1 && failing.get() }).use { server ->
            open(server, "LTR", 1)
            awaitRetry()
            openMenu()
            compose.onNodeWithText("Smart", useUnmergedTree = true).performClick()
            closeMenu()
            failing.set(false)
            retryTouch()
            awaitRecovered(inverted = true)
            assertTrue(imageRequests(server, 1) >= 2)
        }
    }

    @Test
    fun localDecodeFailureRetainsBrokenImageWithoutRetry() {
        val loader = ImageLoader(context)
        try {
            compose.setContent {
                MaterialTheme {
                    ReaderPageView(0, 1, true, emptyMap(), false,
                        { OfflinePage.ArchiveEntry(context.cacheDir.resolve("missing-retry-fixture.zip").path, "page.png", 0) },
                        loader, InvertMode.Off, 0.5f, mutableMapOf(),
                        retryState = ReaderPageRetryState(), interactiveRetry = true, modifier = Modifier.fillMaxSize())
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Page unavailable").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Retry").assertDoesNotExist()
        } finally { loader.shutdown() }
    }

    @Test
    fun downwardDragStartingOnRetryStillClosesReader() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1, failPage = { it == 1 }).use { server ->
            val visible = open(server, "LTR", 1)
            awaitRetry()
            val center = retryCenter()
            compose.onRoot().performTouchInput { swipe(center, Offset(center.x, height * 0.95f), 500) }
            compose.waitUntil(5_000) { !visible.value }
        }
    }

    @Test
    fun doubleTapOutsideRetryStillZoomsAndDisablesRetryHitTargets() = withSettings(PageTurnMode.Curl) {
        ReaderServer(1, failPage = { it == 1 }).use { server ->
            open(server, "LTR", 1)
            awaitRetry()
            compose.onNodeWithContentDescription("Retry page 2").assertExists()
            compose.onRoot().performTouchInput { doubleClick(Offset(width / 2f, height * 0.2f)) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Retry page 2").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
            compose.onRoot().performTouchInput { doubleClick(Offset(width / 2f, height * 0.2f)) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Retry page 2").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun manualRetry(direction: String) = withSettings(PageTurnMode.Slide) {
        val failing = AtomicBoolean(true)
        ReaderServer(1, cacheImages = true, failPage = { it == 1 && failing.get() }).use { server ->
            open(server, direction, 1)
            awaitRetry()
            compose.waitForIdle()
            val healthyRequests = imageRequests(server, 2)
            failing.set(false)
            retryTouch()
            awaitRecovered()
            assertTrue(imageRequests(server, 1) >= 2)
            assertEquals(healthyRequests, imageRequests(server, 2))
            compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
            // Outside Retry, the central tap retains its menu action.
            openMenu()
            compose.onNodeWithText("2 / 6", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun curlDragStartingOnRetryTurnsAndReturningRetriesAutomatically() = withSettings(PageTurnMode.Curl) {
        val failing = AtomicBoolean(true)
        ReaderServer(1, failPage = { it == 1 && failing.get() }).use { server ->
            open(server, "LTR", 1)
            awaitRetry()
            val center = retryCenter()
            compose.onRoot().performTouchInput {
                swipe(center, Offset(10f, center.y), 500)
            }
            compose.waitUntil(10_000) { imageRequests(server, 3) > 0 || imageRequests(server, 2) > 0 }
            openMenu()
            compose.onNodeWithText("2 / 6", substring = true).assertDoesNotExist()
            failing.set(false)
            jump(1)
            closeMenu()
            awaitRecovered()
            assertTrue(imageRequests(server, 1) >= 2)
        }
    }

    @Test
    fun verticalRetryDoesNotToggleMenuAndDragStillScrolls() = withSettings(PageTurnMode.Curl) {
        val failing = AtomicBoolean(true)
        ReaderServer(1, failPage = { it == 1 && failing.get() }).use { server ->
            open(server, "Vertical", 1)
            awaitRetry()
            val center = retryCenter()
            compose.onRoot().performTouchInput { swipe(center, Offset(center.x, 10f), 500) }
            openMenu()
            compose.onNodeWithText("2 / 6", substring = true).assertDoesNotExist()
            jump(4)
            closeMenu()
            openMenu()
            failing.set(false)
            jump(1)
            closeMenu()
            awaitRecovered()
            compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
        }
    }

    @Test
    fun pagedNetworkRecoveryRetriesVisibleFailure() = networkRecovery("LTR")

    @Test
    fun verticalNetworkRecoveryRetriesVisibleFailure() = networkRecovery("Vertical")

    @Test
    fun slideNetworkRecoveryRetriesVisibleFailure() = networkRecovery("LTR", PageTurnMode.Slide)

    private fun networkRecovery(direction: String, mode: PageTurnMode = PageTurnMode.Curl) = withSettings(mode) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun connected() = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        assertTrue("Network must be connected before this emulator-only test", connected())
        // The fixture is on loopback (which survives airplane mode), so it supplies corrupt
        // image data while the real network is down. Recovery uses the production callback.
        ReaderServer(1, failPage = { it > 0 && !connected() }).use { server ->
            try {
                open(server, direction, 0)
                openMenu()
                shell("cmd connectivity airplane-mode enable")
                compose.waitUntil(10_000) { !connected() }
                jump(3)
                closeMenu()
                awaitRetry()
                val before = imageRequests(server, 3)
                shell("cmd connectivity airplane-mode disable")
                compose.waitUntil(20_000) { connected() }
                awaitRecovered()
                assertTrue(imageRequests(server, 3) > before)
            } finally {
                shell("cmd connectivity airplane-mode disable")
            }
        }
    }

    private fun withSettings(mode: PageTurnMode, block: () -> Unit) {
        val settings = AppSettingsStore(context)
        val original = runBlocking { settings.flow.first().reader }
        val preferenceFile = context.cacheDir.resolve("reader_session_preferences.json")
        val originalPreferences = preferenceFile.takeIf { it.exists() }?.readBytes()
        try {
            runBlocking {
                settings.setPrefetchTurns(0)
                settings.setPageTurnMode(mode)
                settings.setPageTransitionAnimation(true)
                settings.setInvertMode(InvertMode.Off)
            }
            block()
        } finally {
            compose.runOnIdle { readerVisible?.value = false }
            compose.waitForIdle()
            runBlocking {
                settings.setPrefetchTurns(original.prefetchTurns)
                settings.setPageTurnMode(original.pageTurnMode)
                settings.setPageTransitionAnimation(original.pageTransitionAnimation)
                settings.setInvertMode(original.invertMode)
            }
            if (originalPreferences == null) preferenceFile.delete() else preferenceFile.writeBytes(originalPreferences)
            ReaderSessionPreferenceCache.clearMemoryForTest()
        }
    }

    private fun open(server: ReaderServer, direction: String, page: Int): androidx.compose.runtime.MutableState<Boolean> {
        val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        val visible = mutableStateOf(true)
        readerVisible = visible
        compose.setContent {
            MaterialTheme {
                if (visible.value) ReaderScreen(session, AppSettingsStore(context), 1, 1, 1, 1,
                    incognito = true, onBack = { visible.value = false })
            }
        }
        compose.waitUntil(10_000) { imageRequests(server, 0) > 0 }
        openMenu()
        compose.onNodeWithText(direction).performClick()
        compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
        jump(if (direction == "RTL") 5 - page else page)
        closeMenu()
        return visible
    }

    private fun jump(page: Int) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(page.toFloat()) }
    }

    private fun openMenu() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.2f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closeMenu() = compose.onNodeWithContentDescription("Close menu").performClick()
    private fun awaitRetry() = compose.waitUntil(15_000) {
        compose.onAllNodesWithText("Retry", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitRecovered(inverted: Boolean = false) = compose.waitUntil(15_000) {
        val noRetry = compose.onAllNodesWithText("Retry", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        val notLoading = compose.onAllNodesWithText("...", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        if (!noRetry || !notLoading) false else {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            var white = 0
            for (y in 1..20) for (x in 1..20) {
                val color = pixels[pixels.width * x / 21, pixels.height * y / 21]
                if (inverted) {
                    if (color.red < 0.005f && color.green < 0.005f && color.blue < 0.005f) white++
                } else if (color.red > 0.995f && color.green > 0.995f && color.blue > 0.995f) white++
            }
            white > 60
        }
    }
    private fun retryCenter(): Offset {
        val bounds = compose.onAllNodesWithText("Retry", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        return bounds.center
    }
    private fun retryTouch() {
        val center = retryCenter()
        compose.onRoot().performTouchInput { click(center) }
    }
    private fun imageRequests(server: ReaderServer, page: Int) = server.requests.count {
        it.path == "/api/Reader/image" && it.getQueryParameter("page") == page.toString()
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }
}
