package li.mof.kamigura

import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.download.UnsupportedOfflineFormatException
import li.mof.kamigura.download.offlineEpubImageEntries
import li.mof.kamigura.reader.internal.UnsupportedImageEpubException
import li.mof.kamigura.reader.internal.epubPageImageUrl
import li.mof.kamigura.reader.internal.parseEpubImagePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class LinkedContentsEpubParsingTest {
    private val root = "https://books.example/kavita"
    private val source = "$root/api/book/42/book-resources?apiKey=key&file=z.png"
    private val rect = "<rect fill-opacity='0.0' x='20' y='30' width='100' height='120'/>"
    private val link = "<a xlink:href='page2.xhtml' target='_top'>$rect</a>"
    private val style = "<style>@charset \"UTF-8\";html,body{margin:0;padding:0;font-size:0}svg{margin:0;padding:0}</style>"

    @Test fun transparentLinksWithLayoutCssAreAcceptedByBothParsers() {
        val body = svg(link.repeat(16))
        assertEquals(listOf("OPS/cover.png", "OPS/z.png", "OPS/a.png"), offline(body))
        assertEquals(source, online(body))
        assertEquals(source, online(body, source.replace("&", "&amp;")))
    }

    @Test fun paintableOrUnknownMarkupIsRejectedByBothParsers() {
        val cases = listOf(
            svg(link.replace(rect, "<rect width='20' height='20'/>")),
            svg(link.replace("0.0", "0.1")),
            svg(link.replace("0.0", "1e-999")),
            svg(link.replace("width='100'", "stroke='red' width='100'")),
            svg(link.replace("width='100'", "style='fill-opacity:1' width='100'")),
            svg(link.replace("width='100'", "filter='url(#paint)' width='100'")),
            svg(link.replace(rect, "<rect fill-opacity='0'><animate attributeName='fill-opacity' to='1'/></rect>")),
            svg(link.replace(rect, "<image xlink:href='z.png'/>")),
            svg(link.replace(rect, "<g>$rect</g>")),
            svg(link.replace(rect, "")),
            svg(link.replace(rect, "$rect visible text")),
            svg(link.replace("target=", "onclick='paint()' target=")),
            svg(link.replace("page2.xhtml", "https://example.com/page2.xhtml")),
            svg(link, "stroke='red'"),
            svg("<g stroke='red'>$link</g>"),
            svg("$link<path d='M0,0L10,10'/>"),
            svg(link, css = "<style>svg{stroke:red}</style>"),
            svg(link, css = "<style>rect{fill-opacity:1}</style>"),
            svg(link, css = "<style>@import 'other.css';</style>"),
            svg(link, css = "<link rel='stylesheet' href='missing.css'/>")
        )
        for (body in cases) {
            assertThrows(body, UnsupportedOfflineFormatException::class.java) { offline(body) }
            assertThrows(body, UnsupportedImageEpubException::class.java) { online(body) }
        }
    }

    @Test fun onlineHotspotsDoNotBypassImageCredentialGuard() {
        for (url in listOf(source.replace("books.example", "other.example"),
            source.replace("/42/", "/43/"), source.replace("book-resources", "book-page"))) {
            assertThrows(UnsupportedImageEpubException::class.java) { online(svg(link), url) }
        }
    }

    @Test fun onlineHotspotsRejectHtmlRepairAndNonSvgNamespaces() {
        val body = svg(link)
        for (invalid in listOf(body.replace("</a>", ""), body.replace("fill-opacity='0.0'",
            "fill-opacity='1' fill-opacity='0.0'"), body.replace("<rect ", "<rect xmlns='urn:other' "),
            svg(link, css = "<style><!--rect{fill-opacity:1}--></style>"),
            svg(link, css = "<style><![CDATA[rect{fill-opacity:1}]]></style>"),
            svg(link, css = "<style>svg{&#47;&#42;stroke:red;&#42;&#47;}</style>"),
            "<?xml-stylesheet href='other.css'?>$body")) {
            assertThrows(UnsupportedImageEpubException::class.java) { online(invalid) }
        }
    }

    private fun svg(content: String, attributes: String = "", css: String = style) =
        "$css<div class='main'><svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 1350 1920' $attributes>" +
            "<image width='1350' height='1920' xlink:href='z.png'/>$content</svg></div>"

    private fun online(body: String, url: String = source): String {
        val processed = body.replace("html,body{", ".book-content html,.book-content .book-content{")
            .replace("svg{", ".book-content svg{")
            .replace("viewBox=", "class='kavita-scale-width-container' viewbox=")
            .replace("xlink:href='z.png'", "class='kavita-scale-width' xlink:href='$url'")
            .replace(Regex("<(image|rect)([^>]*)/>"), "<$1$2></$1>")
        return epubPageImageUrl(parseEpubImagePage("<div class=' '>$processed</div>"), root, 42)
    }

    private fun offline(body: String): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("linked-contents-parity-", ".epub", context.cacheDir)
        try {
            file.writeBytes(epubFixture(pageTransform = { index, page -> if (index == 1) body else page }))
            return ZipFile(file).use(::offlineEpubImageEntries)
        } finally {
            file.delete()
        }
    }
}
