package li.mof.kamigura

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.novel.NovelPosition
import li.mof.kamigura.reader.novel.NovelReaderMenu
import li.mof.kamigura.reader.novel.NovelReaderPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NovelReaderMenuUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phonePortraitMenu() = layout(420, 880, "phone-portrait")
    @Test fun phoneLandscapeMenu() = layout(880, 420, "phone-landscape")
    @Test fun tabletPortraitMenu() = layout(800, 1100, "tablet-portrait")
    @Test fun tabletLandscapeMenu() = layout(1100, 800, "tablet-landscape")

    private fun layout(width: Int, height: Int, name: String) {
        show(width, height)
        compose.onNodeWithText("Writing").assertIsDisplayed()
        compose.onNodeWithText("Vertical").assertIsOn()
        compose.onNodeWithText("Volume 33 | Page 16 / 21 \u2022 Screen 11 / 23").assertIsDisplayed()
        val header = compose.onNodeWithContentDescription("Close menu").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithText("Writing").fetchSemanticsNode().boundsInRoot
        assertTrue("Header must not overlap the scrollable panel", header.bottom <= panel.top)
        val slider = slider("EPUB page navigation").fetchSemanticsNode()
        assertEquals(5f, slider.config[SemanticsProperties.ProgressBarRangeInfo].current)
        val left = compose.onNodeWithText("21").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= slider.boundsInRoot.left)
        assertTrue(slider.boundsInRoot.right <= right.left)
        assertTrue(compose.onAllNodesWithText("Section", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Page").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("complete", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("within this page", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("100%").fetchSemanticsNodes().isEmpty())
        val fontLabel = compose.onNodeWithText("Font size").fetchSemanticsNode().boundsInRoot
        val fontSlider = slider("Font size").fetchSemanticsNode().boundsInRoot
        assertTrue(fontLabel.right <= fontSlider.left)
        assertTrue(fontLabel.center.y in fontSlider.top..fontSlider.bottom)
        assertTrue(fontSlider.bottom <= slider.boundsInRoot.top)
        compose.onNodeWithContentDescription("Back to series").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close menu").assertIsDisplayed()
        compose.onNodeWithText("Writing").performScrollTo()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "b-ui2-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun directionChangesEndpointsAndBothSlidersDispatchTheirLogicalValues() {
        val preferences = mutableStateOf(NovelReaderPreferences(writingStyle = 1))
        val page = mutableStateOf(15)
        var jumped = -1
        show(420, 880, page = { page.value }, preferences = { preferences.value }, onStyle = {
            preferences.value = preferences.value.copy(writingStyle = it)
        }, onFont = { preferences.value = preferences.value.copy(fontSize = it) }, onJump = { jumped = it; page.value = it })
        compose.onNodeWithText("Horizontal").performClick().assertIsOn()
        compose.onNodeWithText("Vertical").assertIsOff()
        val slider = slider("EPUB page navigation")
        assertEquals(15f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current)
        val left = compose.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= slider.fetchSemanticsNode().boundsInRoot.left)
        slider.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .25f, height / 2f)) }
        compose.runOnIdle { assertTrue("LTR reached $jumped", jumped in 4..6) }
        compose.onNodeWithText("Vertical").performClick()
        slider.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .25f, height / 2f)) }
        compose.runOnIdle { assertTrue("RTL reached $jumped", jumped in 14..16) }
        slider("Font size").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .25f, height / 2f))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("100%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("100%").assertIsDisplayed()
        slider("Font size").performTouchInput { up() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("100%").fetchSemanticsNodes().isEmpty() }
        slider("Font size").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width / 2f, height / 2f))
        }
        compose.runOnIdle { assertEquals(150, preferences.value.fontSize) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("150%").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun singlePageAndLoadingMenusKeepLabelsWithoutOfferingInvalidActions() {
        show(420, 880, pages = 1, page = { 0 }, enabled = false)
        slider("EPUB page navigation").assertIsNotEnabled()
        slider("Font size").assertIsNotEnabled()
        compose.onNodeWithText("Vertical").assertIsNotEnabled()
        compose.onNodeWithText("Page 1 / 1", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to series").assertIsEnabled()
        compose.onNodeWithContentDescription("Close menu").assertIsEnabled()
    }

    private fun slider(label: String) = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            hasAnyAncestor(hasContentDescription(label)))

    private fun show(width: Int, height: Int, page: () -> Int = { 15 }, pages: Int = 21, enabled: Boolean = true,
        preferences: () -> NovelReaderPreferences = { NovelReaderPreferences(writingStyle = 1) },
        onStyle: (Int) -> Unit = {}, onFont: (Int) -> Unit = {}, onJump: (Int) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, height.dp).background(Color(0xFFFAF7F2))) {
                        NovelReaderMenu("A long novel series title that must not crowd either icon",
                            "Volume 33", page(), pages, NovelPosition(10, 23, true, "//body/p[57]"),
                            preferences(), enabled, {}, {}, onStyle, onFont, onJump)
                    }
                }
            }
        }
    }
}
