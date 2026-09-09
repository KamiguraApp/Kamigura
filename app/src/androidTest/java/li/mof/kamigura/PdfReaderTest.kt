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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.OfflinePage
import kotlinx.coroutines.runBlocking
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

@RunWith(AndroidJUnit4::class)
class PdfReaderTest {
    @get:Rule val compose = createComposeRule()

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
    fun epubShowsUnsupportedMessageWithoutRequestingPages() {
        ReaderServer(format = 3).use { server ->
            val visible = open(server)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("EPUB is not supported in Kamigura.").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("EPUB is not supported in Kamigura.").assertIsDisplayed()
            assertFalse(server.requests.any { it.path == "/api/Reader/chapter-info" || it.path == "/api/Reader/image" })
            compose.onNodeWithText("Back to series").performClick()
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

    private fun open(server: ReaderServer, incognito: Boolean = true): androidx.compose.runtime.MutableState<Boolean> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionStore = KavitaSessionStore(context).apply {
            useTransient(KavitaSession(baseUrl = server.baseUrl))
        }
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    ReaderScreen(sessionStore, AppSettingsStore(context), 1, 1, 1, 1,
                        incognito = incognito, onBack = { visible.value = false })
                }
            }
        }
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
private class ReaderServer(format: Int, holdChapter: Boolean = false, neighbor: Boolean = false) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Uri>()
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
                        val input = it.getInputStream().bufferedReader()
                        val request = input.readLine() ?: return@use
                        val uri = Uri.parse(request.split(' ')[1])
                        var length = 0
                        while (true) {
                            val header = input.readLine() ?: return@use
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        requests += uri
                        val json = when (uri.path) {
                            "/api/Series/1" -> """{"id":1,"name":"Test book","format":$format}"""
                            "/api/Series/volumes" -> if (neighbor)
                                """[{"id":1,"chapters":[{"id":1,"title":"First"},{"id":2,"title":"Second"}]}]""" else "[]"
                            "/api/reading-profile/1/1" -> """{"kind":1,"readingDirection":1}"""
                            "/api/Reader/get-progress" -> """{"libraryId":1,"seriesId":1,"volumeId":1,"chapterId":1,"pageNum":0}"""
                            "/api/Reader/chapter-info" -> {
                                chapterRequested.countDown()
                                releaseChapter.await(45, TimeUnit.SECONDS)
                                val dimensions = (0..5).joinToString(",") {
                                    """{"pageNumber":$it,"width":600,"height":900}"""
                                }
                                """{"chapterId":1,"pages":6,"pageDimensions":[$dimensions]}"""
                            }
                            else -> "{}"
                        }
                        val isImage = uri.path == "/api/Reader/image"
                        val isDownload = uri.path == "/api/Download/chapter"
                        val body = if (isImage) image else if (isDownload) pdf else json.toByteArray()
                        val type = if (isImage) "image/png" else if (isDownload) "application/pdf" else "application/json"
                        runCatching {
                            it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
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
