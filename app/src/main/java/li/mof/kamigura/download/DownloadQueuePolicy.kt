package li.mof.kamigura.download

internal val OfflineDownloadStatus.isTransferActive: Boolean
    get() = this == OfflineDownloadStatus.Queued || this == OfflineDownloadStatus.Downloading

internal val OfflineDownloadStatus.canRetryDownload: Boolean
    get() = this == OfflineDownloadStatus.Failed || this == OfflineDownloadStatus.Unsupported

internal fun downloadQueueRecords(
    records: List<OfflineIssueRecord>,
    serverKey: String
): List<OfflineIssueRecord> = records
    .filter { it.serverKey == serverKey && (it.status.isTransferActive || it.status.canRetryDownload) }
    .sortedWith(compareBy<OfflineIssueRecord> {
        when (it.status) {
            OfflineDownloadStatus.Downloading -> 0
            OfflineDownloadStatus.Queued -> 1
            OfflineDownloadStatus.Failed -> 2
            else -> 3
        }
    }.thenBy { it.chapterId })
