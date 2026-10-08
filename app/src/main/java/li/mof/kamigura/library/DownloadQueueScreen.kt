package li.mof.kamigura.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.canRetryDownload
import li.mof.kamigura.download.isTransferActive
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import kotlin.math.roundToInt

@Composable
internal fun DownloadQueueScreen(
    session: KavitaSession,
    onBack: () -> Unit,
    onOpenDownloaded: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { OfflineIssueRepository(context) }
    val queueFlow = remember(repository, session) { repository.observeDownloadQueue(session) }
    val operationMutex = remember(repository, session) { Mutex() }
    var records by remember(session) { mutableStateOf<List<OfflineIssueRecord>>(emptyList()) }
    var busyIds by remember(session) { mutableStateOf<Set<Int>>(emptySet()) }
    var actionErrors by remember(session) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var loading by remember(session) { mutableStateOf(true) }
    var loadError by remember(session) { mutableStateOf<String?>(null) }
    var updateError by remember(session) { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(queueFlow, lifecycleOwner, retryKey) {
        loadError = null
        try {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    queueFlow.collect {
                        records = it
                        loading = false
                    }
                }
                launch {
                    while (isActive) {
                        var failure: String? = null
                        operationMutex.withLock {
                            val active = queueFlow.first().filter { it.status.isTransferActive }
                            for (record in active) {
                                try {
                                    repository.reconcile(session, record.chapterId)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Throwable) {
                                    KamiguraLog.w("Could not update download ${record.chapterId}.", t)
                                    failure = t.message ?: "Could not update downloads"
                                }
                            }
                        }
                        updateError = failure
                        delay(1_000)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            KamiguraLog.w("Could not observe download queue.", t)
            loading = false
            loadError = t.message ?: "Could not load downloads"
        }
    }

    fun act(record: OfflineIssueRecord, retry: Boolean) {
        if (record.chapterId in busyIds) return
        busyIds = busyIds + record.chapterId
        actionErrors = actionErrors - record.chapterId
        scope.launch {
            try {
                operationMutex.withLock {
                    // A queued item can finish before the click is handled. Never remove a
                    // completed download using a stale row, and never enqueue an active one twice.
                    val latest = queueFlow.first().firstOrNull { it.chapterId == record.chapterId }
                        ?: return@withLock
                    if (retry) {
                        if (!latest.status.canRetryDownload) return@withLock
                        repository.enqueue(
                            session = session,
                            libraryId = latest.libraryId,
                            seriesId = latest.seriesId,
                            volumeId = latest.volumeId,
                            chapterId = latest.chapterId,
                            seriesName = latest.seriesName,
                            issueName = latest.issueName,
                            expectedBytes = latest.totalBytes.takeIf { it > 0 },
                            expectedPageCount = latest.expectedPageCount
                        )
                    } else {
                        repository.remove(session, latest.chapterId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                KamiguraLog.w("Could not ${if (retry) "retry" else "remove"} download ${record.chapterId}.", t)
                val action = if (retry) "retry" else "remove"
                actionErrors = actionErrors + (record.chapterId to
                    "Could not $action download: ${t.message ?: "Try again."}")
            } finally {
                busyIds = busyIds - record.chapterId
            }
        }
    }

    BrowsePageScaffold(
        title = "Download queue",
        statusBarPadding = false,
        onBack = onBack,
        actions = {
            QueueIconButton("Downloaded", Icons.Filled.DownloadDone, onClick = onOpenDownloaded)
        }
    ) {
        when {
            loading -> DarkLoadingState()
            loadError != null -> DarkMessageState(
                "Could not load downloads", loadError.orEmpty(), "Retry", { retryKey++ })
            records.isEmpty() -> DarkMessageState(
                "No downloads in the queue", "Completed issues are in Downloaded.",
                "Open Downloaded", onOpenDownloaded)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().navigationBarsPadding(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    updateError?.let { message ->
                        item(key = "update-error") {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Could not update downloads: $message", modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                QueueIconButton("Refresh", Icons.Filled.Refresh, onClick = { retryKey++ })
                            }
                        }
                    }
                    items(records, key = { it.chapterId }) { record ->
                        DownloadQueueRow(
                            record = record,
                            busy = record.chapterId in busyIds,
                            actionError = actionErrors[record.chapterId],
                            onCancel = { act(record, retry = false) },
                            onRetry = { act(record, retry = true) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueRow(
    record: OfflineIssueRecord,
    busy: Boolean,
    actionError: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(record.seriesName, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(record.issueName, color = Color(0xFFB9BDBD), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (record.status.canRetryDownload) {
                QueueIconButton("Retry download", Icons.Filled.Refresh, enabled = !busy, onClick = onRetry)
            }
            QueueIconButton(
                label = if (record.status.isTransferActive) "Cancel download" else "Remove download",
                icon = if (record.status.isTransferActive) Icons.Filled.Close else Icons.Filled.DeleteOutline,
                enabled = !busy,
                busy = busy,
                onClick = onCancel
            )
        }
        Text(record.status.name,
            color = if (record.status.canRetryDownload) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge)
        if (record.status == OfflineDownloadStatus.Downloading ||
            (record.status == OfflineDownloadStatus.Queued && record.downloadedBytes > 0)) {
            val progress = record.progressFraction
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(downloadQueueProgressText(record), color = Color(0xFFB9BDBD),
                style = MaterialTheme.typography.bodySmall)
        }
        record.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFB9BDBD), style = MaterialTheme.typography.bodySmall)
        }
        actionError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(color = Color(0xFF303636), modifier = Modifier.padding(horizontal = 16.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueIconButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = label)
            }
        }
    }
}

internal fun downloadQueueProgressText(record: OfflineIssueRecord): String {
    val downloaded = downloadQueueBytes(record.downloadedBytes.coerceAtLeast(0))
    val progress = record.progressFraction ?: return "$downloaded downloaded"
    return "${(progress * 100).roundToInt()}% | $downloaded / ${downloadQueueBytes(record.totalBytes)}"
}

private fun downloadQueueBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
