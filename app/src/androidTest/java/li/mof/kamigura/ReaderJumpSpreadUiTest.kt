package li.mof.kamigura

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderJumpSpreadUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rtlSliderKeepsPairingAndOrdinaryTurnsAcrossWidePage() {
        ReaderServer(1, spineCount = 181, widePages = setOf(1)).use { server ->
            open(server)
            visibleSingle(0)
            menu()
            for ((requested, origin) in listOf(7 to 6, 8 to 8, 12 to 12, 16 to 16, 7 to 6)) {
                jump(requested, origin, rtl = true)
                spread(origin, rtl = true)
            }
            closeMenu()
            turn(forward = true, rtl = true)
            spread(8, rtl = true)
            turn(forward = false, rtl = true)
            spread(6, rtl = true)
            menu()
            jump(1, 1, rtl = true)
            visibleSingle(1)
            jump(0, 0, rtl = true)
            visibleSingle(0)
        }
    }

    @Test fun manualShiftAndJumpBackKeepTheSamePairingAndSaveTheLandedOrigin() {
        ReaderServer(1, spineCount = 181, widePages = setOf(1)).use { server ->
            open(server, initialPage = 6, incognito = false)
            spread(6, rtl = true)
            turn(forward = true, rtl = true)
            spread(8, rtl = true)
            menu()
            jump(7, 6, rtl = true)
            compose.onNodeWithContentDescription("Back to p. 9").performClick()
            spread(8, rtl = true)
            compose.onNodeWithContentDescription("Back to p. 7").performClick()
            spread(6, rtl = true)
            compose.onNodeWithText("+1").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(1_200)
            spread(7, rtl = true)
            jump(14, 13, rtl = true)
            spread(13, rtl = true)
            jump(8, 7, rtl = true)
            spread(7, rtl = true)
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { server.progressWrites.lastOrNull() == (1 to 7) }
        }
    }

    @Test fun freshBookmarkOpeningUsesTheCoverPairingWithoutSavingProgress() {
        ReaderServer(1, spineCount = 181, widePages = setOf(1)).use { server ->
            open(server, initialPage = 7, incognito = false)
            spread(6, rtl = true)
            menu()
            compose.onNodeWithText("7 / 181", substring = true).assertIsDisplayed()
            compose.mainClock.advanceTimeBy(4_000)
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { !opened.value }
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun portraitBookmarkAndSliderKeepTheirExactTargets() {
        ReaderServer(1, spineCount = 181, widePages = setOf(1)).use { server ->
            open(server, initialPage = 7, landscape = false)
            visibleSingle(7)
            menu()
            jump(10, 10, rtl = true)
            visibleSingle(10)
        }
    }

    private val opened = mutableStateOf(true)

    private fun open(server: ReaderServer, initialPage: Int? = null, incognito: Boolean = true,
                     landscape: Boolean = true) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.setContent {
            MaterialTheme {
                // Use the actual reader constraints, independent of the emulator orientation.
                val density = LocalDensity.current
                Box(with(density) { Modifier.size(
                    (if (landscape) 1000 else 720).toDp(), (if (landscape) 720 else 1000).toDp()
                ) }.testTag("Jump spread reader")) {
                    if (opened.value) ReaderScreen(store, AppSettingsStore(context), 1, 1, 1, 1,
                        incognito = incognito, initialPage = initialPage, onBack = { opened.value = false })
                }
            }
        }
    }

    private fun jump(target: Int, origin: Int, rtl: Boolean) {
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            !hasAnyAncestor(hasContentDescription("Smart threshold")))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it((if (rtl) 180 - target else target).toFloat()) }
        compose.onNodeWithText("${origin + 1} / 181", substring = true).assertIsDisplayed()
        assertEquals((if (rtl) 180 - origin else origin).toFloat(),
            slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
    }

    private fun menu() {
        compose.onNodeWithTag("Jump spread reader").performTouchInput { click(Offset(width / 2f, height * .2f)) }
        visible("Close menu")
    }

    private fun closeMenu() = compose.onNodeWithContentDescription("Close menu").performClick()

    private fun turn(forward: Boolean, rtl: Boolean) {
        compose.onNodeWithTag("Jump spread reader").performTouchInput {
            click(Offset(width * if (forward != rtl) .9f else .1f, height * .5f))
        }
        compose.mainClock.advanceTimeBy(1_200)
    }

    private fun visibleSingle(page: Int) = visible("Page $page")

    private fun spread(origin: Int, rtl: Boolean) {
        visible("Left ${if (rtl) origin + 1 else origin}")
        visible("Right ${if (rtl) origin else origin + 1}")
    }

    private fun visible(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label).assertIsDisplayed()
    }
}
