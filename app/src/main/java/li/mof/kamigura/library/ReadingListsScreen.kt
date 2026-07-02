package li.mof.kamigura.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.mof.kamigura.CreateReadingListDto
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.ReadingListDto
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.theme.KamiguraSurface

@Composable
internal fun ReadingListsScreen(
    sessionStore: KavitaSessionStore,
    onBack: () -> Unit,
    onOpenReadingList: (ReadingListDto) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var readingLists by remember { mutableStateOf<List<ReadingListDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createTitle by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
            readingLists = api.readingLists()
                .sortedBy { it.title ?: "Reading List ${it.id}" }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    BrowsePageScaffold(title = "Reading Lists", onBack = onBack) {
        when {
            loading -> DarkLoadingState()
            error != null -> DarkMessageState("Could not load reading lists", error ?: "Unknown error")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    NewReadingListRow(onClick = {
                        createTitle = ""
                        createDialog = true
                    })
                }
                if (readingLists.isEmpty()) {
                    item {
                        DarkMessageState("Reading Lists", "No reading lists yet.")
                    }
                }
                items(readingLists, key = { it.id }) { readingList ->
                    ReadingListRow(
                        readingList = readingList,
                        onClick = { onOpenReadingList(readingList) }
                    )
                }
            }
        }
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!creating) createDialog = false
            },
            title = { Text("New Reading List") },
            text = {
                OutlinedTextField(
                    value = createTitle,
                    onValueChange = { createTitle = it },
                    label = { Text("Title") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !creating && createTitle.trim().isNotBlank(),
                    onClick = {
                        val title = createTitle.trim()
                        scope.launch {
                            creating = true
                            try {
                                val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
                                val created = api.createReadingList(CreateReadingListDto(title))
                                readingLists = (readingLists + created)
                                    .distinctBy { it.id }
                                    .sortedBy { it.title ?: "Reading List ${it.id}" }
                                createDialog = false
                            } catch (t: Throwable) {
                                error = t.message ?: t.toString()
                            } finally {
                                creating = false
                            }
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creating,
                    onClick = { createDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NewReadingListRow(
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF273A32),
        contentColor = Color(0xFFD3EEE3),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF1E2F29),
                contentColor = Color(0xFFD3EEE3),
                shape = MaterialTheme.shapes.small
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = "New Reading List",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReadingListRow(
    readingList: ReadingListDto,
    onClick: () -> Unit
) {
    Surface(
        color = KamiguraSurface,
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = Color(0xFF273A32),
                contentColor = Color(0xFFD3EEE3),
                shape = MaterialTheme.shapes.small
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = readingList.title ?: "Reading List ${readingList.id}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = readingList.itemCount?.let { count ->
                        when (count) {
                            1 -> "1 series"
                            else -> "$count series"
                        }
                    } ?: "Reading List",
                    color = Color(0xFFB9BDBD),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFFE6EAEA)
            )
        }
    }
}
