package li.mof.kamigura.reader.novel

import java.net.URI
import java.util.Locale

internal sealed interface NovelLink {
    data object None : NovelLink
    data object Ignored : NovelLink
    data class Internal(val page: Int, val anchor: String?) : NovelLink
    data class External(val url: String) : NovelLink
}

internal fun novelLink(page: String?, part: String?, href: String?, note: Boolean,
    currentPage: Int, pageCount: Int): NovelLink {
    if (note) return NovelLink.Ignored
    if (page != null) {
        val target = page.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        return if (target != null && target in 0 until pageCount)
            NovelLink.Internal(target, novelContentsAnchor(part.orEmpty())) else NovelLink.Ignored
    }
    if (href?.startsWith("#") == true) {
        val id = part ?: (runCatching { URI(href).fragment }.getOrNull() ?: return NovelLink.Ignored)
        return NovelLink.Internal(currentPage, novelContentsAnchor(id.orEmpty()))
    }
    // Never hand an EPUB-provided arbitrary scheme to another Android application.
    val uri = runCatching { URI(href.orEmpty()) }.getOrNull()
    return if (uri != null && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        NovelLink.External(uri.scheme.lowercase(Locale.ROOT) + uri.toASCIIString().substring(uri.scheme.length))
    else NovelLink.Ignored
}
