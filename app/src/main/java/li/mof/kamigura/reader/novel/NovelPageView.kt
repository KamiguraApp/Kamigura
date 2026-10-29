package li.mof.kamigura.reader.novel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import li.mof.kamigura.NovelMargins
import li.mof.kamigura.normalizeKavitaBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import li.mof.kamigura.PersonalToCDto
import java.io.ByteArrayInputStream
import java.util.UUID

internal data class NovelPosition(val screen: Int, val count: Int, val vertical: Boolean, val anchor: String,
    val location: String? = null, val links: List<NovelLinkRegion> = emptyList(),
    val viewportWidthPx: Int = 0, val viewportHeightPx: Int = 0)

internal fun novelResourceAllowed(url: String, baseUrl: String, chapterId: Int): Boolean {
    val base = normalizeKavitaBaseUrl(baseUrl).toHttpUrl()
    val resource = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
    return resource.scheme == base.scheme && resource.host == base.host && resource.port == base.port &&
        resource.username.isEmpty() && resource.password.isEmpty() &&
        resource.encodedPath.equals(base.encodedPath.trimEnd('/') + "/api/Book/$chapterId/book-resources", true)
}

internal fun novelDocument(fragment: String, baseUrl: String, script: String, anchor: String?, last: Boolean,
    vertical: Boolean? = null, documentId: String = "", location: String? = null,
    preferences: NovelReaderPreferences = NovelReaderPreferences(), cutout: NovelPageInsets = NovelPageInsets()): String {
    val nonce = UUID.randomUUID().toString()
    val base = normalizeKavitaBaseUrl(baseUrl).toHttpUrl()
    val origin = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
    val csp = "default-src 'none'; style-src 'unsafe-inline' $origin; img-src $origin data:; " +
        "font-src $origin $NovelFontOrigin; script-src 'nonce-$nonce'; base-uri 'none'; form-action 'none'"
    val colors = novelReaderColors(preferences.background, preferences.usePureColors)
    return """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="$csp">
        <style>
        html,body { margin:0; padding:0; width:100%; height:100vh; background:${colors.backgroundCss}; color:${colors.textCss}; line-height:1.5; }
        #novel-viewport { position:fixed; inset:0; overflow:hidden; }
        .book-content { box-sizing:border-box; column-fill:auto; font-size:${preferences.fontSize * 0.16}px; font-family:'KamiguraBookDefault'; writing-mode:${if (preferences.vertical) "vertical-rl" else "horizontal-tb"}; }
        .book-content > div { margin:0; padding:0; }
        .book-content img,.book-content svg { max-width:100%; max-height:100%; object-fit:contain; }
        .book-content .visually-hidden { position:absolute !important; width:1px !important; height:1px !important;
            padding:0 !important; margin:-1px !important; overflow:hidden !important; clip:rect(0,0,0,0) !important;
            white-space:nowrap !important; border:0 !important; }
        #novel-viewport .book-content::before, #novel-viewport .book-content::after,
        #novel-viewport .book-content :not(svg):not(svg *)::before,
        #novel-viewport .book-content :not(svg):not(svg *)::after {
            color:var(--novel-text-color) !important; -webkit-text-fill-color:var(--novel-text-color) !important;
            text-decoration-color:var(--novel-text-color) !important; text-emphasis-color:var(--novel-text-color) !important;
            -webkit-text-stroke-color:var(--novel-text-color) !important;
            background-color:transparent !important; text-shadow:none !important;
        }
        </style></head><body><div id="novel-viewport"><div class="book-content">$fragment</div></div>
        <script nonce="$nonce">window.novelInitialAnchor=${JSONObject.quote(anchor.orEmpty())};window.novelOpenLast=$last;window.novelWritingMode=$vertical;window.novelFonts=${novelFontsJson(preferences.fonts)};window.novelDocumentId=${JSONObject.quote(documentId)};window.novelInitialLocation=${JSONObject.quote(location.orEmpty())};window.novelMargin=${preferences.margin};window.novelCutout=${cutout.json()};window.novelLineSpacing=${preferences.lineSpacing};window.novelFontSize=${preferences.fontSize};window.novelColors={background:'${colors.backgroundCss}',text:'${colors.textCss}'};
        $script</script></body></html>""".trimIndent()
}

/** Only our nonce-protected script runs. No native JavascriptInterface is exposed. */
@SuppressLint("SetJavaScriptEnabled")
internal class NovelPageView(context: Context) : WebView(context) {
    var onPosition: (NovelPosition) -> Unit = {}
    var onFailure: (String) -> Unit = {}
    private var generation = 0
    private var disposed = false
    private var colors = novelReaderColors(li.mof.kamigura.PageBackground.Paper, false)
    private val fonts = NovelFontStore(context)
    @Volatile private var allowedFonts = emptySet<String>()
    private var margin = NovelMargins.Normal.dp
    private var cutoutPixels = NovelPageInsets()
    private var pendingReflow: Runnable? = null

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
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) scheduleReflow()
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            cutoutPixels = (ViewCompat.getRootWindowInsets(this)?.displayCutout ?: insets.displayCutout)?.let {
                NovelPageInsets(it.safeInsetLeft.toFloat(), it.safeInsetTop.toFloat(),
                    it.safeInsetRight.toFloat(), it.safeInsetBottom.toFloat())
            } ?: NovelPageInsets()
            scheduleReflow()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun cutoutCss(): NovelPageInsets {
        val origin = IntArray(2); val window = IntArray(2)
        getLocationOnScreen(origin); rootView.getLocationOnScreen(window)
        return cutoutPixels.relativeToView(origin[0] - window[0], origin[1] - window[1],
            window[0] + rootView.width - origin[0] - width,
            window[1] + rootView.height - origin[1] - height, resources.displayMetrics.density)
    }

    private fun pageLayoutScript() = "window.novelReader.setPageLayout($margin, ${cutoutCss().json()})"

    private fun scheduleReflow() {
        pendingReflow?.let(::removeCallbacks)
        val token = generation
        pendingReflow = Runnable { if (token > 0 && !disposed) reflowAfterResize(token, width, height) }
        postDelayed(pendingReflow!!, 250)
    }

    fun setMargins(value: NovelMargins) {
        if (margin == value.dp) return
        margin = value.dp
        scheduleReflow()
    }

    private fun reflowAfterResize(token: Int, widthPx: Int, heightPx: Int, attempt: Int = 0) {
        if (disposed || token != generation) return
        // Chromium can publish its viewport after the native layout callback, without a resize event.
        evaluateJavascript("window.novelDocumentId === '$token' && window.novelReader && window.novelReader.ready ? " +
            "(Math.abs(innerWidth - $widthPx / devicePixelRatio) <= 1 && Math.abs(innerHeight - $heightPx / devicePixelRatio) <= 1 ? " +
            "${pageLayoutScript()} : 'resize-pending') : null") { value ->
            if (disposed || token != generation) return@evaluateJavascript
            if (value.startsWith("{")) report(value)
            else if (value == "\"resize-pending\"" && attempt < 20) {
                postDelayed({ reflowAfterResize(token, widthPx, heightPx, attempt + 1) }, 100)
            }
        }
    }

    fun open(fragment: String, baseUrl: String, chapterId: Int, anchor: String?, last: Boolean, vertical: Boolean? = null,
        location: String? = null, preferences: NovelReaderPreferences = NovelReaderPreferences()) {
        setColors(novelReaderColors(preferences.background, preferences.usePureColors))
        margin = preferences.margin
        val token = ++generation
        allowedFonts = novelFontIds(preferences.fonts)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                fonts.response(request.url.toString(), allowedFonts)?.let { return it }
                if (novelResourceAllowed(request.url.toString(), baseUrl, chapterId)) return null
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView, url: String) { awaitLayout(token, 0) }
        }
        val script = context.assets.open("novel-reader.js").bufferedReader().use { it.readText() }
        loadDataWithBaseURL(normalizeKavitaBaseUrl(baseUrl) + "/", novelDocument(fragment, baseUrl, script, anchor, last, vertical, token.toString(), location, preferences, cutoutCss()),
            "text/html", "UTF-8", null)
    }

    private fun awaitLayout(token: Int, attempt: Int) {
        if (disposed || token != generation) return
        evaluateJavascript("window.novelDocumentId === '$token' && window.novelReader && window.novelReader.ready ? ${pageLayoutScript()} : null") { value ->
            if (disposed || token != generation) return@evaluateJavascript
            if (value.startsWith("{")) {
                setColors(colors)
                report(value)
            } else if (attempt < 200) {
                postDelayed({ awaitLayout(token, attempt + 1) }, 100)
            } else onFailure("Could not lay out this EPUB.")
        }
    }

    private fun report(value: String) {
        runCatching {
            val json = JSONObject(value)
            json.toNovelPosition(width, height)
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

    fun setFontSize(percent: Int) {
        val token = generation
        evaluateJavascript("window.novelReader.setFontSize(${percent.coerceIn(1, 1000)})") {
            if (!disposed && token == generation) report(it)
        }
    }

    fun setFonts(settings: li.mof.kamigura.NovelSettings) {
        val token = generation
        allowedFonts = novelFontIds(settings)
        evaluateJavascript("window.novelFontUpdate = null; window.novelReader.setFonts(${novelFontsJson(settings)})" +
            ".then(value => window.novelFontUpdate = value).catch(() => window.novelFontUpdate = {error:true})", null)
        fun poll(attempt: Int) {
            if (disposed || token != generation) return
            evaluateJavascript("window.novelFontUpdate") { value ->
                if (disposed || token != generation) return@evaluateJavascript
                if (value.contains("\"error\"")) onFailure("Could not load the novel font.")
                else if (value.startsWith("{")) report(value)
                else if (attempt < 200) postDelayed({ poll(attempt + 1) }, 100)
                else onFailure("Could not load the novel font.")
            }
        }
        poll(0)
    }

    fun setColors(value: NovelReaderColors) {
        colors = value
        setBackgroundColor(value.background)
        // Paint-only update: no reflow, navigation or progress callback.
        evaluateJavascript("window.novelReader && window.novelReader.setColors('${value.backgroundCss}','${value.textCss}')", null)
    }

    fun beginSectionLoad() { generation++; stopLoading() }

    fun captureFrame(): Bitmap? {
        if (disposed || width <= 0 || height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        }.getOrNull()
    }

    fun bookmarkContext(items: List<PersonalToCDto>, callback: (NovelBookmarkContext?) -> Unit) {
        val token = generation
        val paths = JSONArray(items.map { it.bookScrollId })
        evaluateJavascript("window.novelReader && window.novelReader.ready ? window.novelReader.bookmarkContext($paths) : null") { value ->
            if (disposed || token != generation) { callback(null); return@evaluateJavascript }
            callback(runCatching {
                val json = JSONObject(value)
                val visible = json.getJSONArray("visible")
                NovelBookmarkContext(json.getString("anchor"), json.getString("title"),
                    (0 until visible.length()).mapNotNull { items.getOrNull(visible.getInt(it))?.id }.toSet())
            }.getOrNull())
        }
    }

    fun jumpToBookmark(anchor: String?, callback: (String?) -> Unit) {
        val token = generation
        evaluateJavascript("window.novelReader.jump(${JSONObject.quote(anchor.orEmpty())})") { value ->
            if (disposed || token != generation) { callback("The EPUB section changed."); return@evaluateJavascript }
            val result = runCatching { JSONObject(value) }.getOrNull()
            when {
                result == null -> callback("Could not open this reading position.")
                result.has("error") -> callback(result.getString("error"))
                else -> { report(value); callback(null) }
            }
        }
    }

    fun contentsParts(parts: List<String>, callback: (Set<String>?) -> Unit) {
        val token = generation
        evaluateJavascript("window.novelReader && window.novelReader.ready ? window.novelReader.contentsParts(${JSONArray(parts)}) : null") { value ->
            if (disposed || token != generation) { callback(null); return@evaluateJavascript }
            callback(runCatching {
                val array = JSONArray(value)
                (0 until array.length()).map { array.getString(it) }.toSet()
            }.getOrNull())
        }
    }

    fun position(callback: (NovelPosition?) -> Unit) {
        val token = generation
        evaluateJavascript("window.novelReader && window.novelReader.ready ? window.novelReader.state() : null") { value ->
            if (disposed || token != generation) { callback(null); return@evaluateJavascript }
            val position = runCatching {
                val json = JSONObject(value)
                json.toNovelPosition(width, height)
            }.getOrNull()
            callback(position)
        }
    }

    fun dispose() { disposed = true; generation++; pendingReflow?.let(::removeCallbacks); stopLoading(); destroy() }
}

private fun NovelPageInsets.json() = JSONObject().put("left", left).put("top", top)
    .put("right", right).put("bottom", bottom).toString()

private fun JSONObject.toNovelPosition(width: Int, height: Int): NovelPosition {
    val items = optJSONArray("links")
    val links = (0 until (items?.length() ?: 0)).mapNotNull { index -> runCatching {
        val link = items!!.getJSONObject(index)
        val bounds = listOf("left", "top", "right", "bottom").map { link.getDouble(it).toFloat() }
        if (bounds.any { !it.isFinite() || it !in 0f..1f } || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) null
        else NovelLinkRegion(Rect(bounds[0], bounds[1], bounds[2], bounds[3]),
            link.optString("page").takeUnless { link.isNull("page") },
            link.optString("part").takeUnless { link.isNull("part") },
            link.optString("href").takeUnless { link.isNull("href") }, link.optBoolean("note"))
    }.getOrNull() }
    return NovelPosition(getInt("screen"), getInt("count"), getBoolean("vertical"), getString("anchor"),
        optString("location").takeIf { it.isNotBlank() && it != "null" }, links, width, height)
}
