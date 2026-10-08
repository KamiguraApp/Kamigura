package li.mof.kamigura.download

import org.junit.Assert.*
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test
    fun queueIsScopedToTheServerAndExcludesCompletedAndRemovedRecords() {
        val records = OfflineDownloadStatus.entries.mapIndexed { index, status -> record(index, status) } +
            record(20, OfflineDownloadStatus.Downloading).copy(serverKey = "other-server")
        assertEquals(
            listOf(OfflineDownloadStatus.Downloading, OfflineDownloadStatus.Queued,
                OfflineDownloadStatus.Failed, OfflineDownloadStatus.Unsupported),
            downloadQueueRecords(records, "server").map { it.status }
        )
    }

    @Test
    fun onlyWaitingAndRunningTransfersArePolledAndOnlyFailuresCanRetry() {
        for (status in OfflineDownloadStatus.entries) {
            assertEquals(status in listOf(OfflineDownloadStatus.Queued, OfflineDownloadStatus.Downloading),
                status.isTransferActive)
            assertEquals(status in listOf(OfflineDownloadStatus.Failed, OfflineDownloadStatus.Unsupported),
                status.canRetryDownload)
        }
    }

    @Test
    fun filteringDoesNotAlterPendingProgressOrExposeTombstones() {
        val failed = record(1, OfflineDownloadStatus.Failed).copy(
            localPage = 7, progressPending = true, markReadPending = true, markUnreadPending = true)
        val removed = failed.copy(chapterId = 2, status = OfflineDownloadStatus.Removed)
        assertEquals(listOf(failed), downloadQueueRecords(listOf(removed, failed), "server"))
        assertEquals(emptyList<OfflineIssueRecord>(), downloadQueueRecords(emptyList(), "server"))
    }

    @Test
    fun byteProgressClampsAndHandlesUnknownTotals() {
        val downloading = record(1, OfflineDownloadStatus.Downloading)
        assertNull(downloading.progressFraction)
        assertEquals(0.25f, downloading.copy(downloadedBytes = 25, totalBytes = 100).progressFraction!!, 0f)
        assertEquals(1f, downloading.copy(downloadedBytes = 150, totalBytes = 100).progressFraction!!, 0f)
    }

    private fun record(id: Int, status: OfflineDownloadStatus) = OfflineIssueRecord(
        serverKey = "server", chapterId = id, libraryId = 1, seriesId = 1, volumeId = 1,
        seriesName = "Series", issueName = "Issue $id", downloadId = id.toLong(),
        archivePath = "", status = status
    )
}
