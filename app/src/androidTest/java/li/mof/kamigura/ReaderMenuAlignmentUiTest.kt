package li.mof.kamigura

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.internal.ReaderMenuOverlay
import li.mof.kamigura.reader.novel.NovelReaderMenu
import li.mof.kamigura.reader.novel.NovelReaderPreferences
import li.mof.kamigura.ui.theme.KamiguraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderMenuAlignmentUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageNarrowPanel() = imagePanel(320, 700, "narrow")
    @Test fun imagePhonePanel() = imagePanel(360, 700, "phone")
    @Test fun imageTabletPanel() = imagePanel(1000, 650, "tablet")
    @Test fun novelPhonePanel() = novelPanel(320, 700, "phone")
    @Test fun novelTabletPanel() = novelPanel(1000, 650, "tablet")

    private fun imagePanel(width: Int, height: Int, size: String) {
        val mode = mutableStateOf(InvertMode.Off)
        val spread = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                KamiguraTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp).background(Color(0xFFFAF7F2))) {
                        ReaderMenuOverlay(
                            seriesName = "Image book", issueLabel = "#1", page = 739, pages = 1812,
                            bookmarkState = null, onBookmark = {},
                            readingDirection = ReaderReadingDirection.RightToLeft, showSpreadShift = spread.value,
                            onBack = {}, onDismiss = {}, onSetReadingDirection = {},
                            invertMode = mode.value, onSetInvertMode = { mode.value = it },
                            whiteThreshold = .85f, onSetWhiteThreshold = {},
                            onNextSingle = {}, onPreviousSingle = {}, onJumpToPage = {}
                        )
                    }
                }
            }
        }
        val normalButtons = listOf(listOf("RTL", "Vertical", "LTR"), listOf("Off", "Smart", "Always"))
        compose.onNodeWithText("Smart threshold").assertDoesNotExist()
        compose.onNodeWithText("Spread shift").assertDoesNotExist()
        val originalButtons = assertButtons(normalButtons)
        val basicTrack = track("Page navigation track")
        capture("Image reader controls", "image-$size-basic")

        // Choosing Smart, or a spread layout, must not move the buttons sideways.
        compose.onNodeWithText("Smart", useUnmergedTree = true).performClick()
        assertEquals(originalButtons, assertButtons(normalButtons), 0f)
        if (size == "tablet") compose.runOnIdle { spread.value = true }
        val withSpread = if (size == "tablet") normalButtons + listOf(listOf("+1", "-1")) else normalButtons
        assertEquals(originalButtons, assertButtons(withSpread), 0f)
        if (size != "tablet") compose.onNodeWithText("Spread shift").assertDoesNotExist()

        val threshold = track("Smart threshold track")
        assertEquals(originalButtons, threshold.left, 3f)
        assertEquals(buttonsRight("LTR"), threshold.right, 3f)
        assertTextFits("Smart threshold")
        compose.onNodeWithText("85%").assertDoesNotExist()

        val page = track("Page navigation track")
        assertSameEdges(page, basicTrack)
        if (size != "narrow") assertTrue(page.width > threshold.width) // 320 dp stacks the labels
        assertTrue(textBounds("1812").right <= page.left)
        assertTrue(textBounds("1").left >= page.right)
        capture("Image reader controls", "image-$size-smart")
    }

    private fun novelPanel(width: Int, height: Int, size: String) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                KamiguraTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp).background(Color(0xFFFAF7F2))) {
                        NovelReaderMenu(
                            "Novel", "Vol. 1", 739, 1812, null,
                            NovelReaderPreferences(writingStyle = 1), true, {}, {}, {}, {}, {}, {}
                        )
                    }
                }
            }
        }
        val buttons = assertButtons(listOf(listOf("Horizontal", "Vertical"), listOf("Paper", "Dark")))
        val font = track("Font size track")
        assertEquals(buttons, font.left, 3f)
        assertEquals(buttonsRight("Dark"), font.right, 3f)
        val page = track("Page navigation track")
        assertTrue(page.width > font.width)
        assertTrue(textBounds("1812").right <= page.left)
        assertTrue(textBounds("1").left >= page.right)
        assertTextFits("Font size")
        capture("Novel reader controls", "novel-$size")
    }

    private fun assertButtons(rows: List<List<String>>): Float {
        val lefts = rows.map { row ->
            row.map { label ->
                assertTextFits(label)
                val button = compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().fetchSemanticsNode()
                val touch = button.touchBoundsInRoot
                assertTrue("$label touch bounds: $touch", touch.width >= 48f && touch.height >= 48f)
                button.boundsInRoot.left
            }.first()
        }
        lefts.forEach { assertEquals(lefts.first(), it, 0f) }
        return lefts.first()
    }

    private fun assertTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals("$label wrapped", 1, result.lineCount)
        assertFalse("$label ellipsized at ${result.size}", result.isLineEllipsized(0))
        assertFalse("$label clipped vertically at ${result.size}", result.didOverflowHeight)
    }

    private fun buttonsRight(lastOption: String) =
        compose.onNode(hasText(lastOption) and hasClickAction()).fetchSemanticsNode().boundsInRoot.right

    private fun textBounds(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot

    private fun track(tag: String): Rect {
        val slider = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyDescendant(hasTestTag(tag)),
            useUnmergedTree = true
        ).fetchSemanticsNode()
        assertTrue("$tag touch target: ${slider.touchBoundsInRoot}", slider.touchBoundsInRoot.height >= 48f)
        return compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    }

    private fun assertSameEdges(first: Rect, second: Rect) {
        assertEquals(first.left, second.left, 0f)
        assertEquals(first.right, second.right, 0f)
    }

    private fun capture(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        context.cacheDir.resolve("v031-menu-alignment-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
