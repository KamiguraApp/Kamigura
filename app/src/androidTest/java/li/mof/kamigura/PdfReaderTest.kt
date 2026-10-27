package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.OfflinePage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okio.buffer
import okio.source

@RunWith(AndroidJUnit4::class)
class PdfReaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rotationRestoresUnsyncedPage() = checkRestoration(initialPage = null, incognito = false)

    @Test
    fun rotationDoesNotReapplyBookmarkPage() = checkRestoration(initialPage = 1, incognito = false)

    @Test
    fun rotationKeepsIncognitoAndUnsyncedPage() = checkRestoration(initialPage = null, incognito = true)

    private fun checkRestoration(initialPage: Int?, incognito: Boolean) {
        ReaderServer(format = 1).use { server ->
            val restoration = StateRestorationTester(compose)
            val visible = open(server, incognito, initialPage, restoration)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            jump(4)
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("5 / 6", substring = true).assertIsDisplayed()
            assertTrue(server.progressWrites.isEmpty())
            compose.mainClock.autoAdvance = true
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            awaitRenderedPage()
            openMenu()
            compose.onNodeWithText("5 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { !visible.value }
            if (incognito) assertTrue(server.progressWrites.isEmpty())
            else compose.waitUntil(5_000) { server.progressWrites.contains(1 to 4) }
        }
    }

    @Test
    fun rotationKeepsChapterReachedInsideReader() {
        ReaderServer(format = 1, neighbor = true).use { server ->
            val restoration = StateRestorationTester(compose)
            open(server, incognito = true, restoration = restoration)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            jump(5)
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Next chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Next chapter").performClick()
            compose.waitUntil(5_000) {
                server.requests.any { it.path == "/api/Reader/image" && it.getQueryParameter("chapterId") == "2" }
            }
            awaitRenderedPage()
            openMenu()
            jump(3)
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) {
                server.requests.count { it.path == "/api/Reader/chapter-info" && it.getQueryParameter("chapterId") == "2" } >= 2
            }
            awaitRenderedPage()
            openMenu()
            compose.onNodeWithText("Second | 4 / 6", substring = true).assertIsDisplayed()
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test
    fun quickCloseSavesReachedPageWithoutWaitingForDebounce() {
        ReaderServer(format = 1).use { server ->
            val visible = open(server, incognito = false)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            jump(2)
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("3 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(5_000) { !visible.value && server.progressWrites.contains(1 to 2) }
            assertEquals(listOf(1 to 2), server.progressWrites.toList())
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { visible.value = true }
            awaitRenderedPage()
            openMenu()
            compose.onNodeWithText("3 / 6", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun untouchedOpeningAndCloseDoNotWriteProgress() {
        ReaderServer(format = 1).use { server ->
            val visible = open(server, incognito = false)
            awaitPages(server)
            compose.mainClock.advanceTimeBy(4_000)
            openMenu()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { !visible.value }
            assertTrue(server.progressWrites.isEmpty())
        }
    }

    @Test
    fun untouchedBookmarkOpeningAndCloseDoNotWriteProgress() {
        ReaderServer(format = 1).use { server ->
            val visible = open(server, incognito = false, initialPage = 1)
            awaitPages(server)
            compose.mainClock.advanceTimeBy(4_000)
            openMenu()
            compose.onNodeWithText("2 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) { !visible.value }
            assertTrue(server.progressWrites.isEmpty())
            assertFalse(server.requests.any { it.path == "/api/Reader/get-progress" })
        }
    }

    private fun jump(page: Int) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(page.toFloat()) }
    }

    @Test
    fun readingStillDebouncesAndSkipsTransientPages() {
        ReaderServer(format = 1).use { server ->
            open(server, incognito = false)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            jump(2)
            compose.mainClock.advanceTimeBy(2_000)
            assertTrue(server.progressWrites.isEmpty())
            jump(3)
            compose.mainClock.advanceTimeBy(2_000)
            assertTrue(server.progressWrites.isEmpty())
            compose.mainClock.advanceTimeBy(1_200)
            compose.waitUntil(5_000) { server.progressWrites.contains(1 to 3) }
            assertEquals(listOf(1 to 3), server.progressWrites.toList())
        }
    }

    @Test
    fun systemBackSavesReachedPageImmediately() {
        ReaderServer(format = 1).use { server ->
            val visible = open(server, incognito = false)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            jump(2)
            compose.mainClock.advanceTimeBy(100)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.mainClock.advanceTimeBy(100)
            compose.waitUntil(5_000) { !visible.value && server.progressWrites.contains(1 to 2) }
        }
    }

    @Test
    fun closeSwipeSavesReachedPageImmediately() {
        ReaderServer(format = 1).use { server ->
            val visible = open(server, incognito = false)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            jump(2)
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.mainClock.advanceTimeBy(100)
            compose.onRoot().performTouchInput {
                swipe(Offset(width / 2f, height * 0.3f), Offset(width / 2f, height * 0.85f), 500)
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(5_000) { !visible.value && server.progressWrites.contains(1 to 2) }
        }
    }

    @Test
    fun firstPdfOpeningSurvivesTwentySecondsThenReadsAndJumps() {
        ReaderServer(format = 4, holdChapter = true).use { server ->
            open(server)
            assertTrue(server.chapterRequested.await(10, TimeUnit.SECONDS))
            compose.onNodeWithText("Preparing PDF...").assertIsDisplayed()
            // Exceed both the production API call timeout and OkHttp's default read timeout.
            assertFalse(server.releaseChapter.await(21, TimeUnit.SECONDS))
            compose.onNodeWithText("Preparing PDF...").assertIsDisplayed()
            assertFalse(server.requests.any { it.path == "/api/Reader/image" })
            server.releaseChapter.countDown()
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("Test book").assertIsDisplayed()
            compose.onNodeWithText("1 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithText("LTR").performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
            compose.onNodeWithText("4 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithText("RTL").performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
            compose.onNodeWithText("2 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.waitUntil(5_000) { server.requests.any { it.path == "/api/Reader/image" } }
            val images = server.requests.filter { it.path == "/api/Reader/image" }
            assertTrue(images.all { it.getQueryParameter("extractPdf") == "true" })
            assertTrue(server.requests.filter { it.path == "/api/Reader/chapter-info" }
                .all { it.getQueryParameter("extractPdf") == "true" })
        }
    }

    @Test
    fun textEpubOpensNovelReaderWithoutRequestingReaderImages() {
        ReaderServer(format = 3).use { server ->
            val visible = open(server)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty()
            }
            openMenu()
            compose.onNodeWithText("Page 1 / 6 • Screen", substring = true).assertIsDisplayed()
            assertFalse(server.requests.any { it.path == "/api/Reader/image" })
            assertTrue(server.requests.any { it.path == "/api/Book/1/book-page" })
            assertTrue(server.requests.filter { it.path == "/api/Reader/chapter-info" }
                .all { it.getQueryParameter("extractPdf") == "false" })
            compose.onNodeWithContentDescription("Back to series").performClick()
            compose.runOnIdle { assertFalse(visible.value) }
        }
    }

    @Test
    fun archiveStillReadsWithoutChangingImageUrls() {
        ReaderServer(format = 1).use { server ->
            open(server)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("1 / 6", substring = true).assertIsDisplayed()
            assertTrue(server.requests.filter { it.path == "/api/Reader/image" }
                .all { it.getQueryParameter("extractPdf") == null })
        }
    }

    @Test
    fun pdfForwardBoundaryMarksReadAndExits() = checkBoundary(forward = true)

    @Test
    fun pdfBackwardBoundaryMarksUnreadAndExits() = checkBoundary(forward = false)

    @Test
    fun pdfRtlForwardBoundaryMarksReadAndExits() = checkBoundary(forward = true, rtl = true)

    @Test
    fun pdfRtlBackwardBoundaryMarksUnreadAndExits() = checkBoundary(forward = false, rtl = true)

    private fun checkBoundary(forward: Boolean, rtl: Boolean = false) {
        ReaderServer(format = 4).use { server ->
            val visible = open(server, incognito = false)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText(if (rtl) "RTL" else "LTR").performClick()
            if (forward) {
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(if (rtl) 0f else 5f) }
            }
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput {
                click(Offset(width * (if (forward != rtl) 0.9f else 0.1f), height / 2f))
            }
            compose.waitUntil(5_000) { !visible.value }
            val endpoint = if (forward) "mark-chapter-read" else "mark-multiple-unread"
            compose.waitUntil(5_000) { server.requests.any { it.path == "/api/Reader/$endpoint" } }
        }
    }

    @Test
    fun pdfNextAndPreviousChaptersKeepExtractionAndLandingPages() {
        ReaderServer(format = 4, neighbor = true).use { server ->
            open(server)
            awaitPages(server)
            openMenu()
            compose.onNodeWithText("LTR").performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Next chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Next chapter").performClick()
            compose.waitUntil(5_000) {
                server.requests.any { it.path == "/api/Reader/image" && it.getQueryParameter("chapterId") == "2" }
            }
            awaitRenderedPage()
            openMenu()
            compose.onNodeWithText("1 / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Previous chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Previous chapter").performClick()
            compose.waitUntil(5_000) {
                server.requests.count { it.path == "/api/Reader/chapter-info" } == 3
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Previous chapter").fetchSemanticsNodes().isEmpty() }
            openMenu()
            compose.onNodeWithText("6 / 6", substring = true).assertIsDisplayed()
            assertTrue(server.requests.filter { it.path == "/api/Reader/chapter-info" || it.path == "/api/Reader/image" }
                .all { it.getQueryParameter("extractPdf") == "true" })
        }
    }

    @Test
    fun pdfCloseSwipeExitsWithoutWritingIncognitoProgress() {
        ReaderServer(format = 4).use { server ->
            val visible = open(server)
            awaitPages(server)
            compose.onRoot().performTouchInput {
                swipe(Offset(width / 2f, height * 0.3f), Offset(width / 2f, height * 0.85f), 500)
            }
            compose.waitUntil(5_000) { !visible.value }
            assertFalse(server.requests.any { it.path == "/api/Reader/progress" })
        }
    }

    @Test
    fun downloadedPdfUsesDeviceRenderingWithoutExtractionRequests() {
        ReaderServer(format = 4).use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OfflineIssueRepository(context)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                runBlocking {
                    repository.enqueue(session, 1, 1, 1, 1, "Test book", "Offline PDF", expectedPageCount = 6)
                }
                compose.waitUntil(20_000) {
                    runBlocking { repository.reconcile(session, 1)?.status == OfflineDownloadStatus.Ready }
                }
                val local = runBlocking { repository.localChapter(session, 1) }!!
                assertEquals(6, local.pages.size)
                assertTrue(local.pages.all { it is OfflinePage.PdfPage })
                open(server)
                awaitRenderedPage()
                openMenu()
                compose.onNodeWithText("1 / 6", substring = true).assertIsDisplayed()
                assertFalse(server.requests.any { it.path == "/api/Reader/chapter-info" || it.path == "/api/Reader/image" })
            } finally {
                runBlocking { repository.remove(session, 1) }
            }
        }
    }

    private fun open(
        server: ReaderServer,
        incognito: Boolean = true,
        initialPage: Int? = null,
        restoration: StateRestorationTester? = null
    ): androidx.compose.runtime.MutableState<Boolean> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionStore = KavitaSessionStore(context).apply {
            useTransient(KavitaSession(baseUrl = server.baseUrl))
        }
        val visible = mutableStateOf(true)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            MaterialTheme {
                if (visible.value) {
                    ReaderScreen(sessionStore, AppSettingsStore(context), 1, 1, 1, 1,
                        incognito = incognito, initialPage = initialPage, onBack = { visible.value = false })
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        return visible
    }

    private fun awaitPages(server: ReaderServer) {
        compose.waitUntil(10_000) { server.requests.any { it.path == "/api/Reader/image" } }
        compose.onNodeWithText("Preparing PDF...").assertDoesNotExist()
        awaitRenderedPage()
    }

    private fun awaitRenderedPage() {
        compose.waitUntil(10_000) {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            var white = 0
            for (y in 1..20) for (x in 1..20) {
                val color = pixels[pixels.width * x / 21, pixels.height * y / 21]
                if (color.red > 0.995f && color.green > 0.995f && color.blue > 0.995f) white++
            }
            white > 60
        }
    }

    private fun openMenu() {
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitUntil(3_000) {
            compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/** A local Kavita response fixture; no saved server credentials or library content are used. */
internal class ReaderServer(
    format: Int,
    holdChapter: Boolean = false,
    neighbor: Boolean = false,
    cacheImages: Boolean = false,
    failPage: (Int) -> Boolean = { false },
    imageResponseGate: (Int) -> CountDownLatch? = { null },
    bookFragment: (Int) -> String = { "<div><p>Novel prose requiring a text reader.</p></div>" },
    bookProgress: ProgressDto? = null,
    readingProfile: String = """{"kind":1,"readingDirection":1}""",
    spineCount: Int = 6,
    bookChapterFragment: ((Int, Int) -> String)? = null,
    personalHandler: ((String, Uri, String) -> Pair<Int, String>)? = null,
    contentsResponse: () -> Pair<Int, String> = { 200 to "[]" },
    bookPageResponse: ((Int, Int) -> Pair<Int, String>)? = null,
    bookFont: ByteArray? = null
) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Uri>()
    val progressWrites = CopyOnWriteArrayList<Pair<Int, Int>>()
    val bookProgressWrites = CopyOnWriteArrayList<ProgressDto>()
    val chapterRequested = CountDownLatch(1)
    val releaseChapter = CountDownLatch(if (holdChapter) 1 else 0)
    private val image = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val pen = Paint().apply { color = Color.BLACK; strokeWidth = 5f; textSize = 60f }
        canvas.drawText("Reader test", 50f, 120f, pen)
        for (y in 200..800 step 100) canvas.drawLine(50f, y.toFloat(), 550f, y.toFloat(), pen)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }.toByteArray()
    private val pdf = ByteArrayOutputStream().also { output ->
        val document = PdfDocument()
        try {
            for (number in 1..6) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, number).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Offline PDF $number", 50f, 120f,
                    Paint().apply { color = Color.BLACK; textSize = 60f })
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }.toByteArray()

    init {
        workers.execute {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
                workers.execute {
                    connection.use {
                        val input = it.getInputStream().source().buffer()
                        val request = input.readUtf8Line() ?: return@use
                        val uri = Uri.parse(request.split(' ')[1])
                        var length = 0
                        while (true) {
                            val header = input.readUtf8Line() ?: return@use
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val requestBody = input.readUtf8(length.toLong()).toCharArray()
                        if (uri.path == "/api/Reader/progress" && length > 0) {
                            val progress = Json.parseToJsonElement(String(requestBody)).jsonObject
                            progressWrites += progress.getValue("chapterId").jsonPrimitive.int to
                                progress.getValue("pageNum").jsonPrimitive.int
                            bookProgressWrites += Json.decodeFromString<ProgressDto>(String(requestBody))
                        }
                        requests += uri
                        val personal = if (uri.path in listOf("/api/reader/ptoc", "/api/reader/create-ptoc"))
                            personalHandler?.invoke(request.substringBefore(' '), uri, String(requestBody)) else null
                        val contents = if (uri.path in listOf("/api/Book/1/chapters", "/api/Book/2/chapters")) contentsResponse() else null
                        val book = if (uri.path in listOf("/api/Book/1/book-page", "/api/Book/2/book-page"))
                            bookPageResponse?.invoke(uri.pathSegments[2].toInt(), uri.getQueryParameter("page")?.toInt() ?: 0) else null
                        val custom = personal ?: contents ?: book
                        val json = custom?.second ?: when (uri.path) {
                            "/api/Series/1" -> """{"id":1,"name":"Test book","format":$format}"""
                            "/api/Series/volumes" -> if (neighbor)
                                """[{"id":1,"chapters":[{"id":1,"title":"First"},{"id":2,"title":"Second"}]}]""" else "[]"
                            "/api/reading-profile/1/1" -> readingProfile
                            "/api/Reader/get-progress" -> {
                                val bookSaved = bookProgressWrites.lastOrNull() ?: bookProgress
                                val chapter = uri.getQueryParameter("chapterId")?.toInt() ?: 1
                                val page = progressWrites.lastOrNull { it.first == chapter }?.second ?: 0
                                if (bookSaved != null) Json.encodeToString(bookSaved) else
                                    """{"libraryId":1,"seriesId":1,"volumeId":1,"chapterId":$chapter,"pageNum":$page}"""
                            }
                            "/api/Reader/chapter-bookmarks" -> "[]"
                            "/api/reader/ptoc" -> "[]"
                            "/api/Book/1/book-page", "/api/Book/2/book-page" -> {
                                val spine = uri.getQueryParameter("page")?.toInt() ?: 0
                                bookChapterFragment?.invoke(uri.pathSegments[2].toInt(), spine) ?: bookFragment(spine)
                            }
                            "/api/Reader/chapter-info" -> {
                                chapterRequested.countDown()
                                releaseChapter.await(45, TimeUnit.SECONDS)
                                val dimensions = (0..5).joinToString(",") {
                                    """{"pageNumber":$it,"width":600,"height":900}"""
                                }
                                """{"chapterId":${uri.getQueryParameter("chapterId") ?: 1},"pages":$spineCount,"pageDimensions":[$dimensions]}"""
                            }
                            else -> "{}"
                        }
                        val isImage = uri.path == "/api/Reader/image" || uri.path.orEmpty().endsWith("/book-resources")
                        val isDownload = uri.path == "/api/Download/chapter"
                        if (isImage) imageResponseGate(uri.getQueryParameter("page")?.toInt() ?: 0)
                            ?.await(30, TimeUnit.SECONDS)
                        val invalidImage = isImage && failPage(uri.getQueryParameter("page")?.toInt() ?: 0)
                        val isBookFont = bookFont != null && uri.path.orEmpty().endsWith("/book-resources") &&
                            uri.getQueryParameter("font") == "embedded"
                        val body = if (isBookFont) bookFont!! else if (invalidImage) "invalid cached image".toByteArray()
                            else if (isImage) image else if (isDownload) pdf else json.toByteArray()
                        val type = if (isBookFont) "font/ttf" else if (isImage) "image/png" else if (isDownload) "application/pdf"
                            else if (uri.path == "/api/Book/1/book-page") "text/html" else "application/json"
                        val cacheHeader = if (isImage && cacheImages) "Cache-Control: max-age=3600\r\n" else ""
                        runCatching {
                            it.getOutputStream().write(("HTTP/1.1 ${custom?.first ?: 200} Response\r\nContent-Type: $type\r\n" +
                                cacheHeader + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        releaseChapter.countDown()
        socket.close()
        workers.shutdownNow()
    }
}
