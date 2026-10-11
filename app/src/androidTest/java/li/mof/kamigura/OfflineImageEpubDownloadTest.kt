package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.download.UnsupportedOfflineEpubMessage
import li.mof.kamigura.download.decodeOfflinePage
import li.mof.kamigura.download.localCoverFile
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineImageEpubDownloadTest {
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

private fun epubFixture(novel: Boolean = false, brokenImage: Boolean = false, epub: Boolean = true): ByteArray {
    val image = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        bitmap.setPixel(40, 50, Color.BLACK)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }.toByteArray()
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
            val pages = listOf("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'><image xlink:href='cover.png'/></svg>",
                if (novel) "<p>Novel prose</p>" else "<img src='z.png'/>", "<img src='a.png'/>")
            pages.forEachIndexed { index, body ->
                text("OPS/page$index.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Metadata</title></head><body>$body</body></html>")
            }
            entry("OPS/cover.png", image)
            entry("OPS/z.png", if (brokenImage) "broken".toByteArray() else image)
            entry("OPS/a.png", image)
        }
    }.toByteArray()
}

private class EpubDownloadServer(@Volatile var body: ByteArray) : AutoCloseable {
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
                        reader.readLine() ?: return@use
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
