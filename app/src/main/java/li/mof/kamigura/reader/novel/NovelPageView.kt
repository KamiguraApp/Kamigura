package li.mof.kamigura.reader.novel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import li.mof.kamigura.normalizeKavitaBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

internal data class NovelPosition(val screen: Int, val count: Int, val vertical: Boolean, val anchor: String)

internal fun novelResourceAllowed(url: String, baseUrl: String, chapterId: Int): Boolean {
    val base = normalizeKavitaBaseUrl(baseUrl).toHttpUrl()
    val resource = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    return resource.scheme == base.scheme && resource.host == base.host && resource.port == base.port &&
        resource.username.isEmpty() && resource.password.isEmpty() &&
        resource.encodedPath.equals(base.encodedPath.trimEnd('/') + "/api/Book/$chapterId/book-resources", true)
}

internal fun novelDocument(fragment: String, baseUrl: String, script: String, anchor: String?, last: Boolean,
    vertical: Boolean? = null, documentId: String = ""): String {
    val nonce = UUID.randomUUID().toString()
    val base = normalizeKavitaBaseUrl(baseUrl).toHttpUrl()
    val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    val csp = "default-src 'none'; style-src 'unsafe-inline' $origin; img-src $origin data:; " +
        "font-src $origin; script-src 'nonce-$nonce'; base-uri 'none'; form-action 'none'"
    return """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="$csp">
        <style>
        html,body { margin:0; padding:0; width:100%; height:100vh; background:#FAF7F2; color:#171717; }
        #novel-viewport { position:fixed; inset:0; overflow:hidden; }
        .book-content { box-sizing:border-box; padding:24px; column-gap:48px; column-fill:auto; font-size:18px; line-height:1.8; }
        .book-content > div { margin:0; padding:0; }
        .book-content img,.book-content svg { max-width:100%; max-height:100%; object-fit:contain; }
        </style></head><body><div id="novel-viewport"><div class="book-content">$fragment</div></div>
        <script nonce="$nonce">window.novelInitialAnchor=${JSONObject.quote(anchor.orEmpty())};window.novelOpenLast=$last;window.novelWritingMode=$vertical;window.novelDocumentId=${JSONObject.quote(documentId)};
        $script</script></body></html>""".trimIndent()
}

/** Only our nonce-protected script runs. No native JavascriptInterface is exposed. */
@SuppressLint("SetJavaScriptEnabled")
internal class NovelPageView(context: Context) : WebView(context) {
    var onPosition: (NovelPosition) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    private var generation = 0
    private var disposed = false

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Color.rgb(250, 247, 242))
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                val token = generation
                postDelayed({
                    if (!disposed && token == generation && token > 0) position { it?.let(onPosition) }
                }, 250)
            }
        }
    }

    fun open(fragment: String, baseUrl: String, chapterId: Int, anchor: String?, last: Boolean, vertical: Boolean? = null) {
        val token = ++generation
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (novelResourceAllowed(request.url.toString(), baseUrl, chapterId)) return null
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView, url: String) { awaitLayout(token, 0) }
        }
        val script = context.assets.open("novel-reader.js").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(normalizeKavitaBaseUrl(baseUrl) + "/", novelDocument(fragment, baseUrl, script, anchor, last, vertical, token.toString()),
            "text/html", "UTF-8", null)
    }

    private fun awaitLayout(token: Int, attempt: Int) {
        if (disposed || token != generation) return
        evaluateJavascript("window.novelDocumentId === '$token' && window.novelReader && window.novelReader.ready ? window.novelReader.state() : null") { value ->
            if (disposed || token != generation) return@evaluateJavascript
            if (value.startsWith("{")) report(value) else if (attempt < 200) {
                postDelayed({ awaitLayout(token, attempt + 1) }, 100)
            } else onFailure("Could not lay out this EPUB.")
        }
    }

    private fun report(value: String) {
        runCatching {
            val json = JSONObject(value)
            NovelPosition(json.getInt("screen"), json.getInt("count"), json.getBoolean("vertical"), json.getString("anchor"))
        }.onSuccess(onPosition).onFailure { onFailure("Could not read the EPUB position.") }
    }

    fun turn(delta: Int, onBoundary: (Int) -> Unit) {
        val token = generation
        evaluateJavascript("window.novelReader.turn($delta)") { value ->
            if (disposed || token != generation) return@evaluateJavascript
            val result = runCatching { JSONObject(value) }.getOrNull() ?: return@evaluateJavascript
            if (result.has("boundary")) onBoundary(result.getInt("boundary")) else report(value)
        }
    }

    fun setVertical(vertical: Boolean) {
        val token = generation
        evaluateJavascript("window.novelReader.setVertical($vertical)") {
            if (!disposed && token == generation) report(it)
        }
    }

    fun beginSectionLoad() { generation++; stopLoading() }

    fun position(callback: (NovelPosition?) -> Unit) {
        evaluateJavascript("window.novelReader && window.novelReader.ready ? window.novelReader.state() : null") { value ->
            val position = runCatching {
                val json = JSONObject(value)
                NovelPosition(json.getInt("screen"), json.getInt("count"), json.getBoolean("vertical"), json.getString("anchor"))
            }.getOrNull()
            callback(position)
        }
    }

    fun dispose() { disposed = true; generation++; stopLoading(); destroy() }
}
