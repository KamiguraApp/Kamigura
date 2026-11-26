package li.mof.kamigura.reader.novel

import android.graphics.BitmapFactory
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.reader.internal.parseEpubImagePage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val ImageLimit = 4 * 1024 * 1024
private const val CacheLimit = 12 * 1024 * 1024
private const val HeaderLimit = 64 * 1024
private const val SourcesPerSection = 24
internal const val NovelImageRetryParameter = "_kamigura_art"

// Use the resource URL Kavita put in the HTML, exactly as WebView does. Never attach
// account headers to document-supplied URLs or follow a redirect with its query token.
private val resourceClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

internal class NovelIllustrationCache(
    parent: CoroutineScope,
    baseUrl: String,
    private val chapterId: Int,
    private val client: Call.Factory = resourceClient
) {
    private data class Image(val mime: String, val bytes: ByteArray)
    private data class Size(val width: Int, val height: Int)

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val baseUrl = normalizeKavitaBaseUrl(baseUrl)
    private val base = "${this.baseUrl}/".toHttpUrl()
    private val lock = Any()
    private val slots = Semaphore(2)
    private var window = IntRange.EMPTY
    private val sections = mutableMapOf<Int, Set<String>>()
    private val pending = mutableMapOf<String, Deferred<Image?>>()
    private val ready = LinkedHashMap<String, Image>(16, .75f, true)
    private val sizes = mutableMapOf<String, Size>()
    private val browserLoads = mutableSetOf<String>()
    private var closed = false

    fun retainAround(page: Int, count: Int) = synchronized(lock) {
        window = maxOf(0, page - 1)..minOf(count - 1, page + 1)
        sections.keys.retainAll(window.toSet())
        val retained = sections.values.flatten().toSet()
        ready.keys.retainAll(retained)
        sizes.keys.retainAll(retained)
        browserLoads.retainAll(retained)
        pending.filterKeys { it !in retained }.values.toList().forEach { it.cancel() }
    }

    fun prepare(page: Int, html: String, speculative: Boolean) {
        synchronized(lock) { if (closed || page !in window || page in sections) return }
        val urls = parseEpubImagePage(html).images.asSequence()
            .mapNotNull { base.resolve(it.trim())?.newBuilder()?.fragment(null)?.build()?.toString() }
            .filter { novelResourceAllowed(it, baseUrl, chapterId) }
            .distinct().take(SourcesPerSection).toSet()
        synchronized(lock) {
            if (closed || page !in window || page in sections) return
            sections[page] = urls
            if (speculative) urls.forEach { request(it)?.start() }
        }
    }

    fun imagesJson(page: Int): String = synchronized(lock) {
        JSONObject().apply {
            for (url in sections[page].orEmpty()) {
                put(url, JSONObject().apply {
                    sizes[url]?.let { put("size", JSONArray(listOf(it.width, it.height))) }
                    put("ready", url in ready || url in browserLoads)
                })
            }
        }.toString()
    }

    fun response(page: Int, rawUrl: String): WebResourceResponse? {
        val resource = base.resolve(rawUrl) ?: return null
        val isRetry = resource.queryParameter(NovelImageRetryParameter) != null
        val url = resource.newBuilder().fragment(null).removeAllQueryParameters(NovelImageRetryParameter).build().toString()
        synchronized(lock) {
            if (closed || url !in sections[page].orEmpty() || url in browserLoads) return null
            ready[url]?.let { return WebResourceResponse(it.mime, null, ByteArrayInputStream(it.bytes)) }
            if (isRetry) return null
            request(url)?.start()
        }
        // Even a deferred InputStream blocks Chromium's first visual commit. Send a
        // no-store empty reply and let the reader script retry locally once bytes exist.
        return WebResourceResponse("text/plain", null, 503, "Illustration pending", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)))
    }

    private fun request(url: String): Deferred<Image?>? {
        if (url in ready || url in browserLoads) return null
        return pending[url] ?: scope.async(start = CoroutineStart.LAZY) {
            val image = slots.withPermit { download(url) }
            synchronized(lock) {
                if (!closed && sections.values.any { url in it }) {
                    if (image == null) browserLoads += url
                    else {
                        ready[url] = image
                        while (ready.values.sumOf { it.bytes.size } > CacheLimit) ready.remove(ready.keys.first())
                    }
                }
            }
            image
        }.also { task ->
            pending[url] = task
            task.invokeOnCompletion { synchronized(lock) { if (pending[url] === task) pending.remove(url) } }
        }
    }

    private suspend fun download(url: String): Image? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val image = try {
                    response.use { it.readIllustration(url) }
                } catch (_: IOException) {
                    null
                }
                continuation.resume(image)
            }
        })
    }

    private fun Response.readIllustration(url: String): Image? {
        val body = body ?: return null
        if (!isSuccessful) return null
        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: return null
        if (!mime.startsWith("image/")) return null
        if (body.contentLength() > ImageLimit) return null // Normal WebView streaming handles oversized images.
        val output = ByteArrayOutputStream()
        val prefix = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var nextProbe = 512
        val stream = body.byteStream()
        while (true) {
            val length = stream.read(buffer)
            if (length < 0) break
            if (output.size() + length > ImageLimit) return null
            output.write(buffer, 0, length)
            if (nextProbe <= HeaderLimit) {
                prefix.write(buffer, 0, minOf(length, HeaderLimit - prefix.size()))
                if (prefix.size() >= nextProbe) {
                    val bytes = prefix.toByteArray()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        synchronized(lock) {
                            if (!closed && sections.values.any { url in it }) sizes[url] = Size(options.outWidth, options.outHeight)
                        }
                        nextProbe = HeaderLimit + 1
                    } else {
                        nextProbe = prefix.size() * 2
                    }
                }
            }
        }
        return Image(mime, output.toByteArray())
    }

    fun close() {
        synchronized(lock) {
            closed = true
            sections.clear(); ready.clear(); sizes.clear(); browserLoads.clear(); pending.clear()
        }
        scope.cancel()
    }
}
