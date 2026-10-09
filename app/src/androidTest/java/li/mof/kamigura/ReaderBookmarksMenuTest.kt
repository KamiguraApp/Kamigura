package li.mof.kamigura

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.mof.kamigura.reader.internal.ReaderBookmarkState
import li.mof.kamigura.reader.internal.ReaderMenuOverlay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderBookmarksMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun phoneMenuAddsAndRemovesOriginPageWithoutClosingOrCrowdingControls() {
        val state = mutableStateOf<ReaderBookmarkState?>(ReaderBookmarkState(loaded = true))
        var clicks = 0
        var dismissals = 0
        menu(320, state = { state.value }, onDismiss = { dismissals++ }) {
            clicks++
            state.value = if (clicks == 1) {
                ReaderBookmarkState(pages = setOf(5), loaded = true, message = "Page 6 bookmarked.")
            } else {
                ReaderBookmarkState(loaded = true, message = "Bookmark removed from page 6.")
            }
        }
        compose.onNodeWithText("p. 6").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bookmark page 6").performClick()
        compose.onNodeWithText("Page 6 bookmarked.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove bookmark from page 6").assertIsDisplayed()
        val bookmark = compose.onNodeWithContentDescription("Remove bookmark from page 6")
            .fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close menu").fetchSemanticsNode().boundsInRoot
        assertTrue(bookmark.right <= close.left)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("Direction").assertIsDisplayed()
        compose.onNodeWithText("Invert").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove bookmark from page 6").performClick()
        compose.onNodeWithText("Bookmark removed from page 6.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bookmark page 6").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, clicks); assertEquals(0, dismissals) }
    }

    @Test
    fun tabletSpreadTargetsTheSameOriginForRtlAndLtr() {
        val direction = mutableStateOf(ReaderReadingDirection.RightToLeft)
        var clicks = 0
        menu(800, state = { ReaderBookmarkState(pages = setOf(5), loaded = true) },
            direction = { direction.value }, spread = true) { clicks++ }
        compose.onNodeWithText("p. 6").assertIsDisplayed()
        compose.onNodeWithText("p. 7").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove bookmark from page 6").performClick()
        compose.runOnIdle { direction.value = ReaderReadingDirection.LeftToRight }
        compose.onNodeWithContentDescription("Remove bookmark from page 6").performClick()
        compose.onNodeWithText("Spread shift").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    fun failedStatusOffersRefreshAndBusyStateCannotBeTapped() {
        val state = mutableStateOf<ReaderBookmarkState?>(ReaderBookmarkState(
            error = "Could not save bookmark for page 6. Tap to check status."))
        var clicks = 0
        menu(320, state = { state.value }) { clicks++ }
        compose.onNodeWithText("Could not save bookmark for page 6. Tap to check status.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Check bookmark status for page 6").performClick()
        compose.runOnIdle { state.value = ReaderBookmarkState(loading = true) }
        compose.onNode(hasStateDescription("Bookmark status unavailable") and hasClickAction())
            .assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun unavailableBookmarkOperationIsAbsentButMenuControlsRemain() {
        menu(320, state = { null }) {}
        compose.onNodeWithText("p. 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("Bookmark page 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("Check bookmark status for page 6").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close menu").assertIsDisplayed()
    }

    @Test
    fun shortLandscapeFeedbackDoesNotOverlapSettings() {
        val error = "Could not save bookmark for page 6. Tap to check status."
        menu(640, height = 320, state = { ReaderBookmarkState(error = error) }, spread = true) {}
        val feedback = compose.onNodeWithText(error).fetchSemanticsNode().boundsInRoot
        val direction = compose.onNodeWithText("Direction").fetchSemanticsNode().boundsInRoot
        assertTrue(feedback.bottom <= direction.top)
        compose.onNodeWithText("Spread shift").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close menu").assertIsDisplayed()
    }

    private fun menu(
        width: Int,
        height: Int = 700,
        state: () -> ReaderBookmarkState?,
        direction: () -> ReaderReadingDirection = { ReaderReadingDirection.RightToLeft },
        spread: Boolean = false,
        onDismiss: () -> Unit = {},
        onBookmark: () -> Unit
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Box(Modifier.size(width.dp, height.dp).background(Color.Black)) {
                        ReaderMenuOverlay(
                            seriesName = "A very long series name that must not push out the controls",
                            chapterName = "A very long volume and chapter title",
                            page = 5, pages = 20,
                            bookmarkState = state(), onBookmark = onBookmark,
                            readingDirection = direction(), showSpreadShift = spread,
                            onBack = {}, onDismiss = onDismiss, onSetReadingDirection = {},
                            invertMode = InvertMode.Off, onSetInvertMode = {},
                            onNextSingle = {}, onPreviousSingle = {}, onJumpToPage = {}
                        )
                    }
                }
            }
        }
    }
}
