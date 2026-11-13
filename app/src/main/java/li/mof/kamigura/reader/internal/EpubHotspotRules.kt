package li.mof.kamigura.reader.internal

internal const val EpubSvgNamespace = "http://www.w3.org/2000/svg"
internal const val EpubXlinkNamespace = "http://www.w3.org/1999/xlink"

internal fun epubHotspotReferences(attributes: Map<String, String>): List<String>? =
    listOfNotNull(attributes["href"], attributes["xlink:href"]).takeIf {
        it.isNotEmpty() && attributes.keys.all { key -> key in HotspotLinkAttributes }
    }

internal fun isTransparentEpubHotspotRect(attributes: Map<String, String>): Boolean =
    attributes.keys.all { it in HotspotRectAttributes } &&
        ZeroHotspotOpacity.matches(attributes["fill-opacity"].orEmpty()) &&
        attributes["stroke"].let { it == null || it == "none" } &&
        attributes["fill"].let { it == null || it == "none" }

internal fun hasLayoutOnlyEpubSvgAttributes(attributes: Map<String, String>, scoped: Boolean = false): Boolean =
    attributes.keys.all { it in HotspotSvgAttributes || scoped && it in KavitaSvgAttributes }

internal fun isLayoutOnlyEpubCss(value: String, scoped: Boolean = false): Boolean {
    val css = HotspotCssComment.replace(value, "").removePrefix("\uFEFF").trim()
    val selector = if (scoped) KavitaHotspotCssSelector else HotspotCssSelector
    var offset = HotspotCssCharset.matchAt(css, 0)?.range?.last?.plus(1) ?: 0
    while (offset < css.length) {
        if (css[offset].isWhitespace()) { offset++; continue }
        val rule = HotspotCssRule.matchAt(css, offset) ?: return false
        if (!rule.groupValues[1].split(',').all { selector.matches(it.trim()) } ||
            !isLayoutOnlyEpubStyle(rule.groupValues[2])) return false
        offset = rule.range.last + 1
    }
    return true
}

internal fun isLayoutOnlyEpubStyle(value: String): Boolean = value.split(';').all { declaration ->
    if (declaration.isBlank()) return@all true
    val content = declaration.substringAfter(':', "").trim()
    when (declaration.substringBefore(':').trim().lowercase()) {
        "margin", "padding", "font-size" -> content == "0"
        "user-select", "-webkit-user-select" -> content == "none"
        else -> false
    }
}

private val HotspotLinkAttributes = setOf("href", "xlink:href", "target")
private val HotspotRectAttributes = setOf("x", "y", "width", "height", "fill-opacity", "stroke", "fill")
private val HotspotSvgAttributes = setOf("version", "width", "height", "viewBox", "preserveAspectRatio", "x", "y", "id", "class", "style")
private val KavitaSvgAttributes = setOf("viewbox", "preserveaspectratio")
private val ZeroHotspotOpacity = Regex("0(?:\\.0+)?")
private val HotspotCssComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
private val HotspotCssCharset = Regex("@charset\\s+[\"']UTF-8[\"']\\s*;", RegexOption.IGNORE_CASE)
private val HotspotCssRule = Regex("([^{}]+)\\{([^{}]*)\\}")
private val HotspotCssSelector = Regex("html|body|svg|\\.main")
private val KavitaHotspotCssSelector = Regex("\\.book-content(?:\\s+(?:html|svg|\\.main|\\.book-content))?")
