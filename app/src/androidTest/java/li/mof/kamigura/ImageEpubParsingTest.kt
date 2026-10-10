package li.mof.kamigura

import androidx.test.ext.junit.runners.AndroidJUnit4
import li.mof.kamigura.reader.internal.UnsupportedImageEpubException
import li.mof.kamigura.reader.internal.epubPageImageUrl
import li.mof.kamigura.reader.internal.parseEpubImagePage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageEpubParsingTest {
    private val root = "https://books.example/kavita"
    private val source = "//books.example/kavita/api/book/42/book-resources?apiKey=key&file=page.jpg"

    @Test
    fun kavitaFragmentIgnoresScopedStylesAndResolvesItsSingleImage() {
        val html = """
            <div class="calibre book-content">
              <style>.book-content p { margin: 0; } .book-content img { width: 100%; }</style>
              <div class="kavita-scale-width-container">
                <img class="kavita-scale-width" src="${source.replace("&", "&amp;")}" alt="Page 1">
              </div>
            </div>
        """.trimIndent()
        val content = parseEpubImagePage(html)
        assertEquals(listOf(source), content.images)
        assertEquals("https:$source", epubPageImageUrl(content, root, 42))
    }

    @Test
    fun htmlEntitiesQuotesAndUnclosedImageTagsAreParsedWithoutRegex() {
        val html = "<DIV><IMG SRC='$source'>&nbsp;</DIV>"
        assertEquals("https:$source", epubPageImageUrl(parseEpubImagePage(html), root, 42))
        val escaped = source.replace("page.jpg", "page%20one.jpg").replace("&", "&#38;")
        assertEquals("https:${source.replace("page.jpg", "page%20one.jpg")}",
            epubPageImageUrl(parseEpubImagePage("<img src=\"$escaped\" />"), root, 42))
    }

    @Test
    fun novelProseRubyAndMultipleImagesAreNeverReducedToOnePicture() {
        for (html in listOf("<p>Novel prose</p><img src='$source'>",
            "<p><ruby>Text<rt>reading</rt></ruby></p><img src='$source'>",
            "<img src='$source'><img src='$source'>", "<div> </div>")) {
            assertUnsupported(html)
        }
    }

    @Test
    fun unsupportedVisualAndActiveContentIsNotDiscardedSilently() {
        for (html in listOf("<svg><image href='$source'/><path d='M0,0 L10,10'/></svg>",
            "<svg><image href='$source'/><text>Novel text</text></svg>",
            "<svg><image href='$source' transform='rotate(90)'/></svg>",
            "<svg><image href='$source'/><image href='$source'/></svg>",
            "<img src='$source'><script>doSomething()</script>",
            "<iframe src='https://other.example'></iframe><img src='$source'>",
            "<canvas></canvas><img src='$source'>")) {
            assertUnsupported(html)
        }
    }

    @Test
    fun imageOnlySvgCoverUsesItsEmbeddedImageRatherThanRejectingTheManga() {
        val html = """
            <div><style>@page { margin: 0; } .book-content { text-align: center; }</style>
              <div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                width="100%" height="100%" viewbox="0 0 1070 1600" preserveaspectratio="none">
                <title>Cover</title><desc>A cover image</desc>
                <image width="1070" height="1600" xlink:href="${source.replace("&", "&amp;")}"></image>
              </svg></div>
            </div>
        """.trimIndent()
        assertEquals("https:$source", epubPageImageUrl(parseEpubImagePage(html), root, 42))
        assertEquals("https:$source",
            epubPageImageUrl(parseEpubImagePage("<svg><image href='$source'/></svg>"), root, 42))
    }

    @Test
    fun textAfterOrBetweenStylesIsStillVisibleContent() {
        assertUnsupported("<style>p { color: black; }</style><p>Novel text</p><img src='$source'>")
        assertUnsupported("<p>Novel text</p><style>p { color: black; }</style><img src='$source'>")
        val html = "<style>p { color: black; }</style><img src='$source'><style>img { width: 100%; }</style>"
        assertEquals("https:$source", epubPageImageUrl(parseEpubImagePage(html), root, 42))
    }

    private fun assertUnsupported(html: String) {
        try {
            epubPageImageUrl(parseEpubImagePage(html), root, 42)
            fail("Unsupported content must not be dropped")
        } catch (_: UnsupportedImageEpubException) { }
    }
}
