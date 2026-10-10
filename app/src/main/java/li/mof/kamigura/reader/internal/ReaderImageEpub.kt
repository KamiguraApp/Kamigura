package li.mof.kamigura.reader.internal

import android.graphics.drawable.ColorDrawable
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.normalizeKavitaBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xml.sax.Attributes
import org.xml.sax.ContentHandler

private const val ImageEpubPageConcurrency = 4
internal const val UnsupportedImageEpubMessage =
    "This EPUB is not supported yet. Only image-only manga EPUBs can be read."

internal class UnsupportedImageEpubException : IllegalArgumentException(UnsupportedImageEpubMessage)

internal data class EpubImagePageContent(
    val images: List<String>,
    val text: String,
    val unsupportedContent: Boolean = false
)

internal fun parseEpubImagePage(html: String): EpubImagePageContent {
    val images = mutableListOf<String>()
    var unsupported = false
    val ignoredTextStarts = mutableMapOf<String, Int>()
    var svgDepth = 0
    var inspectingAttributes = false
    val text = Html.fromHtml(
        html,
        Html.FROM_HTML_MODE_COMPACT,
        { source -> images += source.orEmpty(); ColorDrawable() },
        { opening, tag, output, reader ->
            // Html's public TagHandler supplies its SAX reader. Observe attributes of
            // unknown SVG <image> tags while retaining the standard HTML parser/formatter.
            if (!inspectingAttributes) {
                val delegate = reader.contentHandler
                reader.contentHandler = object : ContentHandler by delegate {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                        val name = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfter(':') }.lowercase()
                        if (svgDepth > 0) {
                            when (name) {
                                "image" -> images += (attributes.getValue("href")
                                    ?: attributes.getValue("xlink:href")
                                    ?: attributes.getValue("http://www.w3.org/1999/xlink", "href")).orEmpty()
                                "g", "title", "desc" -> Unit
                                else -> unsupported = true
                            }
                            if (!attributes.getValue("transform").isNullOrBlank() ||
                                !attributes.getValue("clip-path").isNullOrBlank() ||
                                !attributes.getValue("filter").isNullOrBlank()) unsupported = true
                        }
                        delegate.startElement(uri, localName, qName, attributes)
                    }
                }
                inspectingAttributes = true
            }
            val name = tag.lowercase()
            if (name == "style" || (svgDepth > 0 && name in setOf("title", "desc"))) {
                if (opening) ignoredTextStarts[name] = output.length else {
                    ignoredTextStarts.remove(name)?.let { output.delete(it, output.length) }
                }
            }
            when (name) {
                "svg" -> if (opening) svgDepth++ else svgDepth--
                "image" -> if (svgDepth <= 0) unsupported = true
                "script", "iframe", "object", "embed", "canvas", "math",
                "video", "audio", "form" -> unsupported = true
            }
        }
    ).toString()
    return EpubImagePageContent(images, text, unsupported || ignoredTextStarts.isNotEmpty() || svgDepth != 0)
}

internal fun epubPageImageUrl(content: EpubImagePageContent, baseUrl: String, chapterId: Int): String {
    if (content.unsupportedContent || content.images.size != 1 || content.text.any {
            !it.isWhitespace() && it != '\uFFFC' && it != '\u200B' && it != '\uFEFF'
        }) throw UnsupportedImageEpubException()

    val root = normalizeKavitaBaseUrl(baseUrl).toHttpUrl()
    val source = content.images.single().trim()
    val image = root.resolve(source) ?: throw UnsupportedImageEpubException()
    val expectedPath = root.encodedPath.trimEnd('/') + "/api/book/$chapterId/book-resources"
    // The image client sends authentication headers. Never accept an EPUB URL that could
    // send those credentials to another origin or a non-resource endpoint.
    if (source.isBlank() || image.scheme != root.scheme || image.host != root.host ||
        image.port != root.port || image.username.isNotEmpty() || image.password.isNotEmpty() ||
        !image.encodedPath.equals(expectedPath, ignoreCase = true) ||
        image.queryParameter("file").isNullOrBlank()
    ) throw UnsupportedImageEpubException()
    return image.toString()
}

internal suspend fun loadImageEpubPages(
    api: KavitaApi,
    chapterId: Int,
    pageCount: Int,
    baseUrl: String,
    parse: (String) -> EpubImagePageContent = ::parseEpubImagePage
): List<String> = coroutineScope {
    require(pageCount > 0) { "Chapter has no readable pages" }
    val semaphore = Semaphore(ImageEpubPageConcurrency)
    // Validate every spine before exposing the reader: a novel may also start with an
    // image-only cover. Only HTML is fetched here; image preloading remains unchanged.
    (0 until pageCount).map { page ->
        async {
            semaphore.withPermit {
                val html = api.bookPage(chapterId, page).use { it.string() }
                withContext(Dispatchers.Default) {
                    epubPageImageUrl(parse(html), baseUrl, chapterId)
                }
            }
        }
    }.awaitAll()
}
