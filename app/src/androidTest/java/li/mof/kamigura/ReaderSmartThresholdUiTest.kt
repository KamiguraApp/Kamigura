package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import li.mof.kamigura.reader.readerSessionPreferenceKey
import li.mof.kamigura.reader.internal.ReaderMenuOverlay
import li.mof.kamigura.ui.theme.KamiguraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderSmartThresholdUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = AppSettingsStore(context)
    private val visible = mutableStateOf(true)
    private val generation = mutableIntStateOf(0)

    @Test fun menuOnlyCommitsOnReleaseAndShowsTheSameRangeOnPhone() {
        val threshold = mutableFloatStateOf(.82f)
        val mode = mutableStateOf(InvertMode.Smart)
        var commits = 0
        var dismissals = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { KamiguraTheme {
            Box(Modifier.requiredSize(320.dp, 700.dp).background(androidx.compose.ui.graphics.Color.Black).testTag("Threshold menu")) {
                ReaderMenuOverlay("Test series", "Chapter", 1, null, {}, 6, ReaderReadingDirection.LeftToRight,
                    false, {}, { dismissals++ }, {}, mode.value, { mode.value = it }, threshold.floatValue,
                    { commits++; threshold.floatValue = it }, {}, {}, {})
            }
        } } }
        val range = thresholdSlider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(.2f.. .9f, range.range); assertEquals(0, range.steps)
        thresholdSlider().performTouchInput {
            down(Offset(width * .8f, height / 2f))
            moveTo(Offset(width * .2f, height / 2f), 100)
            moveTo(Offset(width * .7f, height / 2f), 100)
        }
        compose.runOnIdle { assertEquals(0, commits); assertEquals(.82f, threshold.floatValue, 0f) }
        thresholdSlider().performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits); assertEquals(0, dismissals) }
        compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("White threshold").assertDoesNotExist()
        compose.onNodeWithText("Always", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("White threshold").assertDoesNotExist()
        compose.onNodeWithText("Smart", useUnmergedTree = true).performClick()
        thresholdSlider().assertIsDisplayed()
        val before = commits
        setThreshold(threshold.floatValue)
        compose.runOnIdle { assertEquals(before, commits) }
        capture("phone-menu")
    }

    @Test fun smartMenuInShortLandscapeKeepsTheThresholdAndOtherControlsReachable() {
        val threshold = mutableFloatStateOf(.82f)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { KamiguraTheme {
            Box(Modifier.requiredSize(640.dp, 320.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                ReaderMenuOverlay("Test series", "Chapter", 1, null, {}, 6, ReaderReadingDirection.LeftToRight,
                    true, {}, {}, {}, InvertMode.Smart, {}, threshold.floatValue, { threshold.floatValue = it }, {}, {}, {})
            }
        } } }
        thresholdSlider().performScrollTo().assertIsDisplayed()
        setThreshold(.2f)
        assertEquals(.2f, threshold.floatValue, .001f)
        compose.onNodeWithText("Spread shift").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close menu").assertIsDisplayed()
    }

    @Test fun slideSpreadAndPreloadedPagesFollowTheNewThreshold() = online(PageTransition.Slide)
    @Test fun curlSpreadAndPreloadedPagesFollowTheNewThreshold() = online(PageTransition.Curl)

    private fun online(transition: PageTransition) = withSettings(transition) {
        ReaderServer(1, cacheImages = true, imageBytes = halfWhite()).use { server ->
            open(server); awaitPixels(false)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val next = if (root.width > root.height) 3 else 2
            compose.waitUntil(10_000) { server.requests.any { it.path == "/api/Reader/image" && it.getQueryParameter("page") == next.toString() } }
            menu(); setThreshold(.2f); closeMenu(); awaitPixels(true)
            compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
            compose.waitForIdle(); menu()
            compose.onNodeWithText("${next + 1} / 6", substring = true).assertIsDisplayed()
            closeMenu(); awaitPixels(true)
            menu(); setThreshold(.9f); closeMenu(); awaitPixels(false)
            compose.onRoot().performTouchInput { click(Offset(width * .1f, height * .5f)) }
            compose.waitForIdle(); menu()
            compose.onNodeWithText("2 / 6", substring = true).assertIsDisplayed()
            closeMenu(); awaitPixels(false)
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun settingsRemainLiveUntilOverrideAndSeriesOverrideSurvivesReopenAndModeChanges() = withSettings(PageTransition.Slide) {
        ReaderServer(1, imageBytes = halfWhite()).use { server ->
            val session = open(server); awaitPixels(false)
            menu()
            compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Smart", useUnmergedTree = true).performClick()
            closeMenu()
            runBlocking { settings.setInvertWhiteThreshold(.2f) }; awaitPixels(true)
            menu()
            assertEquals(.2f, thresholdSlider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            setThreshold(.9f)
            compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("White threshold").assertDoesNotExist()
            compose.onNodeWithText("Smart", useUnmergedTree = true).performClick()
            compose.onNodeWithText("RTL").performClick()
            compose.onNodeWithText("LTR").performClick()
            assertEquals(.9f, thresholdSlider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            closeMenu(); awaitPixels(false)
            runBlocking { settings.setInvertWhiteThreshold(.8f) }; awaitPixels(false)
            runBlocking { settings.setInvertWhiteThreshold(.2f) }; awaitPixels(false)
            val cacheKey = runBlocking { readerSessionPreferenceKey(session.load(), session.activeProfile()?.id, 1) }
            compose.waitUntil(5_000) { runCatching {
                Json.parseToJsonElement(context.cacheDir.resolve("reader_session_preferences.json").readText()).jsonArray.any {
                    it.jsonObject["key"]?.jsonPrimitive?.content == cacheKey && it.jsonObject["invertWhiteThreshold"]?.jsonPrimitive?.content?.toFloat() == .9f
                }
            }.getOrDefault(false) }
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            ReaderSessionPreferenceCache.clearMemoryForTest()
            compose.runOnIdle { generation.intValue++; visible.value = true }
            awaitPixels(false); menu()
            assertEquals(.9f, thresholdSlider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
            capture("tablet-menu")
        }
    }

    @Test fun downloadedArchivePagesReanalyseWithoutServerImages() = withSettings(PageTransition.Curl) {
        val image = halfWhite()
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> repeat(6) {
            zip.putNextEntry(ZipEntry("$it.png")); zip.write(image); zip.closeEntry()
        } } }.toByteArray()
        ReaderServer(1, imageBytes = image, downloadPayload = "application/zip" to archive).use { server ->
            val repository = OfflineIssueRepository(context)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                runBlocking { repository.enqueue(session, 1, 1, 1, 1, "Threshold fixture", "Offline", 6) }
                compose.waitUntil(20_000) { runBlocking { repository.reconcile(session, 1)?.status == OfflineDownloadStatus.Ready } }
                open(server); awaitPixels(false)
                menu(); setThreshold(.2f); closeMenu(); awaitPixels(true)
                menu(); setThreshold(.9f); closeMenu(); awaitPixels(false)
                assertFalse(server.requests.any { it.path == "/api/Reader/image" })
            } finally { runBlocking { repository.remove(session, 1) } }
        }
    }

    private fun open(server: ReaderServer): KavitaSessionStore {
        val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.setContent { KamiguraTheme { key(generation.intValue) {
            if (visible.value) ReaderScreen(session, settings, 1, 1, 1, 1, incognito = true, initialPage = 1, onBack = { visible.value = false })
        } } }
        awaitPixels(false)
        menu(); compose.onNodeWithText("LTR").performClick(); closeMenu()
        return session
    }
    private fun thresholdSlider() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(hasContentDescription("White threshold")))
    private fun setThreshold(value: Float) = thresholdSlider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun menu() {
        compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeMenu() = compose.onNodeWithContentDescription("Close menu").performClick()
    private fun awaitPixels(inverted: Boolean) = compose.waitUntil(15_000) {
        if (compose.onAllNodesWithText("...", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) false else {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val columns = if (pixels.width > pixels.height) listOf(.25f, .75f) else listOf(.5f)
            columns.all { column ->
                val top = pixels[(pixels.width * column).toInt(), (pixels.height * .35f).toInt()]
                val bottom = pixels[(pixels.width * column).toInt(), (pixels.height * .65f).toInt()]
                if (inverted) top.red < .1f && bottom.red > .9f else top.red > .9f && bottom.red < .1f
            }
        }
    }
    private fun halfWhite(): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        canvas.drawRect(0f, 450f, 600f, 900f, Paint().apply { color = Color.BLACK })
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle(); return bytes
    }
    private fun capture(name: String) {
        val bitmap = if (name == "phone-menu") compose.onNodeWithTag("Threshold menu").captureToImage().asAndroidBitmap()
            else InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.getExternalFilesDir(null)!!.resolve("smart-threshold-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun withSettings(transition: PageTransition, block: () -> Unit) {
        val original = runBlocking { settings.flow.first().reader.image }
        val file = context.cacheDir.resolve("reader_session_preferences.json")
        val saved = file.takeIf { it.exists() }?.readBytes()
        try {
            runBlocking {
                ReaderSessionPreferenceCache.clear(context.cacheDir)
                settings.setPageTransition(transition); settings.setInvertMode(InvertMode.Smart)
                settings.setInvertWhiteThreshold(.9f); settings.setPrefetchTurns(2)
            }
            block()
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            runBlocking {
                ReaderSessionPreferenceCache.persist(context.cacheDir)
                settings.setPageTransition(original.pageTransition); settings.setInvertMode(original.invertMode)
                settings.setInvertWhiteThreshold(original.invertWhiteThreshold); settings.setPrefetchTurns(original.prefetchTurns)
            }
            if (saved == null) file.delete() else file.writeBytes(saved)
            ReaderSessionPreferenceCache.clearMemoryForTest()
        }
    }
}
