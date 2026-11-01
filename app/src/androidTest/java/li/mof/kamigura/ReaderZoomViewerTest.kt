package li.mof.kamigura

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.reader.ReaderScreen
import li.mof.kamigura.reader.internal.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderZoomViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hugeArtBookDecodesOnlyBoundedTilesAndArchiveTemporaryFileIsRemoved() {
        val before = context.cacheDir.listFiles().orEmpty().count { it.name.startsWith("reader-region-") }
        val archive = File.createTempFile("region-test", ".zip", context.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("large.png")); zip.write(png(11000, 13000)); zip.closeEntry()
            }
            val source = openReaderRegionSource(context, ReaderRegionModel.Offline(OfflinePage.ArchiveEntry(archive.path, "large.png", 0)))!!
            source.use {
                assertEquals(11000, it.width); assertEquals(13000, it.height)
                val plan = readerRegionPlan(it.width, it.height, androidx.compose.ui.geometry.Rect(.45f, .45f, .55f, .55f), 5f, 4_000_000)!!
                assertEquals(1, plan.sample)
                var bytes = 0L
                for (rect in plan.tiles) {
                    val bitmap = it.decode(rect, plan.sample)!!
                    assertTrue(bitmap.width <= 1024 && bitmap.height <= 1024)
                    assertTrue(bitmap.allocationByteCount <= 4 * 1024 * 1024)
                    bytes += bitmap.allocationByteCount
                    bitmap.recycle()
                }
                assertTrue(bytes < 16_000_000)
            }
            assertEquals(before, context.cacheDir.listFiles().orEmpty().count { it.name.startsWith("reader-region-") })
        } finally { archive.delete() }
    }

    @Test fun downloadedPdfRendersClippedVectorTiles() {
        val file = File.createTempFile("region-test", ".pdf", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(0f, 450f, 600f, 900f, Paint().apply { color = Color.BLACK })
                document.finishPage(page); file.outputStream().use(document::writeTo)
            } finally { document.close() }
            openReaderRegionSource(context, ReaderRegionModel.Offline(OfflinePage.PdfPage(file.path, 0, 600, 900)))!!.use { source ->
                val bitmap = source.decode(ReaderRegionRect(400, 7000, 1200, 7800), 1)!!
                assertEquals(800, bitmap.width); assertEquals(800, bitmap.height)
                assertEquals(Color.WHITE, bitmap.getPixel(400, 50)); assertEquals(Color.BLACK, bitmap.getPixel(400, 700))
                bitmap.recycle()
            }
        } finally { file.delete() }
    }

    @Test fun missingCachedBytesNeverStartANetworkRequestAndUnsupportedArchiveFallsBack() {
        ReaderServer(1).use { server ->
            val loader = ImageLoader.Builder(context).diskCache(null).build()
            try {
                assertNull(openReaderRegionSource(context, ReaderRegionModel.Cached(loader, "${server.baseUrl}/api/Reader/image")))
                assertTrue(server.requests.isEmpty())
            } finally { loader.shutdown() }
        }
        val file = File.createTempFile("region-test", ".zip", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("bad")); it.write("not an image".toByteArray()); it.closeEntry() }
            assertNull(openReaderRegionSource(context, ReaderRegionModel.Offline(OfflinePage.ArchiveEntry(file.path, "bad", 0))))
        } finally { file.delete() }
    }

    @Test fun rotatedExifSourceMatchesTheDisplayedOrientation() {
        val image = File.createTempFile("region-exif", ".jpg", context.cacheDir)
        val archive = File.createTempFile("region-exif", ".zip", context.cacheDir)
        val cacheDirectory = File(context.cacheDir, "region-cache-${System.nanoTime()}")
        val cache = coil.disk.DiskCache.Builder().directory(cacheDirectory).maxSizeBytes(4L * 1024 * 1024).build()
        val loader = ImageLoader.Builder(context).diskCache(cache).build()
        try {
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap); canvas.drawColor(Color.WHITE)
            canvas.drawRect(0f, 300f, 400f, 600f, Paint().apply { color = Color.BLACK })
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
            androidx.exifinterface.media.ExifInterface(image).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
            }
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page.jpg")); image.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            openReaderRegionSource(context, ReaderRegionModel.Offline(OfflinePage.ArchiveEntry(archive.path, "page.jpg", 0)))!!.use { source ->
                assertEquals(400, source.width); assertEquals(600, source.height)
                val top = source.decode(ReaderRegionRect(0, 0, 400, 300), 1)!!
                assertEquals(Color.WHITE, top.getPixel(200, 150)); top.recycle()
            }
            cache.openEditor("exif-fixture")!!.apply {
                metadata.toFile().writeBytes(byteArrayOf()); image.copyTo(data.toFile(), overwrite = true); commit()
            }
            openReaderRegionSource(context, ReaderRegionModel.Cached(loader, "exif-fixture"))!!.use { source ->
                assertEquals(600, source.width); assertEquals(400, source.height)
                val left = source.decode(ReaderRegionRect(0, 0, 300, 400), 1)!!
                val right = source.decode(ReaderRegionRect(300, 0, 600, 400), 1)!!
                assertEquals(300, left.width); assertEquals(400, left.height)
                assertEquals(Color.BLACK, left.getPixel(150, 200)); assertEquals(Color.WHITE, right.getPixel(150, 200))
                left.recycle(); right.recycle()
            }
        } finally { image.delete(); archive.delete(); loader.shutdown(); cache.clear(); cacheDirectory.deleteRecursively() }
    }

    @Test fun replacingThePageDiscardsItsOldSharpTilesImmediately() {
        val archive = File.createTempFile("region-colors", ".zip", context.cacheDir)
        val selected = mutableStateOf(0)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((index, color) in listOf(Color.RED, Color.GREEN).withIndex()) {
                    val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
                    zip.putNextEntry(ZipEntry("$index.png")); bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry(); bitmap.recycle()
                }
            }
            compose.setContent {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    ReaderZoomableImage(ReaderRegionModel.Offline(OfflinePage.ArchiveEntry(archive.path, "${selected.value}.png", selected.value)),
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 2f; scaleY = 2f }, motion = ReaderImageZoomMotion(2f)) {
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Gray))
                    }
                }
            }
            sharp()
            val first = compose.onRoot().captureToImage().toPixelMap()
            assertTrue(first[first.width / 2, first.height / 2].red > .9f)
            compose.runOnIdle { selected.value = 1 }
            fun assertGreen() {
                val p = compose.onRoot().captureToImage().toPixelMap()
                val color = p[p.width / 2, p.height / 2]
                assertTrue(color.green > .9f && color.red < .1f)
            }
            val pending = compose.onRoot().captureToImage().toPixelMap()
            assertTrue(pending[pending.width / 2, pending.height / 2].red < .75f)
            sharp(); assertGreen()
        } finally { archive.delete() }
    }

    @Test fun imageReaderSharpTilesShareInvertAndDoNotRedownloadThePage() = settings {
        ReaderServer(1, cacheImages = true, imageBytes = png(2340, 3510)).use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            compose.setContent { MaterialTheme { ReaderScreen(session, AppSettingsStore(context), 1, 1, 1, 1,
                incognito = true, initialPage = 1, onBack = {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription("Left 1").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription("Right 1").fetchSemanticsNodes().isNotEmpty() }
            pixels(false)
            compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("LTR").performClick()
            compose.onNodeWithContentDescription("Close menu").performClick()
            compose.onRoot().performTouchInput { doubleClick(center) }
            sharp()
            val requests = server.requests.count { it.path == "/api/Reader/image" && it.getQueryParameter("page") == "1" }
            for ((mode, inverted) in listOf("Always" to true, "Off" to false, "Smart" to true)) {
                compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(mode, useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("Close menu").performClick()
                sharp(); pixels(inverted)
            }
            compose.onRoot().performTouchInput { swipe(center, center + Offset(60f, 60f), 300) }
            sharp()
            assertEquals(requests, server.requests.count { it.path == "/api/Reader/image" && it.getQueryParameter("page") == "1" })
            assertTrue(server.progressWrites.isEmpty())
            capture("image-sharp")
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val next = if (root.width > root.height) 4 else 3
            compose.onRoot().performTouchInput { swipe(Offset(width * .9f, height * .5f), Offset(width * .02f, height * .5f), 500) }
            compose.waitForIdle()
            // A gesture must start at the pan edge; the first swipe only reaches it.
            compose.onRoot().performTouchInput { swipe(Offset(width * .9f, height * .5f), Offset(width * .02f, height * .5f), 500) }
            compose.waitForIdle()
            compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$next / 6", substring = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close menu").performClick()
            sharp()
            compose.onRoot().performTouchInput { doubleClick(center) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("Reader sharp image").fetchSemanticsNodes().isEmpty() }
            compose.onRoot().performTouchInput { click(Offset(width * .9f, height * .5f)) }
            compose.waitForIdle()
            compose.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close menu").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("${if (root.width > root.height) 6 else 4} / 6", substring = true).assertIsDisplayed()
        }
    }

    @Test fun novelViewerKeepsPositionOnCloseBackAndSavedStateRestoration() = novel(false)
    @Test fun novelPinchOpensViewerWithoutWritingIncognitoProgress() = novel(true)

    private fun novel(pinch: Boolean) = settings {
        val html = """<div><img src="/api/Book/1/book-resources?file=art.png&amp;apiKey=fixture-secret"></div>"""
        ReaderServer(3, cacheImages = true, bookFragment = { if (it == 0) html else novelFixture(false, 5) }, imageBytes = png(2340, 1170)).use { server ->
            val session = KavitaSessionStore(context).apply { useTransient(KavitaSession(baseUrl = server.baseUrl)) }
            val restore = StateRestorationTester(compose)
            val viewport = mutableStateOf<IntSize?>(null)
            restore.setContent { MaterialTheme {
                val density = LocalDensity.current
                val shape = viewport.value
                Box(if (shape == null) Modifier.fillMaxSize() else Modifier.size(with(density) { shape.width.toDp() }, with(density) { shape.height.toDp() })) {
                    ReaderScreen(session, AppSettingsStore(context), 1, 1, 1, 1, incognito = pinch, onBack = {})
                }
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
            val before = compose.onNodeWithContentDescription("EPUB page").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
            capture("novel-before")
            val art = illustrationPoint()
            // Await WebView paint/hit regions, not just its first native layout notification.
            compose.waitForIdle()
            if (pinch) compose.onRoot().performTouchInput {
                val y = art.y
                down(0, Offset(width * .45f, y)); down(1, Offset(width * .55f, y))
                moveTo(0, Offset(width * .35f, y), 150); moveTo(1, Offset(width * .65f, y), 150)
                moveTo(0, Offset(width * .30f, y), 150); moveTo(1, Offset(width * .70f, y), 150)
                up(0); up(1)
            } else compose.onRoot().performTouchInput { doubleClick(art) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Close illustration").fetchSemanticsNodes().isNotEmpty() }
            if (!pinch) compose.onNodeWithContentDescription("Illustration viewer").performTouchInput { doubleClick(center) }
            sharp(); capture("novel-sharp")
            val originalBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val short = (minOf(originalBounds.width, originalBounds.height) * .6f).toInt()
            val long = (minOf(originalBounds.width, originalBounds.height) * .9f).toInt()
            val rotated = if (originalBounds.width > originalBounds.height) IntSize(short, long) else IntSize(long, short)
            compose.runOnIdle { viewport.value = rotated }
            compose.waitUntil(10_000) {
                val bounds = compose.onNodeWithContentDescription("Illustration viewer").fetchSemanticsNode().boundsInRoot
                kotlin.math.abs(bounds.width - rotated.width) < 2 && kotlin.math.abs(bounds.height - rotated.height) < 2
            }
            restore.emulateSavedInstanceStateRestore()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("EPUB page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Close illustration").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("EPUB page").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, before))
            compose.onRoot().performTouchInput { doubleClick(illustrationPoint()) }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close illustration").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("Close illustration").assertDoesNotExist()
            compose.onNodeWithContentDescription("EPUB page").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, before))
            assertTrue(server.bookProgressWrites.isEmpty())
        }
    }

    private fun sharp() = compose.waitUntil(15_000) { compose.onAllNodesWithTag("Reader sharp image").fetchSemanticsNodes().isNotEmpty() }
    private fun illustrationPoint(): Offset {
        var point: Offset? = null
        compose.waitUntil(5_000) {
            val latch = java.util.concurrent.CountDownLatch(1)
            compose.activityRule.scenario.onActivity { activity ->
                fun find(view: android.view.View): li.mof.kamigura.reader.novel.NovelPageView? {
                    if (view is li.mof.kamigura.reader.novel.NovelPageView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                val view = find(activity.window.decorView)!!
                view.evaluateJavascript("window.novelReader.illustrations().map(i=>[i.left,i.top,i.right,i.bottom])") { json ->
                    val values = org.json.JSONArray(json)
                    if (values.length() > 0) {
                        val rect = values.getJSONArray(0)
                        point = Offset(((rect.getDouble(0) + rect.getDouble(2)) * view.width / 2).toFloat(),
                            ((rect.getDouble(1) + rect.getDouble(3)) * view.height / 2).toFloat())
                    }
                    latch.countDown()
                }
            }
            assertTrue(latch.await(3, java.util.concurrent.TimeUnit.SECONDS))
            point != null
        }
        return point!!
    }
    private fun pixels(inverted: Boolean) = compose.waitUntil(15_000) {
        val p = compose.onRoot().captureToImage().toPixelMap()
        val x = (p.width * .25f).toInt()
        val top = p[x, (p.height * .35f).toInt()].red
        val bottom = p[x, (p.height * .65f).toInt()].red
        if (inverted) top < .1f && bottom > .9f else top > .9f && bottom < .1f
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.getExternalFilesDir(null)!!.resolve("zoom-$name-${bitmap.width}x${bitmap.height}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun settings(block: () -> Unit) {
        val store = AppSettingsStore(context)
        val original = runBlocking { store.flow.first().reader.image }
        try {
            runBlocking { store.setInvertMode(InvertMode.Off)
                store.setPageTransition(if (context.resources.displayMetrics.widthPixels > context.resources.displayMetrics.heightPixels) PageTransition.Slide else PageTransition.Curl)
                store.setInvertWhiteThreshold(.2f) }
            block()
        } finally {
            runBlocking { store.setInvertMode(original.invertMode); store.setPageTransition(original.pageTransition); store.setInvertWhiteThreshold(original.invertWhiteThreshold) }
        }
    }
}

/** Streaming PNG writer: the 143 MP fixture itself never needs a source-sized Bitmap. */
private fun png(width: Int, height: Int): ByteArray {
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { output ->
        val row = ByteArray(width * 3 + 1)
        repeat(height) { y ->
            row.fill(if (y < height / 2) 255.toByte() else 0); row[0] = 0
            output.write(row)
        }
    }
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
    fun chunk(type: String, body: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(body.size); out.write(name); out.write(body)
        val crc = CRC32(); crc.update(name); crc.update(body); out.writeInt(crc.value.toInt())
    }
    val header = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray()
    chunk("IHDR", header); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
    return bytes.toByteArray()
}
