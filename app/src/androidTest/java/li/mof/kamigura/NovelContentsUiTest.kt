package li.mof.kamigura

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import li.mof.kamigura.reader.ReaderScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NovelContentsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tree() = Json.encodeToString(listOf(BookChapterItemDto("Root", page = 0,
        children = listOf(BookChapterItemDto("Nested section", "target'\"", 2)))))
    private fun fragment() = "<div><p id='target&apos;&quot;'>Section opening.</p>" +
        (1..80).joinToString("") { "<p>Original paragraph $it for reader contents navigation.</p>" } + "</div>"

    @Test fun nestedContentsHighlightCurrentPositionJumpByQuotedIdAndRememberTab() {
        ReaderServer(3, bookFragment = { fragment() }, contentsResponse = { 200 to tree() }).use { server ->
            open(server); menu(); contents()
            compose.onNodeWithContentDescription("Open contents: Root").assertIsSelected()
            val parent = compose.onNodeWithText("Root", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val child = compose.onNodeWithText("Nested section", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(child.left > parent.left)
            compose.onNodeWithContentDescription("Open contents: Nested section").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() }
            assertTrue(state().contains("Page 3 /"))
            menu(); contents()
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Open contents: Nested section") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            tab("Bookmarks").performClick().assertIsSelected()
            compose.onNodeWithContentDescription("Back to reader menu").performClick()
            compose.onNodeWithContentDescription("Contents").performClick()
            tab("Bookmarks").assertIsSelected()
            compose.onNodeWithContentDescription("Back to reader menu").performClick()
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.waitUntil(5_000) { server.bookProgressWrites.isNotEmpty() }
            assertEquals(2, server.bookProgressWrites.last().pageNum)
            assertTrue(server.requests.any { it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "2" })
        }
    }

    @Test fun contents500DoesNotPreventPersonalBookmarkNavigation() {
        val personal = listOf(PersonalToCDto(1, 1, 1, "Saved paragraph", "//body/p[1]"))
        ReaderServer(3, bookFragment = { fragment() }, contentsResponse = { 500 to "Bad NCX" },
            personalHandler = { _, _, _ -> 200 to Json.encodeToString(personal) }).use { server ->
            open(server); menu(); contents()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("HTTP 500", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("table of contents may be damaged", substring = true).assertIsDisplayed()
            tab("Bookmarks").performClick()
            compose.onNodeWithText("HTTP 500", substring = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("Open bookmark: Saved paragraph").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() }
            assertTrue(state().contains("Page 2 /"))
        }
    }

    @Test fun emptyContentsStayUsableAndBookmarksAreStillAvailable() {
        ReaderServer(3, bookFragment = { fragment() }).use { server ->
            open(server); menu(); contents()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No contents in this book.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("No contents in this book.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Refresh contents").assertIsEnabled()
            tab("Bookmarks").performClick()
            compose.onNodeWithText("No bookmarks in this book.").assertIsDisplayed()
        }
    }

    @Test fun singleEntryWithoutPartOpensTheStartOfItsSpine() {
        val single = Json.encodeToString(listOf(BookChapterItemDto("Only section", page = 1)))
        ReaderServer(3, bookFragment = { fragment() }, contentsResponse = { 200 to single }).use { server ->
            open(server); menu(); contents()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open contents: Only section").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Open contents: Only section").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() }
            assertTrue(state().contains("Page 2 /")); assertTrue(state().contains("Screen 1 /"))
        }
    }

    @Test fun incognitoCanReadContentsWithoutExposingPersonalBookmarksOrSendingProgress() {
        ReaderServer(3, bookFragment = { fragment() }, contentsResponse = { 200 to tree() }).use { server ->
            open(server, incognito = true); menu(); contents()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open contents: Nested section").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Bookmarks").assertDoesNotExist()
            compose.onNodeWithContentDescription("Open contents: Nested section").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() }
            assertTrue(state().contains("Page 3 /"))
            assertFalse(server.requests.any { it.path.orEmpty().contains("ptoc") })
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun open(server: ReaderServer, incognito: Boolean = false) {
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.setContent { MaterialTheme { ReaderScreen(store, AppSettingsStore(context), 1, 1, 1, 1,
            incognito = incognito, onBack = {}) } }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun state() = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun menu() { compose.onRoot().performTouchInput { click(center) }; compose.waitUntil(5_000) {
        compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()
    } }
    private fun contents() {
        compose.onNodeWithContentDescription("Contents").performClick()
    }
    private fun tab(name: String) = compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
}
