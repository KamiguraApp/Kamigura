package li.mof.kamigura.library

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.download.OfflineIssueRecord
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.download.localCoverFile
import li.mof.kamigura.series.IssueDetailSideSheet
import li.mof.kamigura.series.chapterCoverUrl
import li.mof.kamigura.series.coverActionColor
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
@Composable
private fun DownloadedGrid(
    records: List<OfflineIssueRecord>,
    session: KavitaSession,
    onSelect: (OfflineIssueRecord) -> Unit,
    onDelete: (OfflineIssueRecord) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BrowsePageScaffold(title = "Downloaded", modifier = modifier, onBack = onBack) {
        if (records.isEmpty()) {
            DarkMessageState(title = "Downloaded", body = "No issues downloaded yet.")
        } else {
            PosterGrid(items = records, key = { it.chapterId }) { record ->
                DownloadedIssueCardMenu(
                    record = record,
                    session = session,
                    onClick = { onSelect(record) },
                    onDelete = { onDelete(record) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedIssueCardMenu(
    record: OfflineIssueRecord,
    session: KavitaSession,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
        ) {
            Column {
                AsyncImage(
                    model = record.localCoverFile() ?: chapterCoverUrl(session, record.chapterId),
                    contentDescription = record.issueName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(KavitaCoverAspectRatio)
                        .background(Color(0xFF111111)),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(
                        text = record.issueName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = record.seriesName,
                        color = Color(0xFFB9BDBD),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Delete Downloaded") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
internal fun DownloadedScreen(
    sessionStore: KavitaSessionStore,
    onBack: () -> Unit,
    onPickIssue: (
        libraryId: Int,
        seriesId: Int,
        volumeId: Int,
        chapterId: Int,
        incognito: Boolean
    ) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineRepository = remember(ctx) { OfflineIssueRepository(ctx) }

    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var selectedDownload by remember { mutableStateOf<OfflineIssueRecord?>(null) }
    var selectedChapter by remember { mutableStateOf<ChapterDto?>(null) }
    var selectedVolume by remember { mutableStateOf<VolumeDto?>(null) }
    var issueLoading by remember { mutableStateOf(false) }
    var issueActionBusy by remember { mutableStateOf(false) }

    val downloadedFlow = remember(session.baseUrl, session.username, session.apiKey) {
        offlineRepository.observeDownloaded(session)
    }
    val downloaded by downloadedFlow.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        val loadedSession = sessionStore.load()
        session = loadedSession
        runCatching { offlineRepository.ensureLocalCovers(loadedSession) }
        api = runCatching {
            KavitaClient(ctx, sessionStore).buildApi().first
        }.getOrNull()
    }

    fun openDownloaded(record: OfflineIssueRecord) {
        selectedDownload = record
        val fallbackChapter = ChapterDto(
            id = record.chapterId,
            title = record.issueName,
            pages = record.pageCount.takeIf { it > 0 },
            pagesRead = record.localPage,
            volumeId = record.volumeId
        )
        selectedChapter = fallbackChapter
        selectedVolume = VolumeDto(
            id = record.volumeId,
            name = record.issueName,
            chapters = listOf(fallbackChapter)
        )
        val currentApi = api
        issueLoading = currentApi != null
        if (currentApi == null) return
        scope.launch {
            val detail = runCatching { currentApi.seriesChapter(record.chapterId) }.getOrNull()
            val volume = runCatching { currentApi.volumes(record.seriesId) }.getOrNull()
                ?.firstOrNull { candidate ->
                    candidate.id == record.volumeId || candidate.chapters.any { it.id == record.chapterId }
                }
            if (selectedDownload?.chapterId == record.chapterId) {
                selectedChapter = detail ?: fallbackChapter
                selectedVolume = volume ?: detail?.let {
                    VolumeDto(id = record.volumeId, name = record.issueName, chapters = listOf(it))
                } ?: selectedVolume
                issueLoading = false
            }
        }
    }

    fun deleteDownloaded(record: OfflineIssueRecord, closeSheet: Boolean = false) {
        scope.launch {
            issueActionBusy = true
            runCatching { offlineRepository.remove(session, record.chapterId) }
                .onSuccess {
                    if (closeSheet && selectedDownload?.chapterId == record.chapterId) {
                        selectedDownload = null
                        selectedChapter = null
                        selectedVolume = null
                    }
                    Toast.makeText(ctx, "Download deleted", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(ctx, "Could not delete download", Toast.LENGTH_SHORT).show()
                }
            issueActionBusy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFF202222))
    ) {
        DownloadedGrid(
            records = downloaded,
            session = session,
            onSelect = ::openDownloaded,
            onDelete = { deleteDownloaded(it) },
            onBack = onBack,
            modifier = Modifier.fillMaxSize()
        )
    }

    val selectedRecord = selectedDownload
    val detail = selectedChapter
    val actionColor = detail?.coverActionColor(Color(0xFF6A5BB7)) ?: Color(0xFF6A5BB7)
    IssueDetailSideSheet(
        visible = selectedRecord != null,
        seriesName = selectedRecord?.seriesName.orEmpty(),
        volume = selectedVolume,
        chapter = detail,
        fileSizeBytes = selectedRecord?.totalBytes,
        downloadRecord = selectedRecord,
        loading = issueLoading,
        actionBusy = issueActionBusy,
        session = session,
        actionColor = actionColor,
        onDismissRequest = {
            selectedDownload = null
            selectedChapter = null
            selectedVolume = null
        },
        onRead = {
            selectedRecord?.let {
                onPickIssue(it.libraryId, it.seriesId, it.volumeId, it.chapterId, false)
            }
        },
        onReadIncognito = {
            selectedRecord?.let {
                onPickIssue(it.libraryId, it.seriesId, it.volumeId, it.chapterId, true)
            }
        },
        onMarkRead = {
            val record = selectedRecord ?: return@IssueDetailSideSheet
            val currentApi = api
            scope.launch {
                issueActionBusy = true
                runCatching {
                    if (currentApi != null) {
                        currentApi.markChapterRead(MarkChapterReadDto(record.seriesId, record.chapterId, false))
                    } else {
                        offlineRepository.saveLocalProgress(
                            session = session,
                            chapterId = record.chapterId,
                            page = record.pageCount,
                            markRead = true
                        )
                    }
                }.onSuccess {
                    selectedChapter = currentApi?.let {
                        runCatching { it.seriesChapter(record.chapterId) }.getOrNull()
                    } ?: detail?.copy(pagesRead = detail.pages)
                    Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, "Could not mark issue as read", Toast.LENGTH_SHORT).show()
                }
                issueActionBusy = false
            }
        },
        onMarkUnread = {
            val record = selectedRecord ?: return@IssueDetailSideSheet
            val currentApi = api
            scope.launch {
                issueActionBusy = true
                runCatching {
                    if (currentApi != null) {
                        currentApi.markChaptersUnread(
                            MarkVolumesReadDto(record.seriesId, chapterIds = listOf(record.chapterId))
                        )
                    } else {
                        offlineRepository.markLocalUnread(session, record.chapterId)
                    }
                }.onSuccess {
                    selectedChapter = currentApi?.let {
                        runCatching { it.seriesChapter(record.chapterId) }.getOrNull()
                    } ?: detail?.copy(pagesRead = 0)
                    Toast.makeText(ctx, "Marked as unread", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(ctx, "Could not mark issue as unread", Toast.LENGTH_SHORT).show()
                }
                issueActionBusy = false
            }
        },
        onDownload = {},
        onRemoveDownload = {
            selectedRecord?.let { deleteDownloaded(it, closeSheet = true) }
        }
    )
}


