package li.mof.kamigura

import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.compareNaturalFileNames
import li.mof.kamigura.reader.readerPrefetchPageIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun portraitPrefetchesOnePagePerTurn() {
        assertEquals(
            listOf(11, 12, 13, 14),
            readerPrefetchPageIndices(
                page = 10,
                pageCount = 30,
                portrait = true,
                pageDimensions = emptyMap(),
                turns = 4
            )
        )
    }

    @Test
    fun landscapePrefetchesFourFutureSpreads() {
        assertEquals(
            (1..8).toList(),
            readerPrefetchPageIndices(
                page = 0,
                pageCount = 30,
                portrait = false,
                pageDimensions = emptyMap(),
                turns = 4
            )
        )
    }

    @Test
    fun zeroTurnsDisablesPrefetch() {
        assertEquals(
            emptyList<Int>(),
            readerPrefetchPageIndices(
                page = 10,
                pageCount = 30,
                portrait = false,
                pageDimensions = emptyMap(),
                turns = 0
            )
        )
    }

    @Test
    fun prefetchClampsAtChapterEnd() {
        assertEquals(
            listOf(29),
            readerPrefetchPageIndices(
                page = 28,
                pageCount = 30,
                portrait = true,
                pageDimensions = emptyMap(),
                turns = 4
            )
        )
    }

    @Test
    fun naturalPageOrderMatchesReaderOrder() {
        val names = listOf(
            "chapter/page10.jpg",
            "chapter/page2.jpg",
            "chapter/page001.jpg",
            "chapter/page20.jpg"
        )

        assertEquals(
            listOf(
                "chapter/page001.jpg",
                "chapter/page2.jpg",
                "chapter/page10.jpg",
                "chapter/page20.jpg"
            ),
            names.sortedWith(::compareNaturalFileNames)
        )
    }

    @Test
    fun sessionKeySeparatesUsersOnTheSameServer() {
        val first = OfflineIssueRepository.sessionKey(
            KavitaSession(baseUrl = "https://example.test", username = "reader-a")
        )
        val second = OfflineIssueRepository.sessionKey(
            KavitaSession(baseUrl = "https://example.test/", username = "reader-b")
        )

        assertNotEquals(first, second)
    }

    @Test
    fun sessionKeyNormalizesEquivalentServerUrls() {
        val first = OfflineIssueRepository.sessionKey(
            KavitaSession(baseUrl = "https://example.test/api", username = "Reader")
        )
        val second = OfflineIssueRepository.sessionKey(
            KavitaSession(baseUrl = "https://example.test/", username = "reader")
        )

        assertEquals(first, second)
    }

    @Test
    fun offlineStorageCheckKeepsReserveAfterDownload() {
        val mib = 1024L * 1024L

        assertEquals(
            true,
            OfflineIssueRepository.hasEnoughOfflineStorage(
                expectedBytes = 181L * mib,
                availableBytes = 910L * mib
            )
        )
        assertEquals(
            false,
            OfflineIssueRepository.hasEnoughOfflineStorage(
                expectedBytes = 950L * mib,
                availableBytes = 910L * mib
            )
        )
    }
}
