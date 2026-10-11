package li.mof.kamigura.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

internal const val UnsupportedOfflineEpubMessage =
    "This EPUB cannot be read offline. Only image-only manga EPUBs are supported."

private fun unsupportedEpub(): Nothing = throw UnsupportedOfflineFormatException(UnsupportedOfflineEpubMessage)

/** One archive image per spine, including the title-page cover. Never sort or compact. */
internal fun offlineEpubImageEntries(zip: ZipFile): List<String> {
    val container = readEpubXml(zip, "META-INF/container.xml")
    val rootfiles = container.children.singleOrNull { it.name == "rootfiles" } ?: unsupportedEpub()
    val rootfile = rootfiles.children.firstOrNull {
        it.name == "rootfile" && it.attributes["media-type"] == "application/oebps-package+xml"
    } ?: unsupportedEpub()
    val opfPath = resolveEpubEntry("", rootfile.attributes["full-path"].orEmpty())
    val opf = readEpubXml(zip, opfPath)
    if (opf.name != "package") unsupportedEpub()
    val manifest = opf.children.singleOrNull { it.name == "manifest" } ?: unsupportedEpub()
    val items = manifest.children.filter { it.name == "item" }
    val byId = items.associateBy { it.attributes["id"].orEmpty() }
    if (byId.size != items.size || "" in byId) unsupportedEpub()
    val spine = opf.children.singleOrNull { it.name == "spine" } ?: unsupportedEpub()
    val references = spine.children.filter { it.name == "itemref" }
    if (references.isEmpty()) unsupportedEpub()
    return references.map { reference ->
        val item = byId[reference.attributes["idref"]] ?: unsupportedEpub()
        if (item.attributes["media-type"] != "application/xhtml+xml") unsupportedEpub()
        val pagePath = resolveEpubEntry(opfPath, item.attributes["href"].orEmpty())
        val document = readEpubXml(zip, pagePath)
        if (document.name != "html") unsupportedEpub()
        val body = document.children.singleOrNull { it.name == "body" } ?: unsupportedEpub()
        val images = mutableListOf<String>()
        collectEpubImages(body, images)
        if (images.size != 1) unsupportedEpub()
        val imagePath = resolveEpubEntry(pagePath, images.single())
        val image = zip.getEntry(imagePath) ?: unsupportedEpub()
        if (image.isDirectory) unsupportedEpub()
        imagePath
    }
}

/** URI decoding precedes segment normalization so encoded traversal cannot escape the ZIP root. */
internal fun resolveEpubEntry(baseEntry: String, reference: String): String {
    val uri = try { URI(reference.trim().replace(" ", "%20")) } catch (_: Exception) { unsupportedEpub() }
    val path = uri.path.orEmpty()
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null ||
        path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path
    ) unsupportedEpub()
    val segments = baseEntry.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) unsupportedEpub() else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/").takeIf { it.isNotBlank() } ?: unsupportedEpub()
}

private data class EpubXmlNode(
    val name: String,
    val namespace: String,
    val attributes: Map<String, String>,
    val children: MutableList<EpubXmlNode> = mutableListOf(),
    val text: StringBuilder = StringBuilder()
)

private fun readEpubXml(zip: ZipFile, path: String): EpubXmlNode {
    val entry = zip.getEntry(path) ?: unsupportedEpub()
    if (entry.isDirectory) unsupportedEpub()
    val bytes = zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MaxEpubXmlBytes) unsupportedEpub()
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    var root: EpubXmlNode? = null
    val stack = mutableListOf<EpubXmlNode>()
    val handler = object : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            // This subset never needs DTDs; reject them before entity expansion or external I/O.
            unsupportedEpub()
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (stack.size >= 64 || !attributes.getValue("http://www.w3.org/XML/1998/namespace", "base").isNullOrBlank()) {
                unsupportedEpub()
            }
            val values = (0 until attributes.length).associate {
                attributes.getQName(it) to attributes.getValue(it)
            }.toMutableMap()
            attributes.getValue("http://www.w3.org/1999/xlink", "href")?.let { values["xlink:href"] = it }
            val node = EpubXmlNode(localName, uri, values)
            if (stack.isEmpty()) root = node else stack.last().children += node
            stack += node
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.text?.append(chars, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            stack.removeAt(stack.lastIndex)
        }

        override fun error(exception: org.xml.sax.SAXParseException) { throw exception }
        override fun fatalError(exception: org.xml.sax.SAXParseException) { throw exception }
    }
    try {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    } catch (_: SAXException) {
        unsupportedEpub()
    }
    return root ?: unsupportedEpub()
}

private fun collectEpubImages(node: EpubXmlNode, images: MutableList<String>, inSvg: Boolean = false) {
    val svg = inSvg || node.name == "svg"
    if (svg && node.name !in setOf("svg", "g", "image", "title", "desc")) unsupportedEpub()
    if (node.name in setOf("script", "iframe", "object", "embed", "canvas", "math", "video", "audio", "form")) {
        unsupportedEpub()
    }
    if (node.name == "style" || (svg && node.name in setOf("title", "desc"))) return
    if (node.text.any { !it.isWhitespace() && it != '\u200B' && it != '\uFEFF' }) unsupportedEpub()
    if (svg && (node.attributes["transform"] != null || node.attributes["clip-path"] != null || node.attributes["filter"] != null)) {
        unsupportedEpub()
    }
    when (node.name) {
        "img" -> {
            if (node.namespace != "http://www.w3.org/1999/xhtml") unsupportedEpub()
            images += node.attributes["src"].orEmpty()
        }
        "image" -> {
            if (!svg || node.namespace != "http://www.w3.org/2000/svg") unsupportedEpub()
            images += (node.attributes["href"] ?: node.attributes["xlink:href"]).orEmpty()
        }
    }
    node.children.forEach { collectEpubImages(it, images, svg) }
}

private const val MaxEpubXmlBytes = 2 * 1024 * 1024
