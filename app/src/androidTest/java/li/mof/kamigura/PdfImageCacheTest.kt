package li.mof.kamigura

import androidx.test.ext.junit.runners.AndroidJUnit4
import li.mof.kamigura.cache.stableKavitaImageCacheKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImageCacheTest {
    @Test
    fun ordinaryPagesKeepTheirKeysAndPdfKeysStillIgnoreCredentials() {
        val oldUrl = "https://example.test/api/Reader/image?chapterId=42&apiKey=old&page=7"
        val oldKey = stableKavitaImageCacheKey("profile", oldUrl)
        assertNotNull(oldKey)
        assertEquals(oldKey, stableKavitaImageCacheKey("profile",
            withPdfExtraction(oldUrl, extractPdf = false)))
        val pdfUrl = withPdfExtraction(oldUrl, extractPdf = true)
        val pdfKey = stableKavitaImageCacheKey("profile", pdfUrl)
        assertNotNull(pdfKey)
        assertNotEquals(oldKey, pdfKey)
        assertEquals(pdfKey, stableKavitaImageCacheKey("profile",
            pdfUrl.replace("apiKey=old", "apiKey=new")))
    }
}
