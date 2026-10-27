package li.mof.kamigura

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDeviceControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val visible = mutableStateOf(true)
    private val settings get() = AppSettingsStore(context)

    @Test fun curlLtrKeysFollowTapSteps() = pagedKeys(PageTurnMode.Curl, "LTR")
    @Test fun curlRtlKeysFollowTapSteps() = pagedKeys(PageTurnMode.Curl, "RTL")
    @Test fun slideLtrKeysFollowTapSteps() = pagedKeys(PageTurnMode.Slide, "LTR")
    @Test fun slideRtlKeysFollowTapSteps() = pagedKeys(PageTurnMode.Slide, "RTL")

    private fun pagedKeys(mode: PageTurnMode, direction: String) = withSettings(mode) {
        ReaderServer(1).use { server ->
            open(server, direction)
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val step = if (bounds.height > bounds.width) 1 else 2
            val next = 1 + step
            listOf(KeyEvent.KEYCODE_VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_PAGE_DOWN to KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_UP,
                (if (direction == "RTL") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) to
                    (if (direction == "RTL") KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            ).forEach { (forward, backward) ->
                key(forward)
                assertPage(next)
                key(backward)
                assertPage(1)
            }
            key(KeyEvent.KEYCODE_SPACE)
            assertPage(next)
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun menuAndZoomDoNotTurnPages() = withSettings(PageTurnMode.Curl) {
        ReaderServer(1).use { server ->
            open(server, "LTR")
            openMenu()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            key(KeyEvent.KEYCODE_VOLUME_DOWN)
            compose.onNodeWithText("2 / 6", substring = true).assertIsDisplayed()
            closeMenu()
            compose.onRoot().performTouchInput { doubleClick(Offset(width / 2f, height / 2f)) }
            compose.mainClock.advanceTimeBy(1_200)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            key(KeyEvent.KEYCODE_VOLUME_DOWN)
            assertPage(1)
        }
    }

    @Test fun verticalKeysScrollForwardAndBackAndContinueChapter() = withSettings(PageTurnMode.Curl) {
        ReaderServer(1, neighbor = true).use { server ->
            open(server, "Vertical")
            val start = scrollPosition()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            assertTrue(scrollPosition() > start)
            key(KeyEvent.KEYCODE_PAGE_UP)
            assertEquals(start, scrollPosition(), 0.001f)
            key(KeyEvent.KEYCODE_VOLUME_DOWN)
            assertTrue(scrollPosition() > start)
            key(KeyEvent.KEYCODE_VOLUME_UP)
            assertEquals(start, scrollPosition(), 0.001f)
            openMenu()
            jump(5)
            closeMenu()
            repeat(8) {
                if (server.requests.none { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "2" }) {
                    key(KeyEvent.KEYCODE_PAGE_DOWN)
                }
            }
            compose.waitUntil(5_000) {
                server.requests.any { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "2" }
            }
            assertPage(0)
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun keysRespectChapterBoundariesAndSavedPosition() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1, neighbor = true).use { server ->
            val restoration = StateRestorationTester(compose)
            open(server, "LTR", restoration)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            openMenu()
            val before = compose.onNodeWithText("/ 6", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text]
            closeMenu()
            restoration.emulateSavedInstanceStateRestore()
            awaitReader(server)
            openMenu()
            assertEquals(before, compose.onNodeWithText("/ 6", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text])
            jump(5)
            closeMenu()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onNodeWithText("Next chapter").assertIsDisplayed()
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Next chapter").assertDoesNotExist()
            assertPage(5)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) {
                server.requests.any { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "2" }
            }
            assertPage(0)
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.onNodeWithText("Previous chapter").assertIsDisplayed()
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.waitUntil(5_000) {
                server.requests.count { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "1" } >= 3
            }
            assertPage(5)
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun keepScreenOnAndVolumeKeysAreScopedToReader() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1).use { server ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val originalStream = compose.activity.volumeControlStream
            compose.runOnIdle { compose.activity.volumeControlStream = AudioManager.STREAM_MUSIC }
            val originalCallback = compose.activity.window.callback
            try {
                open(server, "LTR")
                awaitScreenFlag(true)
                runBlocking { settings.setKeepScreenOn(false) }
                compose.waitForIdle()
                awaitScreenFlag(false)
                assertFalse(runBlocking { AppSettingsStore(context).flow.first().reader.common.keepScreenOn })
                runBlocking { settings.setKeepScreenOn(true) }
                compose.waitForIdle()
                awaitScreenFlag(true)

                val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                key(KeyEvent.KEYCODE_VOLUME_DOWN)
                assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                runBlocking { settings.setTurnPagesWithVolumeKeys(false) }
                compose.waitForIdle()
                assertFalse(runBlocking { AppSettingsStore(context).flow.first().reader.common.turnPagesWithVolumeKeys })
                assertVolumeChanges(audio)
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                awaitScreenFlag(false)
                assertSame(originalCallback, compose.activity.window.callback)
                assertVolumeChanges(audio)
            } finally {
                compose.runOnIdle { compose.activity.volumeControlStream = originalStream }
            }
        }
    }

    @Test fun heldKeyTurnsOnlyOnce() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1).use { server ->
            open(server, "LTR")
            compose.runOnIdle {
                val callback = compose.activity.window.callback
                callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 0))
                repeat(3) { callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, it + 1)) }
                callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN, 0))
            }
            compose.mainClock.advanceTimeBy(1_200)
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertPage(if (bounds.height > bounds.width) 2 else 3)
        }
    }

    @Test fun readerSettingsSwitchesPersist() = withSettings(PageTurnMode.Slide) {
        runBlocking { settings.setTurnPagesWithVolumeKeys(false) }
        compose.setContent { MaterialTheme { ReaderSettingsScreen(settings, onBack = {}) } }
        compose.onNodeWithText("Keep screen on").assertIsDisplayed()
        compose.onNodeWithText("Turn pages with volume keys").assertIsDisplayed()
        val switches = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        switches[0].assertIsOn().performClick()
        switches[1].assertIsOff().performClick()
        compose.waitUntil(5_000) {
            val stored = runBlocking { AppSettingsStore(context).flow.first().reader }
            !stored.common.keepScreenOn && stored.common.turnPagesWithVolumeKeys
        }
        switches[0].assertIsOff()
        switches[1].assertIsOn()
    }

    @Test fun keyTurnThenQuickCloseSavesReachedPage() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1).use { server ->
            open(server, "LTR", incognito = false)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val reached = if (bounds.height > bounds.width) 2 else 3
            openMenu()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { !visible.value && server.progressWrites.contains(1 to reached) }
            assertTrue(server.progressWrites.all { it == 1 to reached })
        }
    }

    @Test fun keyDoesNotInterruptDragAtChapterEdge() = withSettings(PageTurnMode.Slide) {
        ReaderServer(1).use { server ->
            open(server, "LTR")
            openMenu()
            jump(0)
            closeMenu()
            compose.onRoot().performTouchInput {
                down(Offset(width * 0.3f, height * 0.5f))
                moveTo(Offset(width * 0.65f, height * 0.5f), delayMillis = 200)
            }
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.onRoot().performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitUntil(5_000) { !visible.value }
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    private fun withSettings(mode: PageTurnMode, block: () -> Unit) {
        val original = runBlocking { settings.flow.first().reader }
        val file = context.cacheDir.resolve("reader_session_preferences.json")
        val saved = file.takeIf { it.exists() }?.readBytes()
        try {
            runBlocking {
                settings.setPageTransition(PageTransition.valueOf(mode.name))
                settings.setPrefetchTurns(0)
                settings.setInvertMode(InvertMode.Off)
                settings.setKeepScreenOn(true)
                settings.setTurnPagesWithVolumeKeys(true)
            }
            block()
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            runBlocking {
                settings.setPageTransition(original.image.pageTransition)
                settings.setPrefetchTurns(original.image.prefetchTurns)
                settings.setInvertMode(original.image.invertMode)
                settings.setKeepScreenOn(original.common.keepScreenOn)
                settings.setTurnPagesWithVolumeKeys(original.common.turnPagesWithVolumeKeys)
            }
            if (saved == null) file.delete() else file.writeBytes(saved)
            ReaderSessionPreferenceCache.clearMemoryForTest()
        }
    }

    private fun open(
        server: ReaderServer,
        direction: String,
        restoration: StateRestorationTester? = null,
        incognito: Boolean = true
    ) {
        val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme {
                if (visible.value) ReaderScreen(session, settings, 1, 1, 1, 1,
                    incognito = incognito, initialPage = 1, onBack = { visible.value = false })
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        awaitReader(server)
        openMenu()
        compose.onNodeWithText(direction).performClick()
        closeMenu()
    }

    private fun awaitReader(server: ReaderServer) {
        compose.waitUntil(10_000) {
            server.requests.any { it.path == "/api/Reader/image" } &&
                compose.onAllNodesWithText("Loading session...").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Opening chapter...").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_200)
    }

    private fun key(code: Int) {
        shell("input keyevent $code")
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.2f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeMenu() { compose.onNodeWithContentDescription("Close menu").performClick(); compose.waitForIdle() }
    private fun assertPage(page: Int) {
        openMenu()
        compose.onNodeWithText("${page + 1} / 6", substring = true).assertIsDisplayed()
        closeMenu()
    }
    private fun jump(page: Int) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(page.toFloat()) }
        compose.waitForIdle()
    }
    private fun scrollPosition(): Float = compose.onNode(hasScrollAction())
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun awaitScreenFlag(enabled: Boolean) = compose.waitUntil(5_000) {
        // ViewRootImpl adds the view's wake request to the effective server-side flags.
        val component = compose.activity.componentName.flattenToShortString()
        val window = shell("dumpsys window windows").split(Regex("\n  Window #\\d+ "))
            .firstOrNull { it.lineSequence().first().contains(component) }
        window != null && window.contains("KEEP_SCREEN_ON") == enabled
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation()
        .uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes().toString(Charsets.UTF_8) }
        }

    private fun assertVolumeChanges(audio: AudioManager) {
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val code = if (before > 0) KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
        // Android may use the first unconsumed press only to reveal its volume panel.
        var changed = false
        repeat(3) {
            if (!changed) {
                key(code)
                changed = runCatching {
                    compose.waitUntil(1_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) != before }
                }.isSuccess
            }
        }
        assertTrue("Normal Android volume handling did not adjust the music stream", changed)
        key(if (code == KeyEvent.KEYCODE_VOLUME_DOWN) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == before }
    }
}
