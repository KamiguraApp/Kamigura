package li.mof.kamigura.library

import li.mof.kamigura.download.OfflineIssueRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueuePresentationTest {
    @Test
    fun unknownSizeReportsTransferredBytesWithoutInventingAPercentage() {
        assertEquals("2.0 KB downloaded", downloadQueueProgressText(record.copy(downloadedBytes = 2048)))
        assertEquals("0 B downloaded", downloadQueueProgressText(record.copy(downloadedBytes = -1)))
    }

    @Test
    fun knownSizeShowsClampedPercentageAndByteCounts() {
        assertEquals("25% | 25 B / 100 B",
            downloadQueueProgressText(record.copy(downloadedBytes = 25, totalBytes = 100)))
        assertEquals("100% | 150 B / 100 B",
            downloadQueueProgressText(record.copy(downloadedBytes = 150, totalBytes = 100)))
    }

    private val record = OfflineIssueRecord(
        serverKey = "server", chapterId = 1, libraryId = 1, seriesId = 1, volumeId = 1,
        seriesName = "Series", issueName = "Issue", downloadId = 1, archivePath = ""
    )
}
