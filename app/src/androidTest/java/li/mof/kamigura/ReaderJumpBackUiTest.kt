package li.mof.kamigura

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ReaderJumpBackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity.applicationContext
    private val settings get() = AppSettingsStore(context)
    private val opening = mutableStateOf<Opening?>(null)
    private data class Opening(val session: KavitaSessionStore, val initialPage: Int?, val incognito: Boolean)

    @Test fun imageCurlAndSlideOnlyAdvanceOnTurnsAndToggleUntilTheNextTurn() = withReaderSettings {
        ReaderServer(1, spineCount = 12, initialImageProgress = 1, readingProfile = ltrProfile).use { server ->
            open(server)
            turnImage()
            menu()
            val furthest = imagePage()
            assertTrue(furthest > 2)
            closeMenu()
            turnImage(forward = false)
            menu()
            assertNoBack()
            val controls = bottomControls()
            val close = closeButton().fetchSemanticsNode().boundsInRoot
            jump(0)
            val returnButton = back(furthest).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val bookmark = compose.onNodeWithContentDescription("Bookmark page 1")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(returnButton.right <= bookmark.left)
            assertEquals(bookmark.top, returnButton.top)
            assertEquals(bookmark.bottom, returnButton.bottom)
            assertTrue(bookmark.right <= close.left)
            assertEquals(close, closeButton().fetchSemanticsNode().boundsInRoot)
            assertEquals(controls, bottomControls())
            val label = compose.onNodeWithText("p. $furthest").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().captureToImage().asAndroidBitmap()
            val header = Bitmap.createBitmap(root, 0, 0, root.width,
                (label.bottom + returnButton.height / 4).toInt())
            context.cacheDir.resolve("jump-back-header.png").outputStream().use {
                assertTrue(header.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            jump(1); back(furthest).assertIsDisplayed()
            jump(11); back(furthest).assertIsDisplayed()
            jump(0); back(furthest).performClick()
            assertEquals(furthest, imagePage())
            back(1).performClick()
            assertEquals(1, imagePage())
            back(furthest).performClick()
            closeMenu()
            runBlocking { settings.setPageTransition(PageTransition.Slide) }
            turnImage()
            menu()
            val next = imagePage()
            assertTrue(next > furthest)
            assertNoBack()
            jump(0); back(next).assertIsDisplayed()
        }
    }

    @Test fun imageHistorySurvivesReopenAndBookmarkEntryButFinishedChapterClearsIt() = withReaderSettings {
        val finished = AtomicBoolean(false)
        ReaderServer(1, spineCount = 12, initialImageProgress = 1, readingProfile = ltrProfile,
            chapterFinished = finished::get).use { server ->
            open(server)
            turnImage()
            menu()
            val furthest = imagePage()
            jump(0)
            back(furthest).assertIsDisplayed()
            closeBook()
            compose.waitUntil(5_000) { server.progressWrites.lastOrNull() == (1 to 0) }
            reloadHistory()
            open(server)
            menu(); back(furthest).assertIsDisplayed()
            closeBook()
            open(server, initialPage = 10)
            menu(); back(furthest).assertIsDisplayed()
            jump(1); back(furthest).assertIsDisplayed()
            closeBook()
            finished.set(true)
            open(server, initialPage = 0)
            menu(); assertNoBack()
            jump(1); jump(0); assertNoBack()
        }
    }

    @Test fun verticalScrollAdvancesTheMarkButSliderAndReturnDoNot() = withReaderSettings {
        ReaderServer(1, spineCount = 12, initialImageProgress = 1, readingProfile = ltrProfile).use { server ->
            open(server, incognito = true)
            menu()
            compose.onNodeWithText("Vertical").performClick()
            closeMenu()
            repeat(3) { key(KeyEvent.KEYCODE_PAGE_DOWN) }
            menu()
            val furthest = imagePage()
            assertTrue(furthest > 2)
            assertNoBack()
            jump(0); back(furthest).assertIsDisplayed()
            jump(9); back(furthest).assertIsDisplayed()
            jump(0); back(furthest).performClick()
            assertEquals(furthest, imagePage())
            back(1).assertIsDisplayed()
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun novelContentsBookmarksAndSliderReturnToTheExactScreen() = withReaderSettings {
        val contents = Json.encodeToString(listOf(BookChapterItemDto("Character list", page = 0)))
        val bookmarks = Json.encodeToString(listOf(PersonalToCDto(1, 1, 1, "Saved paragraph", "//body/p[1]")))
        ReaderServer(3, bookProgress = ProgressDto(1, 1, 1, 1, 2), bookFragment = { novelText() },
            readingProfile = ltrProfile,
            contentsResponse = { 200 to contents }, personalHandler = { _, _, _ -> 200 to bookmarks }).use { server ->
            open(server, novel = true)
            val start = novelState()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState() != start }
            val furthest = novelState()
            assertTrue(furthest.contains("Page 3 /"))
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.waitUntil(5_000) { novelState() == start }
            menu(); assertNoBack()
            openContentsItem("Open contents: Character list")
            menu(); back(3).assertIsDisplayed()
            jump(5, novel = true); back(3).assertIsDisplayed()
            val slider = pageSlider(novel = true).fetchSemanticsNode().boundsInRoot
            val close = closeButton().fetchSemanticsNode().boundsInRoot
            jump(0, novel = true)
            val returnButton = back(3).assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val contentsButton = compose.onNodeWithContentDescription("Contents").fetchSemanticsNode().boundsInRoot
            assertTrue(contentsButton.right <= returnButton.left)
            assertTrue(returnButton.right <= close.left)
            assertTrue(returnButton.center.y in close.top..close.bottom)
            assertEquals(slider, pageSlider(novel = true).fetchSemanticsNode().boundsInRoot)
            assertEquals(close, closeButton().fetchSemanticsNode().boundsInRoot)
            back(3).performClick()
            compose.waitUntil(10_000) { novelState() == furthest }
            back(1).assertIsDisplayed()
            openContentsItem("Open bookmark: Saved paragraph", bookmarks = true)
            menu(); back(3).performClick()
            compose.waitUntil(10_000) { novelState() == furthest }
            back(2).performClick()
            compose.waitUntil(10_000) { novelState().contains("Page 2 /") }
            back(3).assertIsDisplayed()
            closeMenu()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState().contains("Screen 2 /") }
            menu(); assertNoBack()
        }
    }

    @Test fun imageSliderAndContentsCanPeekAheadAndTurningCommitsTheNewPosition() = withReaderSettings {
        val contents = Json.encodeToString(listOf(BookChapterItemDto("Cover"), BookChapterItemDto("Ahead", page = 9)))
        ReaderServer(3, spineCount = 12, initialImageProgress = 1, readingProfile = ltrProfile,
            bookFragment = { "<img src='/api/Book/1/book-resources?file=$it.png'>" },
            contentsResponse = { 200 to contents }).use { server ->
            open(server, incognito = true)
            turnImage()
            menu()
            val furthest = imagePage()
            assertNoBack()
            jump(7)
            back(furthest).performClick()
            assertEquals(furthest, imagePage())
            back(8).assertIsDisplayed()
            closeMenu()
            menu()
            openContentsItem("Open contents: Ahead", novel = false)
            menu()
            assertEquals(10, imagePage())
            back(furthest).performClick()
            assertEquals(furthest, imagePage())
            // Turn from a second slider lookup, then from a second Contents lookup.
            jump(7)
            closeMenu()
            turnImage(forward = false)
            menu()
            assertNoBack()
            jump(0)
            back(8).assertIsDisplayed()
            openContentsItem("Open contents: Ahead", novel = false)
            turnImage(forward = false)
            menu()
            assertNoBack()
            jump(0)
            back(10).assertIsDisplayed()
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test fun novelSliderAndContentsCanPeekAheadAndTurningCommitsTheNewPosition() = withReaderSettings {
        val contents = Json.encodeToString(listOf(BookChapterItemDto("Ahead", page = 5)))
        ReaderServer(3, spineCount = 12, bookProgress = ProgressDto(1, 1, 1, 1, 2),
            bookFragment = { novelText() }, readingProfile = ltrProfile,
            contentsResponse = { 200 to contents }).use { server ->
            open(server, novel = true, incognito = true)
            val start = novelState()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState() != start }
            val furthest = novelState()
            menu(); assertNoBack()
            jump(5, novel = true)
            back(3).performClick()
            compose.waitUntil(10_000) { novelState() == furthest }
            back(6).assertIsDisplayed()
            jump(4, novel = true)
            closeMenu()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState().contains("Page 5 /") && novelState().contains("Screen 2 /") }
            menu(); assertNoBack()
            jump(2, novel = true)
            back(5).performClick()
            compose.waitUntil(10_000) { novelState().contains("Page 5 /") && novelState().contains("Screen 2 /") }
            openContentsItem("Open contents: Ahead")
            menu()
            back(5).performClick()
            compose.waitUntil(10_000) { novelState().contains("Page 5 /") && novelState().contains("Screen 2 /") }
            openContentsItem("Open contents: Ahead")
            val ahead = novelState()
            assertTrue(ahead.contains("Page 6 /"))
            key(KeyEvent.KEYCODE_PAGE_UP)
            compose.waitUntil(10_000) { novelState() != ahead }
            menu(); assertNoBack()
            jump(0, novel = true)
            back(6).performClick()
            compose.waitUntil(10_000) { novelState() == ahead }
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    @Test fun novelHistorySurvivesReopenUntilTheBookIsFinished() = withReaderSettings {
        val finished = AtomicBoolean(false)
        ReaderServer(3, bookProgress = ProgressDto(1, 1, 1, 1, 2), bookFragment = { novelText() },
            readingProfile = ltrProfile,
            chapterFinished = finished::get).use { server ->
            open(server, novel = true)
            val start = novelState()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState() != start }
            val furthest = novelState()
            menu(); jump(0, novel = true); back(3).assertIsDisplayed()
            closeBook()
            compose.waitUntil(5_000) { server.bookProgressWrites.lastOrNull()?.pageNum == 0 }
            reloadHistory()
            open(server, novel = true)
            menu(); back(3).performClick()
            compose.waitUntil(10_000) { novelState() == furthest }
            closeBook()
            finished.set(true)
            open(server, novel = true)
            menu(); jump(0, novel = true); assertNoBack()
        }
    }

    @Test fun novelSameSpineContentsJumpReturnsWithoutReloadingTheDocument() = withReaderSettings {
        val contents = Json.encodeToString(listOf(BookChapterItemDto("Spine start", page = 2)))
        ReaderServer(3, bookProgress = ProgressDto(1, 1, 1, 1, 2), bookFragment = { novelText() },
            readingProfile = ltrProfile, contentsResponse = { 200 to contents }).use { server ->
            open(server, novel = true)
            val start = novelState()
            key(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitUntil(5_000) { novelState() != start }
            val furthest = novelState()
            menu(); openContentsItem("Open contents: Spine start")
            assertEquals(start, novelState())
            val requests = server.requests.count { it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "2" }
            menu(); back(3).performClick()
            compose.waitUntil(5_000) { novelState() == furthest }
            back(3).performClick()
            compose.waitUntil(5_000) { novelState() == start }
            assertEquals(requests, server.requests.count { it.path == "/api/Book/1/book-page" && it.getQueryParameter("page") == "2" })
        }
    }

    private fun withReaderSettings(block: () -> Unit) {
        val original = runBlocking { settings.flow.first().reader }
        val file = context.cacheDir.resolve("reader_session_preferences.json")
        val saved = file.takeIf { it.exists() }?.readBytes()
        try {
            runBlocking {
                settings.setPageTransition(PageTransition.Curl)
                settings.setPrefetchTurns(0)
                settings.setInvertMode(InvertMode.Off)
            }
            compose.setContent { MaterialTheme {
                opening.value?.let { ReaderScreen(it.session, settings, 1, 1, 1, 1,
                    incognito = it.incognito, initialPage = it.initialPage, onBack = { opening.value = null }) }
            } }
            block()
        } finally {
            compose.runOnIdle { opening.value = null }
            runBlocking {
                settings.setPageTransition(original.image.pageTransition)
                settings.setPrefetchTurns(original.image.prefetchTurns)
                settings.setInvertMode(original.image.invertMode)
                ReaderSessionPreferenceCache.persist(context.cacheDir)
            }
            if (saved == null) file.delete() else file.writeBytes(saved)
            ReaderSessionPreferenceCache.clearMemoryForTest()
        }
    }

    private fun open(server: ReaderServer, novel: Boolean = false, initialPage: Int? = null, incognito: Boolean = false) {
        val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
        compose.runOnIdle { opening.value = Opening(session, initialPage, incognito) }
        if (novel) compose.waitUntil(20_000) { novelState().isNotEmpty() }
        else compose.waitUntil(15_000) {
            compose.onAllNodes(hasContentDescription("Page ", substring = true) or
                hasContentDescription("Left ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun reloadHistory() {
        runBlocking { ReaderSessionPreferenceCache.persist(context.cacheDir) }
        ReaderSessionPreferenceCache.clearMemoryForTest()
    }

    private fun menu() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * .2f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeMenu() {
        compose.onNodeWithContentDescription("Close menu").performClick()
        compose.waitForIdle()
    }
    private fun closeBook() {
        compose.onNodeWithContentDescription("Back to series").performClick()
        compose.waitUntil(10_000) { opening.value == null }
    }
    private fun turnImage(forward: Boolean = true) {
        compose.onRoot().performTouchInput { click(Offset(width * if (forward) .9f else .1f, height / 2f)) }
        compose.mainClock.advanceTimeBy(1_200)
    }
    private fun key(code: Int) {
        compose.runOnIdle {
            val callback = compose.activity.window.callback
            callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.mainClock.advanceTimeBy(1_200)
    }
    private fun imagePage(): Int = compose.onNodeWithText(" / 12", substring = true)
        .fetchSemanticsNode().config[SemanticsProperties.Text].first().text.substringBefore(" / 12")
        .substringAfterLast(' ').toInt()
    private fun novelState() = compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes()
        .firstOrNull()?.config?.get(SemanticsProperties.StateDescription).orEmpty()
    private fun back(page: Int) = compose.onNodeWithContentDescription("Back to p. $page")
    private fun assertNoBack() = compose.onNodeWithContentDescription("Back to p.", substring = true).assertDoesNotExist()
    private fun closeButton() = compose.onNodeWithContentDescription("Close menu")
    private fun bottomControls() = pageSlider().fetchSemanticsNode().boundsInRoot to
        compose.onAllNodesWithText("Spread shift").fetchSemanticsNodes().singleOrNull()?.boundsInRoot

    private fun pageSlider(novel: Boolean = false): SemanticsNodeInteraction {
        val setProgress = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
        return compose.onNode(if (novel) setProgress and
            hasAnyAncestor(hasContentDescription("EPUB page navigation")) else setProgress)
    }

    private fun jump(page: Int, novel: Boolean = false) {
        pageSlider(novel).performSemanticsAction(SemanticsActions.SetProgress) { it(page.toFloat()) }
        if (novel) compose.waitUntil(10_000) { novelState().contains("Page ${page + 1} /") }
        else compose.waitUntil(5_000) { imagePage() == page + 1 }
    }
    private fun openContentsItem(label: String, bookmarks: Boolean = false, novel: Boolean = true) {
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Contents").isDisplayed() }
        compose.onNodeWithContentDescription("Contents").performClick()
        if (bookmarks) compose.onNode(hasText("Bookmarks") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isEmpty() &&
                (!novel || novelState().isNotEmpty())
        }
    }
    private fun novelText() = "<div>" + (1..180).joinToString("") {
        "<p>Paragraph $it. The reader returns to this exact screen after visiting the character list.</p>"
    } + "</div>"

    private val ltrProfile = """{"kind":1,"readingDirection":0,"bookReaderWritingStyle":0,"bookReaderReadingDirection":0}"""
}
