package li.mof.kamigura

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import li.mof.kamigura.library.BookmarksScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookmarksScreenUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapOpensPageAndLongPressStartsSelection() = withBookmarks { _, _ ->
        page(8).performClick()
        assertEquals(listOf(1, 1, 1, 1, 7), opened.single())
        selectFirst()
        compose.onAllNodes(isToggleable() and isOn()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        assertEquals(1, opened.size)
    }

    @Test fun selectAllDeletesBothPagesAndShowsEmptyState() = withBookmarks { server, removals ->
        selectFirst()
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onAllNodes(isToggleable() and isOn()).assertCountEquals(2)
        compose.onNodeWithContentDescription("Clear selection").performClick()
        compose.onNodeWithContentDescription("Delete selected bookmarks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Select all").performClick()
        delete()
        awaitText("No bookmarked pages yet.")
        assertEquals(listOf(7, 8), removals.map { it.page })
        assertEquals(5, removals.last().imageOffset)
        assertEquals(2, server.requests.count { it.path == "/api/Reader/all-bookmarks" })
    }

    @Test fun partialFailureKeepsFailedPageSelectedAndRefreshesItsThumbnail() {
        val fail = AtomicBoolean(true)
        withBookmarks(fail) { server, removals ->
            compose.waitUntil(5_000) {
                server.requests.any { it.getQueryParameter("bookmarkId") == "12" && it.getQueryParameter("page") == "1" }
            }
            compose.onNodeWithContentDescription("Select items").performClick()
            compose.onNodeWithContentDescription("Select all").performClick()
            delete()
            awaitText("Removed 1 bookmark. Could not remove 1 bookmark.")
            page(8).assertDoesNotExist()
            page(9).assertIsDisplayed()
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onAllNodes(isToggleable() and isOn()).assertCountEquals(1)
            compose.waitUntil(5_000) {
                server.requests.any { it.getQueryParameter("bookmarkId") == "12" && it.getQueryParameter("page") == "0" }
            }
            fail.set(false)
            delete()
            awaitText("No bookmarked pages yet.")
            assertEquals(listOf(7, 8, 8), removals.map { it.page })
        }
    }

    private val opened = mutableListOf<List<Int>>()

    private fun withBookmarks(
        fail: AtomicBoolean = AtomicBoolean(false),
        verify: (ReaderServer, List<BookmarkPageDto>) -> Unit
    ) {
        val series = SeriesDto(1, "Bookmark fixture", libraryId = 1)
        val pages = CopyOnWriteArrayList(listOf(
            BookmarkDto(id = 11, page = 7, volumeId = 1, seriesId = 1, chapterId = 1, series = series),
            BookmarkDto(id = 12, page = 8, volumeId = 1, seriesId = 1, chapterId = 1, series = series, imageOffset = 5)
        ))
        val removals = CopyOnWriteArrayList<BookmarkPageDto>()
        ReaderServer(1, bookmarkHandler = { method, uri, body ->
            assertEquals("POST", method)
            if (uri.path == "/api/Reader/all-bookmarks") {
                200 to Json.encodeToString(pages.toList())
            } else {
                val target = Json.decodeFromString<BookmarkPageDto>(body)
                removals += target
                if (target.page == 8 && fail.get()) 500 to "{}" else {
                    val removed = pages.removeIf {
                        it.page == target.page && it.chapterId == target.chapterId && it.imageOffset == target.imageOffset
                    }
                    (if (removed) 200 else 400) to "{}"
                }
            }
        }).use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = KavitaSessionStore(context).apply {
                useTransient(KavitaSession(baseUrl = server.baseUrl, apiKey = "fixture"))
            }
            compose.setContent {
                MaterialTheme {
                    BookmarksScreen(store, {}) { library, seriesId, volume, chapter, page ->
                        opened += listOf(library, seriesId, volume, chapter, page)
                    }
                }
            }
            awaitText("p. 8")
            verify(server, removals)
            assertTrue(server.requests.none { it.path.orEmpty().contains("ptoc") })
        }
    }

    private fun page(number: Int) = compose.onNodeWithText("p. $number")

    private fun selectFirst() {
        page(8).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    private fun delete() = compose.onNodeWithContentDescription("Delete selected bookmarks").performClick()

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
