package li.mof.kamigura

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import li.mof.kamigura.library.ReadingListItems
import li.mof.kamigura.library.ReadingListsPane
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class ReadingListItemsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersEveryQueueEntryAndOpensTheSelectedChapterIncludingDuplicates() {
        val entries = (0 until 16).map { index ->
            ReadingListItemDto(id = 100 - index, order = index, chapterId = if (index == 15) 1 else index + 1,
                volumeId = 20 + index, seriesId = if (index % 2 == 0) 2 else 1,
                libraryId = 7, seriesName = if (index % 2 == 0) "Zebra" else "Apple",
                chapterNumber = "${index + 1}", chapterTitleName = "Entry ${index + 1}",
                seriesFormat = if (index % 2 == 0) 4 else 3)
        }
        var selected: ReadingListItemDto? = null
        compose.setContent {
            MaterialTheme { ReadingListItems(entries, KavitaSession()) { selected = it } }
        }
        val first = compose.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("2").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < second.top)
        compose.onNodeWithText("1").performClick()
        compose.runOnIdle { assertEquals(entries[0], selected) }
        compose.onNodeWithText("2").performClick()
        compose.runOnIdle { assertEquals(entries[1], selected) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        compose.onNodeWithText("16").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(entries[15], selected) }
    }

    @Test
    fun listRowCountsItemsNotDistinctSeries() {
        val api = Proxy.newProxyInstance(KavitaApi::class.java.classLoader, arrayOf(KavitaApi::class.java)) {
                _, method, _ ->
            check(method.name == "readingLists")
            listOf(ReadingListDto(id = 1, title = "umeueuu", itemCount = 16))
        } as KavitaApi
        compose.setContent {
            MaterialTheme { ReadingListsPane(api = api, onBack = {}, onOpenReadingList = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("16 items").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("16 items").assertIsDisplayed()
        compose.onNodeWithText("16 series").assertDoesNotExist()
    }
}
