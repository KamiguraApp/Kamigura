package li.mof.kamigura.reader

import li.mof.kamigura.reader.novel.*
import org.junit.Assert.*
import org.junit.Test

class NovelLinksTest {
    private fun link(page: String? = null, part: String? = null, href: String? = null, note: Boolean = false) =
        novelLink(page, part, href, note, currentPage = 8, pageCount = 21)

    @Test fun internalLinksUseSpineNumbersAndTheContentsXPathEscaping() {
        assertEquals(NovelLink.Internal(10, null), link("10", href = "javascript:void(0)"))
        assertEquals(NovelLink.Internal(0, novelContentsAnchor("part'\"")), link("0", "part'\""))
        assertEquals(NovelLink.Internal(8, "id(\"local\")"), link(part = "local", href = "#local"))
        assertEquals(NovelLink.Internal(8, "id(\"a b\")"), link(href = "#a%20b"))
        assertEquals(NovelLink.Internal(8, null), link(href = "#"))
    }

    @Test fun invalidSpinesDoNotFallThroughToExternalNavigation() {
        for (page in listOf("", "-1", "21", "999999999999999999", "1.0", "+1", " 1", "1e1"))
            assertEquals(page, NovelLink.Ignored, link(page, href = "https://example.org"))
    }

    @Test fun onlyAbsoluteHttpAndHttpsCanLeaveTheReader() {
        for (url in listOf("https://example.org/book?a=1#part", "http://example.org", "HTTPS://example.org/"))
            assertTrue(url, link(href = url) is NovelLink.External)
        for (url in listOf("file:///sdcard/book", "content://book/1", "javascript:alert(1)", "data:text/html,a",
            "intent://book", "//example.org", "chapter.xhtml", "https:", "https:///book", "https://user:pass@example.org",
            "https://example.org\n", "https://example.org\\@other.org"))
            assertEquals(url, NovelLink.Ignored, link(href = url))
    }

    @Test fun footnotesAndUnsupportedAnchorsAreConsumedWithoutNavigation() {
        assertEquals(NovelLink.Ignored, link("2", "note", "https://example.org", note = true))
        assertEquals(NovelLink.Ignored, link(href = "#note", note = true))
        assertEquals(NovelLink.Ignored, link())
    }
}
