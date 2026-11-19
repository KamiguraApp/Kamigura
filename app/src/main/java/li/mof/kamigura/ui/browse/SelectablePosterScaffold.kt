package li.mof.kamigura.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import li.mof.kamigura.ui.KavitaCoverAspectRatio

@Composable
internal fun <K : Any> SelectablePosterScaffold(
    title: String,
    keys: List<K>?,
    deleteLabel: String,
    onBack: () -> Unit,
    onDelete: (Set<K>) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    exitOnDelete: Boolean = true,
    content: @Composable BoxScope.(selectionMode: Boolean, selected: Set<K>, toggle: (K) -> Unit) -> Unit
) {
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedKeys by rememberSaveable { mutableStateOf<List<K>>(emptyList()) }
    val available = keys.orEmpty().toSet()
    val selected = selectedKeys.filterTo(mutableSetOf()) { it in available }
    val selectionMode = selecting && available.isNotEmpty()

    fun exitSelection() {
        selecting = false
        selectedKeys = emptyList()
    }

    fun toggle(key: K) {
        if (busy || key !in available) return
        selecting = true
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
    }

    LaunchedEffect(keys) {
        if (keys != null) {
            if (keys.isEmpty() || (selectedKeys.isNotEmpty() && selected.isEmpty() && !exitOnDelete)) {
                exitSelection()
            } else {
                selectedKeys = selectedKeys.filter { it in available }
            }
        }
    }

    BackHandler(enabled = selectionMode || busy) { if (!busy) exitSelection() }
    BrowsePageScaffold(
        title = if (selectionMode) "${selected.size} selected" else title,
        modifier = modifier,
        onBack = { if (!busy) { if (selectionMode) exitSelection() else onBack() } },
        navigationIcon = if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
        navigationContentDescription = if (selectionMode) "Cancel selection" else "Back",
        actions = {
            if (selectionMode) {
                val allSelected = selected.size == available.size
                IconButton(enabled = !busy, onClick = {
                    selectedKeys = if (allSelected) emptyList() else keys.orEmpty()
                }) {
                    Icon(Icons.Filled.SelectAll, if (allSelected) "Clear selection" else "Select all", tint = Color.White)
                }
                IconButton(enabled = !busy && selected.isNotEmpty(), onClick = {
                    if (exitOnDelete) exitSelection()
                    onDelete(selected)
                }) {
                    Icon(Icons.Filled.Delete, deleteLabel, tint = Color.White)
                }
            } else if (available.isNotEmpty()) {
                IconButton(enabled = !busy, onClick = { selecting = true }) {
                    Icon(Icons.Filled.Checklist, "Select items", tint = Color.White)
                }
            }
        }
    ) {
        content(selectionMode, selected, ::toggle)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectablePosterCard(
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                enabled = enabled,
                onClick = if (selectionMode) onToggle else onOpen,
                onLongClick = onToggle
            ),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF303333)),
            content = content
        )
        if (selectionMode) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(KavitaCoverAspectRatio).background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    else Color.Black.copy(alpha = 0.12f)
                )
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.62f), MaterialTheme.shapes.small)
                )
            }
        }
    }
}
