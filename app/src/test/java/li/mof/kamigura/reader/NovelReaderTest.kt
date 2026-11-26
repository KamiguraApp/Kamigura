package li.mof.kamigura.reader

import li.mof.kamigura.reader.novel.novelResourceAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelReaderTest {
    @Test fun resourcesAreRestrictedToCurrentServerProxyPrefixAndChapter() {
        val base = "https://books.example/kavita"
        assertTrue(novelResourceAllowed("$base/api/Book/4/book-resources?file=font.woff&apiKey=secret", base, 4))
        listOf("https://evil.example/api/Book/4/book-resources", "$base/api/Book/5/book-resources",
            "$base/api/Reader/image", "file:///private", "http://books.example/kavita/api/Book/4/book-resources",
            "https://user:pass@books.example/kavita/api/Book/4/book-resources").forEach {
            assertFalse(it, novelResourceAllowed(it, base, 4))
        }
    }
}
