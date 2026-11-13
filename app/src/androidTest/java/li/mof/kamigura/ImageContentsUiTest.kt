package li.mof.kamigura

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.serialization.json.Json
import li.mof.kamigura.reader.ReaderScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageContentsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity.applicationContext
    private val endpoint = "/api/Book/1/chapters"
    private val contentsButton get() = compose.onNodeWithContentDescription("Contents")
    private val section = "Open contents: Section"

    @Test fun contentsKeepsSpreadHighlightsTheVisibleEntryAndOffersJumpBack() {
        imageEpub(listOf(BookChapterItemDto("Cover"), BookChapterItemDto("Section", part = "start", page = 2))).use { server ->
            open(server)
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            val landscape = compose.onRoot().fetchSemanticsNode().size.let { it.width >= it.height }
            val returnPage = if (landscape) 6 else 5
            menu()
            waitForContents()
            contentsButton.performClick()
            compose.onNodeWithContentDescription(section).assertIsSelected()
            compose.onRoot().performTouchInput { click(Offset(width * .1f, height * .8f)) }
            compose.onNodeWithContentDescription(section).assertIsSelected()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            waitForContents()
            compose.onNodeWithText("$returnPage / 8", substring = true).assertIsDisplayed()
            contentsButton.performClick()
            compose.onNodeWithContentDescription(section).performClick()
            if (landscape) {
                awaitImage("Right 1")
                awaitImage("Left 2")
            } else awaitImage("Page 2")
            menu()
            val back = compose.onNodeWithContentDescription("Back to p. $returnPage").assertIsDisplayed()
            val bookmark = compose.onNodeWithContentDescription("Bookmark page ${if (landscape) 2 else 3}")
            compose.waitUntil(5_000) { bookmark.isDisplayed() }
            val close = compose.onNodeWithContentDescription("Close menu")
            assertTrue(contentsButton.fetchSemanticsNode().boundsInRoot.right <= back.fetchSemanticsNode().boundsInRoot.left)
            assertTrue(back.fetchSemanticsNode().boundsInRoot.right <= bookmark.fetchSemanticsNode().boundsInRoot.left)
            assertTrue(bookmark.fetchSemanticsNode().boundsInRoot.right <= close.fetchSemanticsNode().boundsInRoot.left)
            contentsButton.performClick()
            compose.onNodeWithContentDescription(section).assertIsSelected()
            compose.onNodeWithContentDescription("Back to reader menu").performClick()
            back.performClick()
            compose.onNodeWithText("$returnPage / 8", substring = true).assertIsDisplayed()
        }
    }

    @Test fun fewerThanTwoValidEntriesDoesNotOfferContents() {
        imageEpub(listOf(BookChapterItemDto("Cover"), BookChapterItemDto("Out of range", page = 8))).use { server ->
            open(server, incognito = true)
            menu()
            compose.waitUntil(5_000) { server.requests.any { it.path == endpoint } }
            contentsButton.assertDoesNotExist()
        }
    }

    @Test fun incognitoContentsIsReadOnly() {
        imageEpub(listOf(BookChapterItemDto("Cover"), BookChapterItemDto("Section", page = 2))).use { server ->
            open(server, incognito = true)
            menu()
            waitForContents()
            contentsButton.performClick()
            compose.onNodeWithText("Bookmarks").assertDoesNotExist()
            compose.onNodeWithContentDescription(section).performClick()
            compose.onNodeWithContentDescription("Close menu").assertDoesNotExist()
            assertTrue(server.progressWrites.isEmpty())
            assertFalse(server.requests.any { it.path == "/api/Reader/chapter-bookmarks" })
        }
    }

    @Test fun archiveAndPdfNeverRequestContents() {
        for (format in listOf(1, MangaFormat.Pdf)) {
            ReaderServer(format, spineCount = 8).use { server ->
                open(server, incognito = true)
                menu()
                contentsButton.assertDoesNotExist()
                assertFalse(server.requests.any { it.path == endpoint })
            }
            compose.runOnIdle { opening.value = null }
        }
    }

    private data class Opening(val session: KavitaSessionStore, val incognito: Boolean)
    private val opening = androidx.compose.runtime.mutableStateOf<Opening?>(null)

    @org.junit.Before fun hostReader() {
        compose.setContent { MaterialTheme {
            opening.value?.let {
                ReaderScreen(it.session, AppSettingsStore(context), 1, 1, 1, 1, incognito = it.incognito,
                    initialPage = 3, onBack = { opening.value = null })
            }
        } }
    }

    private fun imageEpub(items: List<BookChapterItemDto>) = ReaderServer(
        MangaFormat.Epub, spineCount = 8,
        bookFragment = { "<img src='/api/Book/1/book-resources?file=$it.png'>" },
        contentsResponse = { 200 to Json.encodeToString(items) }
    )

    private fun open(server: ReaderServer, incognito: Boolean = false) {
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.runOnIdle { opening.value = Opening(store, incognito) }
        compose.waitUntil(15_000) { server.requests.any {
            it.path == "/api/Reader/image" || it.path.orEmpty().endsWith("/book-resources")
        } }
        compose.waitForIdle()
    }

    private fun menu() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * .2f)) }
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Close menu").isDisplayed() }
    }

    private fun waitForContents() = compose.waitUntil(5_000) { contentsButton.isDisplayed() }

    private fun awaitImage(label: String) = compose.waitUntil(10_000) {
        compose.onNodeWithContentDescription(label).isDisplayed()
    }

    private fun key(code: Int) {
        compose.runOnIdle {
            val callback = compose.activity.window.callback
            callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.mainClock.advanceTimeBy(1_200)
    }
}
