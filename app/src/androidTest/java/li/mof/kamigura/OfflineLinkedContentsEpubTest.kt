package li.mof.kamigura

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.mof.kamigura.download.*
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.ReaderSessionPreferenceCache
import li.mof.kamigura.reader.internal.readerJumpLandingPage
import li.mof.kamigura.reader.internal.readerVisiblePageIndices
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class OfflineLinkedContentsEpubTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = mutableStateOf<KavitaSessionStore?>(null)

    @Before fun hostReader() { compose.setContent { MaterialTheme {
        reader.value?.let { ReaderScreen(it, AppSettingsStore(context), 1, 1, 1, 1,
            incognito = true, onBack = { reader.value = null }) }
    } } }

    @Test fun transparentLinkedContentsDownloadsAndReadsOffline() = runBlocking {
        val file = context.cacheDir.resolve("linked-contents-fixture.epub")
        file.writeBytes(epubFixture(doctype = "<!DOCTYPE html>", pageTransform = { index, body ->
            if (index == 1) body.replace("</svg>", "<a xlink:href='page2.xhtml' target='_top'><rect fill-opacity='0.0' x='1' y='1' width='114' height='126'/></a></svg>") else body
        }))
        try { readBook(file, 3, 1, "fixture") } finally { file.delete() }
    }

    @Test fun visibleShapesAndLinkedImagesStayUnsupportedOnAndroid() {
        val file = context.cacheDir.resolve("linked-contents-rejected.epub")
        try {
            for (link in listOf(
                "<a href='page2.xhtml'><rect fill-opacity='1'/></a>",
                "<a href='page2.xhtml'><rect fill-opacity='0' stroke='red'/></a>",
                "<a href='page2.xhtml'><image href='a.png'/></a>",
                "<a href='https://example.com/outside'><rect fill-opacity='0'/></a>",
                "<a href='../../outside.xhtml'><rect fill-opacity='0'/></a>")) {
                file.writeBytes(epubFixture(doctype = "<!DOCTYPE html>", pageTransform = { index, body ->
                    if (index == 1) body.replace("</svg>", "$link</svg>") else body
                }))
                try { ZipFile(file).use { offlineEpubImageEntries(it) }; fail("Expected unsupported linked SVG") }
                catch (error: UnsupportedOfflineFormatException) { assertEquals(UnsupportedOfflineEpubMessage, error.message) }
            }
        } finally { file.delete() }
    }

    @Test fun realBooksDownloadAndReadOfflineWhenExplicitlyProvided() = runBlocking {
        val directory = InstrumentationRegistry.getArguments().getString("linkedContentsFiles")
        assumeTrue("Supply real archive copies and metadata on the read-only AVD", directory != null)
        val root = File(directory!!)
        val books = JSONArray(root.resolve("metadata.json").readText())
        for (index in 0 until books.length()) {
            val book = books.getJSONObject(index)
            readBook(root.resolve(book.getString("file")), book.getInt("pages"), book.getInt("contents"), "real-${index + 1}")
        }
    }

    private suspend fun readBook(file: File, pages: Int, contents: Int, label: String) {
        val preferences = context.cacheDir.resolve("reader_session_preferences.json")
        val original = preferences.takeIf { it.exists() }?.readBytes()
        EpubDownloadServer(archive = file).use { server ->
            val repository = OfflineIssueRepository(context)
            val session = KavitaSession(baseUrl = server.baseUrl)
            try {
                repository.enqueue(session, 1, 1, 1, 1, label, "Linked contents", expectedPageCount = pages)
                val record = withTimeout(120_000) {
                    var record = repository.reconcile(session, 1)!!
                    while (record.status in setOf(OfflineDownloadStatus.Queued, OfflineDownloadStatus.Downloading)) {
                        delay(100); record = repository.reconcile(session, 1)!!
                    }
                    record
                }
                assertEquals(OfflineDownloadStatus.Ready, record.status)
                assertArrayEquals(archiveDigest(file), archiveDigest(File(record.archivePath)))
                val chapter = repository.localChapter(session, 1)!!
                assertEquals(pages, chapter.pages.size)
                assertEquals((0 until pages).toList(), chapter.pages.map { it.index })
                server.close()
                shell("cmd connectivity airplane-mode enable")
                compose.runOnIdle { reader.value = KavitaSessionStore(context).apply { useTransient(session) } }
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Page 0").fetchSemanticsNodes().isNotEmpty() }
                val viewport = compose.onRoot().fetchSemanticsNode().size
                var cursor = 0
                for ((kind, page) in listOf("contents" to contents, "last" to pages - 1)) {
                    compose.onRoot().performTouchInput { click(Offset(width / 2f, height * .2f)) }
                    compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("LTR").performClick()
                    compose.onNodeWithText("Off", useUnmergedTree = true).performClick()
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                        .performSemanticsAction(SemanticsActions.SetProgress) { it(page.toFloat()) }
                    cursor = readerJumpLandingPage(cursor, page, pages, viewport.height > viewport.width, chapter.dimensions)
                    compose.onNodeWithText("${cursor + 1} / $pages", substring = true).assertIsDisplayed()
                    compose.onNodeWithContentDescription("Close menu").performClick()
                    val visible = readerVisiblePageIndices(cursor, pages, viewport.height > viewport.width, chapter.dimensions)
                    assertTrue(page in visible)
                    val label = if (visible.size == 1) "Page $page"
                        else if (page == cursor) "Left $page" else "Right $page"
                    val image = compose.onNodeWithContentDescription(label)
                    compose.waitUntil(15_000) { image.isDisplayed() }
                    val bounds = image.fetchSemanticsNode().boundsInRoot
                    val expected = decodeOfflinePage(chapter.pages[page], bounds.width.toInt(), bounds.height.toInt())!!
                    try { compose.waitUntil(15_000) { imageMatches(expected, image, label) } } finally { expected.recycle() }
                    val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    context.getExternalFilesDir(null)!!.resolve("linked-contents-$label-$kind.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                assertEquals(1, server.requestPaths.size)
                assertFalse(repository.observe(session, 1).first()!!.progressPending)
                println("Offline $label: $pages pages; contents=$contents and last=${pages - 1} matched archive pixels.")
            } finally {
                compose.runOnIdle { reader.value = null }; compose.waitForIdle()
                shell("cmd connectivity airplane-mode disable")
                repository.observe(session, 1).first()?.let {
                    repository.markProgressSynced(session, 1, it.localPage, markedRead = true, markedUnread = true)
                }
                repository.remove(session, 1)
                if (original == null) preferences.delete() else preferences.writeBytes(original)
                ReaderSessionPreferenceCache.clearMemoryForTest()
            }
        }
    }

    private fun imageMatches(expected: Bitmap, image: SemanticsNodeInteraction, label: String): Boolean {
        val pixels = image.captureToImage().toPixelMap()
        val scale = min(pixels.width.toFloat() / expected.width, pixels.height.toFloat() / expected.height)
        // Spread sheets align to the binding, while single pages are centered.
        val left = when {
            label.startsWith("Left ") -> pixels.width - expected.width * scale
            label.startsWith("Right ") -> 0f
            else -> (pixels.width - expected.width * scale) / 2
        }
        val top = (pixels.height - expected.height * scale) / 2
        var matches = 0
        for (y in 1..24) for (x in 1..24) {
            val sx = expected.width * x / 25; val sy = expected.height * y / 25
            val source = expected.getPixel(sx, sy)
            val actual = pixels[(left + sx * scale).roundToInt(), (top + sy * scale).roundToInt()]
            if (abs(actual.red - android.graphics.Color.red(source) / 255f) +
                abs(actual.green - android.graphics.Color.green(source) / 255f) +
                abs(actual.blue - android.graphics.Color.blue(source) / 255f) < .3f) matches++
        }
        return matches > 24 * 24 * .9
    }

    private fun archiveDigest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }
}
