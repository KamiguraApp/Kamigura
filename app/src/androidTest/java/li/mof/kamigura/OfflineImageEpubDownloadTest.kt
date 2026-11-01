package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.download.UnsupportedOfflineEpubMessage
import li.mof.kamigura.download.decodeOfflinePage
import li.mof.kamigura.download.localCoverFile
import li.mof.kamigura.download.offlineEpubImageEntries
import li.mof.kamigura.download.UnsupportedOfflineFormatException
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList

class OfflineImageEpubDownloadTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun doctypeMangaDownloadsAndReadsWithoutServerOrNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.cacheDir.resolve("reader_session_preferences.json")
        val originalPreferences = preferences.takeIf { it.exists() }?.readBytes()
        val visible = mutableStateOf(false)
        EpubDownloadServer(epubFixture(doctype = "<!DOCTYPE html>")).use { server ->
            val repository = OfflineIssueRepository(context)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, "Manga", "DOCTYPE fixture", expectedPageCount = 3)
                assertEquals(OfflineDownloadStatus.Ready, awaitPrepared(repository, session).status)
                val chapter = repository.localChapter(session, 1)!!
                assertEquals(listOf("OPS/cover.png", "OPS/z.png", "OPS/a.png"),
                    chapter.pages.map { (it as OfflinePage.ArchiveEntry).entryName })
                assertEquals(listOf(0, 1, 2), chapter.pages.map { it.index })
                assertEquals(1440, chapter.dimensions.getValue(0).width)
                assertEquals(2048, chapter.dimensions.getValue(2).height)
                assertNotNull(repository.observe(session, 1).first()!!.localCoverFile())
                assertEquals(1, server.requestPaths.size)
                server.close()
                shell("cmd connectivity airplane-mode enable")
                val sessions = KavitaSessionStore(context).apply { useTransient(session) }
                compose.setContent { MaterialTheme {
                    if (visible.value) ReaderScreen(sessions, AppSettingsStore(context), 1, 1, 1, 1,
                        incognito = true, onBack = { visible.value = false })
                } }
                compose.runOnIdle { visible.value = true }
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Page 0").fetchSemanticsNodes().isNotEmpty() }
                compose.onRoot().performTouchInput { click(Offset(width / 2f, height * .2f)) }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("LTR").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("LTR").performClick()
                compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
                compose.onNodeWithText("1 / 3", substring = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Close menu").performClick()
                awaitColor(red = true)
                compose.onRoot().performTouchInput { click(Offset(width / 2f, height * .2f)) }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
                compose.onNodeWithText("3 / 3", substring = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Close menu").performClick()
                awaitColor(red = false)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.getExternalFilesDir(null)!!.resolve("epub-doctype-offline-${screenshot.width}x${screenshot.height}.png")
                    .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                assertEquals(1, server.requestPaths.size)
            } finally {
                compose.runOnIdle { visible.value = false }; compose.waitForIdle()
                shell("cmd connectivity airplane-mode disable")
                clean(repository, session)
                if (originalPreferences == null) preferences.delete() else preferences.writeBytes(originalPreferences)
                ReaderSessionPreferenceCache.clearMemoryForTest()
            }
        }
    }

    @Test
    fun androidParserRejectsHostileDoctypesWithoutReadingExternalResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EpubDownloadServer(byteArrayOf()).use { canary ->
            val file = context.cacheDir.resolve("doctype-security-fixture.epub")
            try {
                for (prefix in listOf(
                    "<!DOCTYPE html []>", "<!DOCTYPE html [<!ENTITY unused 'text'>]>",
                    "<!DOCTYPE html SYSTEM '${canary.baseUrl}/external.dtd'>",
                    "<!DOCTYPE html PUBLIC 'public' '${canary.baseUrl}/external.dtd'>",
                    "<!DOCTYPE html [<!ENTITY % remote SYSTEM '${canary.baseUrl}/entity.dtd'>%remote;]>",
                    "<!-- <!DOCTYPE html> --><?fake data?><!DOCTYPE html [<!ENTITY external SYSTEM 'file:///nonexistent'>]>",
                    "<?fake data='<!DOCTYPE html>'?><!DOCTYPE html [<!ENTITY unused 'x'>]>",
                    "<!DOCTYPE html><!DOCTYPE html [<!ENTITY unused 'x'>]>"
                )) {
                    file.writeBytes(epubFixture(doctype = prefix))
                    ZipFile(file).use { assertUnsupported { offlineEpubImageEntries(it) } }
                }
                for (encoding in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                    val declaration = "\uFEFF<?xml version='1.0' encoding='UTF-16'?>"
                    file.writeBytes(epubFixture(doctype = "$declaration<!DOCTYPE html>", encoding = encoding))
                    ZipFile(file).use { assertEquals(3, offlineEpubImageEntries(it).size) }
                    file.writeBytes(epubFixture(doctype = "$declaration<!-- <!DOCTYPE html> --><!DOCTYPE html [<!ENTITY unused 'x'>]>", encoding = encoding))
                    ZipFile(file).use { assertUnsupported { offlineEpubImageEntries(it) } }
                }
                for (fixture in listOf(epubFixture(doctype = "<!DOCTYPE html>", novel = true),
                    epubFixture(doctype = "<!DOCTYPE html>", bodyScript = true))) {
                    file.writeBytes(fixture)
                    ZipFile(file).use { assertUnsupported { offlineEpubImageEntries(it) } }
                }
                assertTrue("No DTD/entity request is allowed", canary.requestPaths.isEmpty())
            } finally { file.delete() }
        }
    }

    private fun assertUnsupported(block: () -> Unit) {
        try { block(); fail("Expected unsupported EPUB") }
        catch (error: UnsupportedOfflineFormatException) { assertEquals(UnsupportedOfflineEpubMessage, error.message) }
    }

    private fun awaitColor(red: Boolean) = compose.waitUntil(10_000) {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val color = pixels[pixels.width / 2, pixels.height / 2]
        if (red) color.red > .95f && color.green < .05f && color.blue < .05f
        else color.blue > .95f && color.red < .05f && color.green < .05f
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }

    @Test
    fun ordinaryZipKeepsNaturalImageOrderAndComicInfoCover() = runBlocking {
        EpubDownloadServer(epubFixture(epub = false)).use { server ->
            val repository = OfflineIssueRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, "Archive", "Test issue", expectedPageCount = 3)
                assertEquals(OfflineDownloadStatus.Ready, awaitPrepared(repository, session).status)
                val chapter = repository.localChapter(session, 1)!!
                assertEquals(listOf("OPS/a.png", "OPS/cover.png", "OPS/z.png"),
                    chapter.pages.map { (it as OfflinePage.ArchiveEntry).entryName })
                assertEquals(1, chapter.coverPageIndex)
            } finally { clean(repository, session) }
        }
    }

    @Test
    fun downloadedMangaUsesSpineOrderCoverAndPageDimensions() = runBlocking {
        EpubDownloadServer(epubFixture()).use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OfflineIssueRepository(context)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, "Manga", "Test issue", expectedPageCount = 3)
                val record = awaitPrepared(repository, session)
                assertEquals(OfflineDownloadStatus.Ready, record.status)
                assertNotNull(record.localCoverFile())
                assertEquals(1, repository.observeDownloaded(session).first().size)
                val chapter = repository.localChapter(session, 1)!!
                assertEquals(listOf("OPS/cover.png", "OPS/z.png", "OPS/a.png"),
                    chapter.pages.map { (it as OfflinePage.ArchiveEntry).entryName })
                assertEquals(listOf(0, 1, 2), chapter.pages.map { it.index })
                assertEquals(0, chapter.coverPageIndex)
                chapter.pages.forEach { page ->
                    val bitmap = decodeOfflinePage(page, 100, 150)!!
                    assertEquals(100, bitmap.width)
                    assertEquals(150, bitmap.height)
                    bitmap.recycle()
                }
                assertEquals(100, chapter.dimensions.getValue(0).width)
                assertEquals(150, chapter.dimensions.getValue(2).height)
            } finally { clean(repository, session) }
        }
    }

    @Test
    fun unsupportedNovelCanRetryWithoutLosingPendingProgress() = runBlocking {
        EpubDownloadServer(epubFixture(novel = true)).use { server ->
            val repository = OfflineIssueRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, "Manga", "Test issue", expectedPageCount = 3)
                assertEquals(OfflineDownloadStatus.Unsupported, awaitPrepared(repository, session).status)
                assertEquals(UnsupportedOfflineEpubMessage, repository.observe(session, 1).first()!!.errorMessage)
                assertTrue(repository.observeDownloaded(session).first().isEmpty())
                repository.saveLocalProgress(session, 1, 1, markRead = true)
                server.body = epubFixture()
                repository.enqueue(session, 1, 1, 1, 1, "Manga", "Test issue", expectedPageCount = 3)
                val retried = awaitPrepared(repository, session)
                assertEquals(OfflineDownloadStatus.Ready, retried.status)
                assertEquals(1, retried.localPage)
                assertTrue(retried.progressPending)
                assertTrue(retried.markReadPending)
                assertFalse(retried.markUnreadPending)
                assertEquals(3, repository.localChapter(session, 1)!!.pages.size)
            } finally { clean(repository, session) }
        }
    }

    @Test
    fun undecodableImageRejectsWholeBookWithoutCompactingPages() = runBlocking {
        EpubDownloadServer(epubFixture(brokenImage = true)).use { server ->
            val repository = OfflineIssueRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, "Manga", "Test issue", expectedPageCount = 3)
                val record = awaitPrepared(repository, session)
                assertEquals(OfflineDownloadStatus.Unsupported, record.status)
                assertEquals(UnsupportedOfflineEpubMessage, record.errorMessage)
                assertTrue(repository.observeDownloaded(session).first().isEmpty())
                assertNull(repository.localChapter(session, 1))
            } finally { clean(repository, session) }
        }
    }

    private suspend fun awaitPrepared(repository: OfflineIssueRepository, session: KavitaSession): li.mof.kamigura.download.OfflineIssueRecord {
        return kotlinx.coroutines.withTimeout(30_000) {
            while (true) {
                val record = repository.reconcile(session, 1)!!
                if (record.status in setOf(OfflineDownloadStatus.Ready, OfflineDownloadStatus.Unsupported, OfflineDownloadStatus.Failed)) {
                    return@withTimeout record
                }
                kotlinx.coroutines.delay(100)
            }
            error("Unreachable")
        }
    }

    private suspend fun clean(repository: OfflineIssueRepository, session: KavitaSession) {
        repository.observe(session, 1).first()?.let {
            repository.markProgressSynced(session, 1, it.localPage, markedRead = true, markedUnread = true)
        }
        repository.remove(session, 1)
    }
}

private fun epubFixture(novel: Boolean = false, brokenImage: Boolean = false, epub: Boolean = true,
    doctype: String? = null, bodyScript: Boolean = false, encoding: Charset = Charsets.UTF_8): ByteArray {
    fun image(color: Int) = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(if (doctype != null) 1440 else 100, if (doctype != null) 2048 else 150, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        bitmap.setPixel(40, 50, Color.BLACK)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }.toByteArray()
    val white = image(Color.WHITE)
    return ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun text(name: String, value: String) = entry(name, value.toByteArray())
            if (epub) text("mimetype", "application/epub+zip")
            else text("ComicInfo.xml", "<ComicInfo><Pages><Page Image='1' Type='FrontCover'/></Pages></ComicInfo>")
            text("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("OPS/book.opf", "<package><manifest>" +
                (0..2).joinToString("") { "<item id='p$it' href='page$it.xhtml' media-type='application/xhtml+xml'/>" } +
                "</manifest><spine>" + (0..2).joinToString("") { "<itemref idref='p$it'/>" } + "</spine></package>")
            val pages = if (doctype != null) listOf("cover.png", "z.png", "a.png").mapIndexed { index, name ->
                if (novel && index == 1) "<p>Novel prose</p>" else
                    "<div><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 1440 2048'><image width='1440' height='2048' xlink:href='$name'/></svg></div>" +
                        if (bodyScript && index == 1) "<script src='../../js/kobo.js'/>" else ""
            } else listOf("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'><image xlink:href='cover.png'/></svg>",
                if (novel) "<p>Novel prose</p>" else "<img src='z.png'/>", "<img src='a.png'/>")
            pages.forEachIndexed { index, body ->
                entry("OPS/page$index.xhtml", "${doctype.orEmpty()}<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Metadata</title>${if (doctype != null) "<script src='../../js/kobo.js'/>" else ""}</head><body>$body</body></html>".toByteArray(encoding))
            }
            entry("OPS/cover.png", if (doctype != null) image(Color.RED) else white)
            entry("OPS/z.png", if (brokenImage) "broken".toByteArray() else if (doctype != null) image(Color.GREEN) else white)
            entry("OPS/a.png", if (doctype != null) image(Color.BLUE) else white)
        }
    }.toByteArray()
}

private class EpubDownloadServer(@Volatile var body: ByteArray) : AutoCloseable {
    val requestPaths = CopyOnWriteArrayList<String>()
    private val socket = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    init {
        workers.execute {
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
                workers.execute {
                    connection.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: return@use
                        requestPaths += request.split(' ')[1].substringBefore('?')
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val bytes = body
                        runCatching {
                            it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/epub+zip\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                        }
                    }
                }
            }
        }
    }
    override fun close() { socket.close(); workers.shutdownNow() }
}
