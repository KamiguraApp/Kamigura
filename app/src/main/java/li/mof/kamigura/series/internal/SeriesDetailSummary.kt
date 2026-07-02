package li.mof.kamigura.series.internal

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.CreateReadingListDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.ReadingListDto
import li.mof.kamigura.RefreshSeriesDto
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.SeriesMetadataDto
import li.mof.kamigura.UpdateReadingListBySeriesDto
import li.mof.kamigura.UpdateWantToReadDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial
import li.mof.kamigura.ui.theme.KamiguraBackground
/** Internal to series, not for external use. */
@Composable
internal fun SeriesDetailSummary(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    continueChapter: ChapterDto?,
    chapterCards: List<ChapterCardItem>,
    volumeCount: Int,
    session: KavitaSession,
    api: KavitaApi,
    isAdmin: Boolean,
    onPick: (chapterId: Int, volumeId: Int) -> Unit
) {
    val summary = metadata?.summary?.takeIf { it.isNotBlank() }
    val genres = metadata?.genres?.mapNotNull { it.title }.orEmpty()
    val continueItem = continueChapter?.let { chapter ->
        chapterCards.firstOrNull { it.chapter.id == chapter.id }
    } ?: chapterCards.firstOrNull()
    val continueButtonText = series.primaryReadActionText()
    val continueButtonColor = series.coverActionColor()
    val summaryActionColor = continueButtonColor.readableAccentOn(KamiguraBackground)
    var summaryExpanded by remember(summary) { mutableStateOf(false) }
    var summaryCanExpand by remember(summary) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SeriesDetailHero(
            series = series,
            metadata = metadata,
            issueCount = chapterCards.size,
            volumeCount = volumeCount,
            session = session
        )

        continueItem?.let { item ->
            SeriesReadSplitButton(
                text = continueButtonText,
                containerColor = continueButtonColor,
                series = series,
                api = api,
                isAdmin = isAdmin,
                onRead = { onPick(item.chapter.id, item.volume.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        summary?.let {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = it,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (summaryExpanded) Int.MAX_VALUE else 8,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layoutResult ->
                        if (!summaryExpanded && layoutResult.hasVisualOverflow) {
                            summaryCanExpand = true
                        }
                    }
                )
                if (summaryCanExpand || summaryExpanded) {
                    TextButton(
                        onClick = { summaryExpanded = !summaryExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = summaryActionColor)
                    ) {
                        Text(if (summaryExpanded) "Show less" else "Show more")
                    }
                }
            }
        }

        DetailFactBlock("Genres", genres)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SeriesReadSplitButton(
    text: String,
    containerColor: Color,
    series: SeriesDto,
    api: KavitaApi,
    isAdmin: Boolean,
    onRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showingReadingLists by remember { mutableStateOf(false) }
    var readingLists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var createReadingListDialog by remember { mutableStateOf(false) }
    var newReadingListTitle by remember { mutableStateOf("") }
    var actionRunning by remember { mutableStateOf(false) }
    val colors = ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = Color.White
    )
    val mainMenuItems = buildList {
        add(SeriesMenuAction.WantToRead)
        add(SeriesMenuAction.AddToReadingList)
        if (isAdmin) add(SeriesMenuAction.Refresh)
    }

    BoxWithConstraints(modifier) {
        val trailingWidth = 48.dp
        val leadingWidth = (maxWidth - trailingWidth - SplitButtonDefaults.Spacing).coerceAtLeast(48.dp)

        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onRead,
                    modifier = Modifier.width(leadingWidth),
                    colors = colors
                ) {
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            trailingButton = {
                Box {
                    SplitButtonDefaults.TrailingButton(
                        checked = menuExpanded,
                        onCheckedChange = { menuExpanded = it },
                        modifier = Modifier
                            .width(trailingWidth)
                            .semantics {
                                stateDescription = if (menuExpanded) "Expanded" else "Collapsed"
                                contentDescription = "More reading actions"
                            },
                        colors = colors
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (menuExpanded) 180f else 0f,
                            label = "Reading actions arrow rotation"
                        )
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }

                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = {
                            menuExpanded = false
                            showingReadingLists = false
                        },
                        modifier = Modifier.width(240.dp),
                        offset = DpOffset(x = trailingWidth - 240.dp, y = 0.dp)
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(index = 0, count = 1)
                        ) {
                            val labels = if (showingReadingLists) {
                                listOf("Back") + readingLists.map { list ->
                                    list.title?.takeIf { it.isNotBlank() } ?: "Reading List ${list.id}"
                                } + "New Reading List"
                            } else {
                                mainMenuItems.map { it.label }
                            }

                            labels.forEachIndexed { index, label ->
                                val itemShape = MenuDefaults.itemShape(
                                    index = index,
                                    count = labels.size
                                ).shape

                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        if (showingReadingLists) {
                                            if (index == 0) {
                                                showingReadingLists = false
                                            } else if (index == labels.lastIndex) {
                                                menuExpanded = false
                                                showingReadingLists = false
                                                newReadingListTitle = ""
                                                createReadingListDialog = true
                                            } else {
                                                val readingList = readingLists[index - 1]
                                                menuExpanded = false
                                                showingReadingLists = false
                                                scope.launch {
                                                    actionRunning = true
                                                    runCatching {
                                                        api.addSeriesToReadingList(
                                                            UpdateReadingListBySeriesDto(
                                                                seriesId = series.id,
                                                                readingListId = readingList.id
                                                            )
                                                        )
                                                    }.onSuccess {
                                                        Toast.makeText(
                                                            context,
                                                            "Added to ${readingList.title ?: "reading list"}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }.onFailure {
                                                        Toast.makeText(
                                                            context,
                                                            "Could not update reading list",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                    actionRunning = false
                                                }
                                            }
                                        } else {
                                            when (mainMenuItems[index]) {
                                                SeriesMenuAction.WantToRead -> {
                                                    menuExpanded = false
                                                    scope.launch {
                                                        actionRunning = true
                                                        runCatching {
                                                            api.addSeriesToWantToRead(
                                                                UpdateWantToReadDto(listOf(series.id))
                                                            )
                                                        }.onSuccess {
                                                            Toast.makeText(
                                                                context,
                                                                "Added to Want to Read",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }.onFailure {
                                                            Toast.makeText(
                                                                context,
                                                                "Could not update Want to Read",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        actionRunning = false
                                                    }
                                                }
                                                SeriesMenuAction.AddToReadingList -> scope.launch {
                                                    actionRunning = true
                                                    runCatching { api.readingLists() }
                                                        .onSuccess { lists ->
                                                            readingLists = lists
                                                            showingReadingLists = true
                                                        }
                                                        .onFailure {
                                                            menuExpanded = false
                                                            Toast.makeText(
                                                                context,
                                                                "Could not load reading lists",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    actionRunning = false
                                                }
                                                SeriesMenuAction.Refresh -> {
                                                    menuExpanded = false
                                                    val libraryId = series.libraryId
                                                    if (libraryId == null) {
                                                        Toast.makeText(
                                                            context,
                                                            "Library information is unavailable",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } else {
                                                        scope.launch {
                                                            actionRunning = true
                                                            val request = RefreshSeriesDto(
                                                                libraryId = libraryId,
                                                                seriesId = series.id
                                                            )
                                                            runCatching {
                                                                api.scanSeries(request)
                                                                api.analyzeSeries(request)
                                                                api.refreshSeriesMetadata(request)
                                                            }.onSuccess {
                                                                Toast.makeText(
                                                                    context,
                                                                    "Series refresh requested",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }.onFailure {
                                                                Toast.makeText(
                                                                    context,
                                                                    "Could not refresh series",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                            actionRunning = false
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    shape = itemShape,
                                    enabled = !actionRunning
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (createReadingListDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!actionRunning) createReadingListDialog = false
            },
            title = { Text("New Reading List") },
            text = {
                OutlinedTextField(
                    value = newReadingListTitle,
                    onValueChange = { newReadingListTitle = it },
                    label = { Text("Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionRunning && newReadingListTitle.trim().isNotBlank(),
                    onClick = {
                        val title = newReadingListTitle.trim()
                        scope.launch {
                            actionRunning = true
                            runCatching {
                                val list = api.createReadingList(CreateReadingListDto(title))
                                api.addSeriesToReadingList(
                                    UpdateReadingListBySeriesDto(
                                        seriesId = series.id,
                                        readingListId = list.id
                                    )
                                )
                                list
                            }.onSuccess { list ->
                                readingLists = (readingLists + list)
                                    .distinctBy { it.id }
                                    .sortedBy { it.title ?: "Reading List ${it.id}" }
                                createReadingListDialog = false
                                Toast.makeText(
                                    context,
                                    "Added to ${list.title ?: "reading list"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Could not create reading list",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            actionRunning = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionRunning,
                    onClick = { createReadingListDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private enum class SeriesMenuAction(val label: String) {
    WantToRead("Add to Want to Read"),
    AddToReadingList("Add to Reading List"),
    Refresh("Refresh")
}

@Composable
private fun SeriesDetailHero(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    issueCount: Int,
    volumeCount: Int,
    session: KavitaSession
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 420.dp
        val coverWidth = if (compact) 180.dp else 150.dp
        if (compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SeriesCover(series, session, Modifier.width(coverWidth))
                SeriesDetailHeroInfo(
                    series = series,
                    metadata = metadata,
                    issueCount = issueCount,
                    volumeCount = volumeCount,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                SeriesCover(series, session, Modifier.width(coverWidth))
                SeriesDetailHeroInfo(
                    series = series,
                    metadata = metadata,
                    issueCount = issueCount,
                    volumeCount = volumeCount,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SeriesCover(series: SeriesDto, session: KavitaSession, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(KavitaCoverAspectRatio)
            .background(Color(0xFF111111)),
        contentAlignment = Alignment.Center
    ) {
        if (session.baseUrl.isNotBlank() && session.apiKey.isNotBlank()) {
            AsyncImage(
                model = seriesCoverUrl(session, series.id),
                contentDescription = series.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(seriesInitial(series.name), color = Color(0xFFB9BDBD), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun SeriesDetailHeroInfo(
    series: SeriesDto,
    metadata: SeriesMetadataDto?,
    issueCount: Int,
    volumeCount: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val creatorMaxChars = when {
            maxWidth < 220.dp -> 18
            maxWidth < 320.dp -> 28
            else -> 42
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = series.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            series.detailMetaLines(metadata, creatorMaxChars, issueCount, volumeCount).forEach { line ->
                Text(
                    text = line,
                    color = Color(0xFFE6EAEA),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DetailFactBlock(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = Color(0xFFB9BDBD),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = values.joinToString(", "),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

