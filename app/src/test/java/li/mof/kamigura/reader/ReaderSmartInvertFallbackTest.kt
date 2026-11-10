package li.mof.kamigura.reader

import li.mof.kamigura.reader.internal.ReaderInvertCacheKey
import li.mof.kamigura.reader.internal.readerSmartInvertFallback
import org.junit.Assert.*
import org.junit.Test

class ReaderSmartInvertFallbackTest {
    private val pages = List(8) { "current-chapter-$it" }
    private fun key(page: Int, threshold: Float = .5f) = ReaderInvertCacheKey(pages[page], threshold)
    private fun fallback(page: Int, decisions: Map<ReaderInvertCacheKey, Boolean>) =
        readerSmartInvertFallback(page, pages.size, pages::get, .5f, decisions)

    @Test fun noKnownDecisionDefaultsToInvertedWithoutWritingTheCache() {
        val decisions = mutableMapOf<ReaderInvertCacheKey, Boolean>()
        assertTrue(fallback(3, decisions))
        assertTrue(decisions.isEmpty())
    }

    @Test fun nearestDecisionCanComeFromEitherSideIncludingFalse() {
        assertFalse(fallback(3, mapOf(key(2) to false, key(5) to true)))
        assertTrue(fallback(3, mapOf(key(0) to false, key(4) to true)))
    }

    @Test fun equalDistancePrefersThePrecedingPage() {
        assertFalse(fallback(3, linkedMapOf(key(4) to true, key(2) to false)))
    }

    @Test fun otherChaptersAndThresholdsCannotSupplyFallback() {
        assertTrue(fallback(3, mapOf(ReaderInvertCacheKey("other-chapter-2", .5f) to false, key(2, .8f) to false)))
    }

    @Test fun missingModelsAndChapterEdgesAreSafe() {
        assertFalse(fallback(0, mapOf(key(7) to false)))
        assertTrue(readerSmartInvertFallback(0, 3, { null }, .5f, mapOf(key(0) to false)))
        assertTrue(readerSmartInvertFallback(0, 0, pages::get, .5f, emptyMap()))
    }
}
