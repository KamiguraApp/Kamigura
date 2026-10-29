package li.mof.kamigura

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.internal.ReaderBookmarkState
import li.mof.kamigura.reader.internal.ReaderChapterEntry
import li.mof.kamigura.reader.internal.ReaderChapterNeighbors
import li.mof.kamigura.reader.novel.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NovelBookmarksUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class PersonalFixture {
        val items = CopyOnWriteArrayList<PersonalToCDto>()
        val creates = CopyOnWriteArrayList<CreatePersonalToCDto>()
        val deletes = CopyOnWriteArrayList<Triple<Int, Int, String>>()
        var failure = false
        fun handle(method: String, uri: android.net.Uri, body: String): Pair<Int, String> = when (method) {
            "POST" -> {
                creates += Json.decodeFromString<CreatePersonalToCDto>(body)
                if (failure) 500 to "Damaged NCX" else {
                    val dto = creates.last()
                    items += PersonalToCDto(items.size + 1, dto.chapterId, dto.pageNumber, dto.title, dto.bookScrollId)
                    200 to "{}"
                }
            }
            "DELETE" -> {
                val chapter = uri.getQueryParameter("chapterId")!!.toInt()
                val page = uri.getQueryParameter("pageNum")!!.toInt()
                val title = uri.getQueryParameter("title")!!
                deletes += Triple(chapter, page, title)
                items.removeAll { it.chapterId == chapter && it.pageNumber == page && it.title == title }
                200 to "{}"
            }
            else -> 200 to Json.encodeToString(items.toList())
        }
    }

    @Test fun headerAddsAndRemovesCurrentPositionAndListShowsItsEmptyState() {
        val fixture = PersonalFixture()
        ReaderServer(3, bookFragment = { novelFixture(false) }, personalHandler = fixture::handle).use { server ->
            open(server); menu(); waitButton("Bookmark page 1")
            compose.onNodeWithContentDescription("Bookmark page 1").performClick()
            waitButton("Remove bookmark from page 1")
            compose.onNodeWithText("Page 1 bookmarked.").assertIsDisplayed()
            val added = fixture.creates.single()
            assertEquals(0, added.pageNumber); assertEquals(1, added.chapterId)
            assertEquals("id(\"paragraph-0\")", added.bookScrollId)
            assertEquals("This is a paragraph", added.title)
            compose.onNodeWithContentDescription("Remove bookmark from page 1").performClick()
            waitButton("Bookmark page 1")
            compose.onNodeWithContentDescription("Contents").performClick()
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithText("No bookmarks in this book.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to reader menu").performClick()
            compose.onNodeWithText("Background").assertIsDisplayed()
            assertEquals(Triple(1, 0, added.title), fixture.deletes.single())
            assertTrue(server.requests.count { it.path == "/api/reader/ptoc" && it.getQueryParameter("title") == null } <= 4)
            assertFalse(server.requests.any { it.path == "/api/Reader/all-bookmarks" || it.path == "/api/Reader/bookmark" })
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun verticalWebBookmarkJumpsAcrossSpinesSurvivesRestorationAndDeletesFromList() {
        val fixture = PersonalFixture()
        fixture.items += PersonalToCDto(10, 1, 2, "Web selection", "//body/p[40]/ruby[1]", "selection", "Part Two")
        fixture.items += PersonalToCDto(11, 1, 0, "First page", "//body/p[1]", chapterTitle = null)
        val fragment = "<div>" + (1..80).joinToString("") {
            "<p>これは本文です。段落番号 $it <ruby>位置<rt>いち</rt></ruby> を読みます。</p>"
        } + "</div>"
        ReaderServer(3, bookFragment = { fragment }, readingProfile = """{"bookReaderWritingStyle":1}""",
            personalHandler = fixture::handle).use { server ->
            val restoration = StateRestorationTester(compose)
            open(server, restoration = restoration); menu()
            compose.onNodeWithContentDescription("Contents").performClick()
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithText("Chapter Part Two, Page 3").assertIsDisplayed()
            compose.onNodeWithText("Page 1").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open bookmark: Web selection").performClick()
            awaitLoaded(); compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() }
            assertTrue(state().contains("Page 3 /"))
            assertTrue(viewContext(fixture.items.toList()).visible.contains(10))
            restoration.emulateSavedInstanceStateRestore(); awaitLoaded()
            menu(); waitButton("Remove bookmark from page 3")
            val source = viewPosition().location
            compose.onNodeWithText("Horizontal").performClick()
            compose.waitUntil(5_000) { !viewPosition().vertical }
            assertEquals(source, viewPosition().location)
            // Reflow retains the first visible character, not every previously visible bookmark.
            val stillVisible = viewContext(fixture.items.toList()).visible.contains(10)
            waitButton(if (stillVisible) "Remove bookmark from page 3" else "Bookmark page 3")
            compose.onNodeWithContentDescription("Contents").performClick()
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithContentDescription("Remove bookmark: Web selection").performClick()
            compose.waitUntil(5_000) { fixture.items.none { it.id == 10 } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Web selection").fetchSemanticsNodes().isEmpty() }
            assertEquals(Triple(1, 2, "Web selection"), fixture.deletes.single())
            assertEquals("First page", fixture.items.single().title)
        }
    }

    @Test fun server500ExplainsDamagedContentsAndRefreshAllowsRecoveryWithoutBlindReplay() {
        val fixture = PersonalFixture().apply { failure = true }
        ReaderServer(3, bookFragment = { novelFixture(false) }, personalHandler = fixture::handle).use { server ->
            open(server); menu(); waitButton("Bookmark page 1")
            compose.onNodeWithContentDescription("Bookmark page 1").performClick()
            waitButton("Check bookmark status for page 1")
            compose.onNodeWithText("table of contents may be damaged", substring = true).assertIsDisplayed()
            assertEquals(1, fixture.creates.size)
            fixture.failure = false
            compose.onNodeWithContentDescription("Check bookmark status for page 1").performClick()
            waitButton("Bookmark page 1")
            assertEquals(1, fixture.creates.size)
            compose.onNodeWithContentDescription("Bookmark page 1").performClick()
            waitButton("Remove bookmark from page 1")
            assertEquals(2, fixture.creates.size)
        }
    }

    @Test fun incognitoHidesBothActionsAndNeverReadsPersonalContents() {
        ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
            open(server, incognito = true); menu()
            compose.onNodeWithContentDescription("Bookmarks").assertDoesNotExist()
            compose.onNodeWithContentDescription("Bookmark page 1").assertDoesNotExist()
            assertFalse(server.requests.any { it.path.orEmpty().contains("ptoc") })
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun offlineEligibilityHidesBothActionsEvenWhenAnApiIsAvailable() {
        ReaderServer(3, bookFragment = { novelFixture(false) }).use { server ->
            val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val api = kotlinx.coroutines.runBlocking { KavitaClient(context, store).buildApi().first }
            compose.setContent { MaterialTheme { NovelReaderScreen(api, server.baseUrl, 1, 1, 1, 1, 6,
                "Test book", "First", ProgressDto(1, 1, 1, 1, 0), false, false, {}, {}, null,
                "offline-novel-fixture", PageBackground.Paper, false, ReaderChapterEntry(1, 1, null, "First"),
                ReaderChapterNeighbors(null, null), false, false, false, null, { _, _ -> }, offline = true) } }
            awaitLoaded(); menu()
            compose.onNodeWithContentDescription("Bookmarks").assertDoesNotExist()
            compose.onNodeWithContentDescription("Bookmark page 1").assertDoesNotExist()
            assertFalse(server.requests.any { it.path.orEmpty().contains("ptoc") })
        }
    }

    @Test fun phoneHeaderKeepsActionsSeparate() = header(420, 880)
    @Test fun tabletHeaderKeepsActionsSeparate() = header(1100, 800)
    private fun header(width: Int, height: Int) {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) { MaterialTheme {
            androidx.compose.foundation.layout.Box(Modifier.requiredSize(width.dp, height.dp)) {
                NovelReaderMenu("A long novel series name that must not crowd the actions", "Volume 33", 0, 21,
                    NovelPosition(0, 12, true, "//body/p[1]"), NovelReaderPreferences(), true,
                    {}, {}, {}, {}, {}, {}, ReaderBookmarkState(loaded = true), {}, {})
            }
        } } }
        val list = compose.onNodeWithContentDescription("Contents").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bookmark = compose.onNodeWithContentDescription("Bookmark page 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close menu").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(list.right <= bookmark.left); assertTrue(bookmark.right <= close.left)
        compose.onNodeWithText("Background").assertIsDisplayed()
        compose.onNodeWithText("Font size").assertIsDisplayed()
    }

    private fun open(server: ReaderServer, incognito: Boolean = false, restoration: StateRestorationTester? = null) {
        val store = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        val content: @androidx.compose.runtime.Composable () -> Unit = { MaterialTheme {
            ReaderScreen(store, AppSettingsStore(context), 1, 1, 1, 1, incognito = incognito, onBack = {})
        } }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        awaitLoaded()
    }
    private fun awaitLoaded() = compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
    private fun state() = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun menu() {
        compose.onRoot().performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitButton(description: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasContentDescription(description) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun findView(view: View): NovelPageView? = if (view is NovelPageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findView(view.getChildAt(it)) } else null
    private fun viewContext(items: List<PersonalToCDto>): NovelBookmarkContext {
        val done = CountDownLatch(1); var result: NovelBookmarkContext? = null
        compose.runOnIdle { findView(compose.activity.window.decorView)!!.bookmarkContext(items) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return requireNotNull(result)
    }
    private fun viewPosition(): NovelPosition {
        val done=CountDownLatch(1); var result: NovelPosition? = null
        compose.runOnIdle { findView(compose.activity.window.decorView)!!.position { result=it;done.countDown() } }
        assertTrue(done.await(5,TimeUnit.SECONDS));return requireNotNull(result)
    }
}
