package li.mof.kamigura.reader.novel

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import li.mof.kamigura.NovelFont
import li.mof.kamigura.NovelSettings
import org.json.JSONObject

internal const val NovelFontOrigin = "https://kamigura.invalid/novel-fonts/"
private val FontId = Regex("[0-9a-f-]{36}\\.(ttf|otf|ttc)")

internal class NovelFontStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "novel-fonts")

    suspend fun import(uri: Uri): NovelFont = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("ttf", "otf", "ttc")) { "Choose a TTF, OTF or TTC font file." }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create font storage." }
        val file = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 64L * 1024 * 1024) { "Choose a font smaller than 64 MB." }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("Could not open this font file.")
            try { Typeface.Builder(file).build() }
            catch (_: Exception) { throw IOException("This is not a supported font file.") }
            val font = NovelFont(file.name, name)
            val supported = withContext(Dispatchers.Main) { validate(font) }
            if (!supported) throw IOException(if (extension == "ttc")
                "This WebView cannot load this TTC collection. Choose a TTF or OTF font."
                else "This WebView cannot load this font. Choose another TTF or OTF file.")
            font
        } catch (error: Throwable) { file.delete(); throw error }
    }

    fun remove(font: NovelFont) { font.id?.takeIf(FontId::matches)?.let { File(directory, it).delete() } }

    fun response(url: String, allowed: Set<String>): WebResourceResponse? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "kamigura.invalid" || uri.port != -1 ||
            uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        val id = uri.path?.removePrefix("/novel-fonts/") ?: return null
        if (!FontId.matches(id) || id !in allowed || uri.path != "/novel-fonts/$id") return null
        val file = File(directory, id)
        if (!file.isFile) return null
        val input = runCatching { file.inputStream() }.getOrNull() ?: return null
        return WebResourceResponse(when (file.extension) { "otf" -> "font/otf"; "ttc" -> "font/collection"; else -> "font/ttf" },
            null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), input)
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun validate(font: NovelFont): Boolean = suspendCancellableCoroutine { continuation ->
        val view = WebView(context)
        // This validation view is never attached, so View.post cannot drive its timeout/poll.
        val handler = Handler(Looper.getMainLooper())
        view.settings.javaScriptEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        var finished = false
        fun finish(success: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            view.stopLoading(); view.destroy()
            if (continuation.isActive) continuation.resume(success)
        }
        fun poll(attempt: Int) {
            if (finished) return
            view.evaluateJavascript("window.fontLoaded") { result ->
                when {
                    result == "true" -> finish(true)
                    result == "false" || attempt >= 150 -> finish(false)
                    else -> handler.postDelayed({ poll(attempt + 1) }, 100)
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                response(request.url.toString(), setOf(font.id!!)) ?: WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
            override fun onPageFinished(view: WebView, url: String) { poll(0) }
        }
        continuation.invokeOnCancellation { handler.post { finish(false) } }
        handler.postDelayed({ finish(false) }, 15_000)
        view.loadDataWithBaseURL("https://kamigura.invalid/", """<html><body><script>
            new FontFace('ImportCheck','url(${NovelFontOrigin}${font.id})').load().then(
            ()=>window.fontLoaded=true,()=>window.fontLoaded=false);
            </script></body></html>""", "text/html", "UTF-8", null)
    }
}

internal fun novelFontIds(settings: NovelSettings): Set<String> =
    listOfNotNull(settings.mincho.id, settings.gothic.id).filter(FontId::matches).toSet()

internal fun novelFontsJson(settings: NovelSettings): String = JSONObject().apply {
    put("defaultSlot", settings.defaultFont.name)
    put("mincho", settings.mincho.id?.takeIf(FontId::matches)?.let { NovelFontOrigin + it } ?: JSONObject.NULL)
    put("gothic", settings.gothic.id?.takeIf(FontId::matches)?.let { NovelFontOrigin + it } ?: JSONObject.NULL)
}.toString()
