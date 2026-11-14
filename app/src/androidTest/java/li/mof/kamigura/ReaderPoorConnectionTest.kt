package li.mof.kamigura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.mof.kamigura.reader.internal.*
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReaderPoorConnectionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun tinyCacheEvictionKeepsVisiblePagesAndPrefetchReadable() {
        tinyCacheEviction(CachePolicy.ENABLED)
    }

    @Test fun tinyCacheEvictionAlsoRecoversManualRetry() {
        tinyCacheEviction(CachePolicy.WRITE_ONLY)
    }

    @Test fun noSpaceWritingToDiskDoesNotFailThePage() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "reader-full-").toFile()
        val cache = DiskCache.Builder().directory(directory).maxSizeBytes(2 * 1024 * 1024L)
            .fileSystem(object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun sink(file: Path, mustCreate: Boolean): okio.Sink {
                    if (file.name.endsWith(".1.tmp")) {
                        throw IOException(ErrnoException("write", OsConstants.ENOSPC))
                    }
                    return super.sink(file, mustCreate)
                }
            }).build()
        val loader = ImageLoader.Builder(context)
            .callFactory(readerImageCallFactory(client()))
            .diskCache(ReaderDiskCache(cache))
            .components { add(ReaderImageRequestInterceptor()) }
            .build()
        try {
            SlowPageServer(Transfer.Normal).use { server ->
                val request = ImageRequest.Builder(context).data(server.url).size(600, 900).build()
                val result = loader.execute(request)
                assertTrue((result as? ErrorResult)?.throwable.toString(), result is SuccessResult)
                assertNull((result as SuccessResult).diskCacheKey)
                assertEquals(2, server.requests.get())
            }
        } finally {
            loader.shutdown()
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun tinyCacheEviction(policy: CachePolicy) = runBlocking {
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "reader-tiny-").toFile()
        val cache = DiskCache.Builder().directory(directory).maxSizeBytes(128 * 1024L).build()
        val loader = ImageLoader.Builder(context)
            .callFactory(readerImageCallFactory(client()))
            .diskCache(ReaderDiskCache(cache))
            .components { add(ReaderImageRequestInterceptor()) }
            .build()
        try {
            SlowPageServer(Transfer.Hold).use { visible ->
                SlowPageServer(Transfer.Normal).use { prefetch ->
                    fun request(url: String, diskPolicy: CachePolicy) = ImageRequest.Builder(context)
                        .data(url).size(600, 900).diskCacheKey(url)
                        .diskCachePolicy(diskPolicy).memoryCachePolicy(CachePolicy.DISABLED).build()
                    withTimeout(12_000) {
                        val visibleRequest = request(visible.url, policy)
                        val held = async { loader.execute(visibleRequest) }
                        val shared = async { loader.execute(visibleRequest) }
                        try {
                            // Keep the visible editor open while two larger-than-budget
                            // images commit and the real Coil LRU trims past that editor.
                            while (directory.listFiles().orEmpty().none { it.name.endsWith(".1.tmp") }) delay(10)
                            visible.transfer = Transfer.Normal
                            val prefetches = (1..2).map {
                                async { loader.execute(request("${prefetch.url}&window=$it", CachePolicy.ENABLED)) }
                            }
                            prefetches.forEach { assertTrue(it.await() is SuccessResult) }
                            while (cache.size != 0L) delay(10)
                        } finally {
                            visible.release.countDown()
                        }
                        for (result in listOf(held.await(), shared.await())) {
                            assertTrue((result as? ErrorResult)?.throwable.toString(), result is SuccessResult)
                            assertNull((result as SuccessResult).diskCacheKey)
                        }
                        assertEquals("The shared flight retries only once", 2, visible.requests.get())
                        assertTrue("Each prefetch can retry at most once", prefetch.requests.get() in 2..4)
                    }
                }
            }
        } finally {
            loader.shutdown()
            cache.clear()
            directory.deleteRecursively()
        }
    }

    @Test fun midBodyStallBecomesRetryAndThenSmartRecovers() = transferFailure(Transfer.Stall)
    @Test fun midBodyDisconnectBecomesRetryAndThenSmartRecovers() = transferFailure(Transfer.Drop)

    @Test fun initialSlowBodyCanBeCancelledAndRetried() {
        SlowPageServer(Transfer.Trickle).use { server ->
            val loader = loader(server)
            val retries = ReaderPageRetryState()
            try {
                surface(server, loader, retries)
                compose.waitUntil(8_000) { retries.canCancel(1) }
                compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
                compose.runOnIdle { assertTrue(retries.failed(1)); assertEquals(1, retries.attempt(1)) }
                compose.onNodeWithText("Retry").assertIsEnabled()
                server.transfer = Transfer.Normal
                compose.onNodeWithText("Retry").performClick()
                awaitImage(inverted = true)
                assertEquals(2, server.requests.get())
            } finally { loader.shutdown() }
        }
    }

    private fun transferFailure(transfer: Transfer) {
        SlowPageServer(transfer).use { server ->
            val loader = loader(server)
            val retries = ReaderPageRetryState()
            try {
                surface(server, loader, retries)
                compose.waitUntil(12_000) { retries.failed(1) }
                compose.onNodeWithText("Retry").assertIsEnabled()
                server.transfer = Transfer.Normal
                compose.onNodeWithText("Retry").performClick()
                awaitImage(inverted = true)
                assertEquals(2, server.requests.get())
            } finally { loader.shutdown() }
        }
    }

    @Test fun slowProgressingBodySurvivesRealReconnectionsWithoutAnotherGet() {
        SlowPageServer(Transfer.Trickle).use { server ->
            val loader = loader(server)
            val retries = ReaderPageRetryState()
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun connected() = manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            assertTrue("Emulator needs an initial validated network", connected())
            try {
                surface(server, loader, retries, observeNetwork = true)
                compose.waitUntil(5_000) { server.bytesSent.get() > 0 }
                val generation = compose.runOnIdle { retries.networkGeneration }
                repeat(2) {
                    shell("cmd connectivity airplane-mode enable")
                    compose.waitUntil(10_000) { !connected() }
                    shell("cmd connectivity airplane-mode disable")
                    compose.waitUntil(20_000) { connected() }
                }
                compose.waitUntil(8_000) { retries.canCancel(1) }
                compose.onNodeWithText("Loading", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText("Cancel").assertIsEnabled()
                compose.runOnIdle {
                    assertTrue(retries.networkGeneration > generation)
                    assertEquals(0, retries.attempt(1))
                }
                awaitImage(inverted = true)
                compose.waitUntil(2_000) { server.completedMillis > 0 }
                assertEquals(1, server.requests.get())
                assertTrue("Total transfer must exceed a conventional 10-second call timeout", server.completedMillis > 10_000)
            } finally {
                shell("cmd connectivity airplane-mode disable")
                loader.shutdown()
            }
        }
    }

    @Test fun identicalRequestsShareTransferEvenWhenFirstConsumerLeaves() = runBlocking {
        SlowPageServer(Transfer.Hold).use { server ->
            val loader = loader(server)
            try {
                fun request() = ImageRequest.Builder(context).data(server.url).size(600, 900).allowHardware(false).build()
                val first = async { loader.execute(request()) }
                while (server.bytesSent.get() == 0) delay(20)
                val second = async { loader.execute(request()) }
                val third = async { loader.execute(request()) }
                delay(300)
                first.cancel()
                server.release.countDown()
                val secondResult = second.await() as SuccessResult
                val thirdResult = third.await() as SuccessResult
                assertNotSame(secondResult.drawable, thirdResult.drawable)
                assertEquals(1, server.requests.get())
                val cached = loader.execute(request()) as SuccessResult
                assertEquals(DataSource.MEMORY_CACHE, cached.dataSource)
                val retried = loader.execute(request().newBuilder()
                    .memoryCachePolicy(CachePolicy.WRITE_ONLY).diskCachePolicy(CachePolicy.WRITE_ONLY)
                    .addHeader("Cache-Control", "no-cache")
                    .setParameter(ReaderRetryAttemptParameter, 1, memoryCacheKey = null).build()) as SuccessResult
                assertEquals(secondResult.memoryCacheKey, retried.memoryCacheKey)
                assertEquals(secondResult.diskCacheKey, retried.diskCacheKey)
                assertEquals(2, server.requests.get())
            } finally { loader.shutdown() }
        }
    }

    @Test fun differentSizesDoNotShareTheWrongDecode() = runBlocking {
        SlowPageServer(Transfer.Hold).use { server ->
            val loader = loader(server)
            try {
                val large = async { loader.execute(ImageRequest.Builder(context).data(server.url).size(600, 900).build()) }
                while (server.bytesSent.get() == 0) delay(20)
                val small = async { loader.execute(ImageRequest.Builder(context).data(server.url).size(100, 150).build()) }
                repeat(50) { if (server.requests.get() < 2) delay(20) }
                assertEquals(2, server.requests.get())
                server.release.countDown()
                val largeImage = (large.await() as SuccessResult).drawable
                val smallImage = (small.await() as SuccessResult).drawable
                assertTrue(largeImage.intrinsicWidth > smallImage.intrinsicWidth)
            } finally { loader.shutdown() }
        }
    }

    @Test fun memoryHitsCompleteInlineOnMainWithoutSuspending() = runBlocking {
        SlowPageServer(Transfer.Normal).use { server ->
            val loader = loader(server)
            try {
                fun request() = ImageRequest.Builder(context).data(server.url).size(600, 900).allowHardware(false).build()
                assertTrue(loader.execute(request()) is SuccessResult)
                withContext(Dispatchers.Main.immediate) {
                    repeat(50) {
                        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.execute(request()) }
                        assertTrue("Warm image must finish before returning to the frame", result.isCompleted)
                        assertEquals(DataSource.MEMORY_CACHE, (result.await() as SuccessResult).dataSource)
                    }
                }
                assertEquals(1, server.requests.get())
            } finally { loader.shutdown() }
        }
    }

    @Test fun cancellingEveryConsumerStopsTransferAndRetryStartsFresh() = runBlocking {
        SlowPageServer(Transfer.Hold).use { server ->
            val callsCancelled = AtomicInteger()
            val client = client().newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun canceled(call: okhttp3.Call) { callsCancelled.incrementAndGet() }
            }).build()
            val loader = loader(server, client)
            try {
                val request = ImageRequest.Builder(context).data(server.url).size(600, 900).build()
                val pending = async { loader.execute(request) }
                while (server.bytesSent.get() == 0) delay(20)
                pending.cancel(); pending.join()
                repeat(100) { if (callsCancelled.get() == 0) delay(20) }
                assertEquals(1, callsCancelled.get())
                server.transfer = Transfer.Normal
                assertTrue(loader.execute(request) is SuccessResult)
                assertEquals(2, server.requests.get())
            } finally { loader.shutdown() }
        }
    }

    @Test fun failedSmartAfterRetryDefaultsToInvertedWithoutNetworkFallback() = smartFailure(stallSample = false)
    @Test fun stalledSmartCacheFallbackIsBoundedAndDefaultsToInverted() = smartFailure(stallSample = true)
    @Test fun failedSmartUsesTheNearestUninvertedDecisionWithoutCachingFallback() = smartFailure(false, false)
    @Test fun failedSmartUsesTheNearestInvertedDecisionWithoutCachingFallback() = smartFailure(false, true)

    private fun smartFailure(stallSample: Boolean, knownDecision: Boolean? = null) {
        SlowPageServer(Transfer.Drop).use { server ->
            val original = loader(server)
            val samples = AtomicInteger()
            val loader = original.newBuilder().components {
                add(Interceptor { chain ->
                    if (chain.request.networkCachePolicy == CachePolicy.DISABLED) {
                        samples.incrementAndGet()
                        if (stallSample) delay(20_000)
                        ErrorResult(null, chain.request, SocketTimeoutException("cache sample unavailable ${server.url}"))
                    } else when (val result = chain.proceed(chain.request)) {
                        is SuccessResult -> result.copy(drawable = object : Drawable() {
                            override fun getIntrinsicWidth() = result.drawable.intrinsicWidth
                            override fun getIntrinsicHeight() = result.drawable.intrinsicHeight
                            override fun draw(canvas: Canvas) {
                                if (bounds.width() == 64 && bounds.height() == 64)
                                    throw IllegalStateException("fixture analysis failed ${server.url}")
                                result.drawable.bounds = bounds
                                result.drawable.draw(canvas)
                            }
                            override fun setAlpha(alpha: Int) { result.drawable.alpha = alpha }
                            override fun setColorFilter(filter: ColorFilter?) { result.drawable.colorFilter = filter }
                            @Deprecated("Drawable compatibility") override fun getOpacity() = PixelFormat.OPAQUE
                        })
                        is ErrorResult -> result
                    }
                })
            }.build()
            val retries = ReaderPageRetryState()
            val decisions = mutableMapOf<ReaderInvertCacheKey, Boolean>()
            try {
                surface(server, loader, retries, decisions = decisions, knownNeighborDecision = knownDecision)
                val originalDecisions = decisions.toMap()
                compose.waitUntil(10_000) { retries.failed(1) }
                server.transfer = Transfer.Normal
                compose.onNodeWithText("Retry").performClick()
                awaitImage(inverted = knownDecision ?: true)
                assertTrue(samples.get() > 0)
                assertEquals("Failure fallback must not become a cached analysis result", originalDecisions, decisions)
                assertEquals(2, server.requests.get())
            } finally { loader.shutdown(); original.shutdown() }
        }
    }

    private fun surface(server: SlowPageServer, loader: ImageLoader, retries: ReaderPageRetryState,
                        observeNetwork: Boolean = false, decisions: MutableMap<ReaderInvertCacheKey, Boolean> = mutableMapOf(),
                        knownNeighborDecision: Boolean? = null) {
        val white = whiteBitmap()
        knownNeighborDecision?.let { decisions[ReaderInvertCacheKey(white, .5f)] = it }
        compose.setContent { MaterialTheme {
            if (observeNetwork) ReaderPageRetryEffects(retries, setOf(1), true)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                ReaderPageView(1, 3, maxHeight > maxWidth, emptyMap(), false, { if (it == 1) server.url else white },
                    loader, InvertMode.Smart, .5f, decisions, retryState = retries,
                    interactiveRetry = true, modifier = Modifier.fillMaxSize())
            }
        } }
    }

    private fun awaitImage(inverted: Boolean) {
        try {
            compose.waitUntil(25_000) {
                if (listOf("Retry", "Cancel", "...").any { compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }) false
                else {
                    val pixels = compose.onRoot().captureToImage().toPixelMap()
                    var matching = 0
                    for (x in 1..10) for (y in 1..10) {
                        // Sample the sheet, not portrait letterboxing around a fit-scaled page.
                        val c = pixels[(pixels.width * (.15f + x * .02f)).toInt(),
                            (pixels.height * (.35f + y * .03f)).toInt()]
                        if (if (inverted) c.red < .2f else c.red > .8f) matching++
                    }
                    matching > 65
                }
            }
        } finally {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            context.getExternalFilesDir(null)!!.resolve("poor-connection-inverted-$inverted.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun whiteBitmap() = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
    private fun client() = OkHttpClient.Builder().callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun loader(server: SlowPageServer, client: OkHttpClient = client()) = runBlocking {
        val session = KavitaSession(baseUrl = server.baseUrl)
        val store = KavitaSessionStore(context).apply { useTransient(session) }
        KavitaClient(context, store).buildReaderImageLoader(client, session)
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }
}

private enum class Transfer { Normal, Stall, Drop, Trickle, Hold }

/** A real HTTP body: idle stalls, truncated bodies, and progressing 0.5 MB grayscale PNGs. */
private class SlowPageServer(@Volatile var transfer: Transfer) : AutoCloseable {
    private val server = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val url = "$baseUrl/api/Reader/image?chapterId=1&page=1&apiKey=fixture-private-key"
    val requests = AtomicInteger()
    val bytesSent = AtomicInteger()
    val release = CountDownLatch(1)
    @Volatile var completedMillis = 0L
    private val png = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).let { bitmap ->
        val random = java.util.Random(42)
        bitmap.setPixels(IntArray(600 * 900) {
            val v = if (it % 8 == 0) random.nextInt(80) else 220 + random.nextInt(36)
            android.graphics.Color.rgb(v, v, v)
        }, 0, 600, 0, 0, 600, 900)
        ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    init {
        workers.execute {
            while (!server.isClosed) try {
                val socket = server.accept(); sockets.add(socket)
                workers.execute { socket.use { runCatching { respond(it) } }; sockets.remove(socket) }
            } catch (_: Exception) { break }
        }
    }
    private fun respond(socket: Socket) {
        socket.soTimeout = 5_000
        val input = socket.getInputStream().bufferedReader()
        input.readLine() ?: return
        while (!input.readLine().isNullOrEmpty()) Unit
        requests.incrementAndGet()
        val mode = transfer
        val started = System.nanoTime()
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
        when (mode) {
            Transfer.Stall, Transfer.Hold, Transfer.Drop -> {
                output.write(png, 0, 4096); output.flush(); bytesSent.addAndGet(4096)
                if (mode == Transfer.Drop) return
                release.await(30, TimeUnit.SECONDS)
                output.write(png, 4096, png.size - 4096)
            }
            Transfer.Trickle -> {
                val chunk = (png.size + 39) / 40
                var offset = 0
                while (offset < png.size) {
                    val count = minOf(chunk, png.size - offset)
                    output.write(png, offset, count); output.flush(); bytesSent.addAndGet(count)
                    offset += count
                    Thread.sleep(400)
                }
            }
            Transfer.Normal -> output.write(png)
        }
        output.flush()
        completedMillis = (System.nanoTime() - started) / 1_000_000
    }
    override fun close() {
        release.countDown(); server.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
    }
}
