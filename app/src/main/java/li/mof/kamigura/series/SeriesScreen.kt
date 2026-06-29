package li.mof.kamigura.series

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.MarkChapterReadDto
import li.mof.kamigura.MarkVolumesReadDto
import li.mof.kamigura.ReadingListDto
import li.mof.kamigura.RefreshSeriesDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto
import li.mof.kamigura.SeriesMetadataDto
import li.mof.kamigura.UpdateReadingListBySeriesDto
import li.mof.kamigura.UpdateWantToReadDto
import li.mof.kamigura.VolumeDto
import li.mof.kamigura.download.OfflineDownloadStatus
import li.mof.kamigura.download.OfflineIssueRepository
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

internal fun chapterCoverUrl(session: KavitaSession, chapterId: Int): String {
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = session.apiKey.takeIf { it.isNotBlank() }?.let { "&apiKey=${Uri.encode(it)}" }.orEmpty()
    return "$root/api/Image/chapter-cover?chapterId=$chapterId$apiKey"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SeriesScreen(
    sessionStore: KavitaSessionStore,
    libraryId: Int,
    libraryName: String,
    onBack: () -> Unit,
    onSelect: (SeriesDto) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var session by remember { mutableStateOf(KavitaSession()) }
    var api by remember { mutableStateOf<KavitaApi?>(null) }
    var isAdmin by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var scanRunning by remember { mutableStateOf(false) }

    LaunchedEffect(libraryId) {
        loading = true
        error = null
        api = null
        isAdmin = false
        menuExpanded = false
        scanRunning = false
        try {
            session = sessionStore.load()
            val client = KavitaClient(ctx, sessionStore)
            val (loadedApi, _) = client.buildApi()
            api = loadedApi
            isAdmin = runCatching {
                loadedApi.currentUser().roles.orEmpty().any { it.equals("Admin", ignoreCase = true) }
            }.getOrDefault(false)
            val all = loadedApi.allSeriesV2(
                body = SeriesFilterV2Dto(
                    statements = listOf(
                        SeriesFilterStatementDto(
                            comparison = 0,
                            field = 19,
                            value = libraryId.toString()
                        )
                    )
                ),
                pageNumber = 0,
                pageSize = 300
            )
            series = all.filter { it.libraryId == null || it.libraryId == libraryId }
                .sortedBy { it.name }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFF202222))
    ) {
        BrowsePageScaffold(
            title = libraryName,
            onBack = onBack,
            actions = {
                if (isAdmin && api != null) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !scanRunning
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Library actions",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.width(220.dp)
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(index = 0, count = 1)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Scan Library") },
                                    onClick = {
                                        menuExpanded = false
                                        val loadedApi = api ?: return@DropdownMenuItem
                                        scope.launch {
                                            scanRunning = true
                                            runCatching {
                                                loadedApi.scanLibrary(libraryId)
                                            }.onSuccess {
                                                Toast.makeText(
                                                    ctx,
                                                    "Library scan requested",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }.onFailure {
                                                Toast.makeText(
                                                    ctx,
                                                    "Could not scan library",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            scanRunning = false
                                        }
                                    },
                                    enabled = !scanRunning
                                )
                            }
                        }
                    }
                }
            }
        ) {
            when {
                loading -> DarkLoadingState()
                error != null -> DarkMessageState("Could not load series", error ?: "Unknown error")
                series.isEmpty() -> DarkMessageState("No series", "This library did not return any visible series.")
                else -> PosterGrid(items = series, key = { it.id }) { item ->
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(item) }
                    )
                }
            }
        }
    }
}
