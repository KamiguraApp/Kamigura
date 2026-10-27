package li.mof.kamigura

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.mof.kamigura.reader.novel.NovelFontStore

@Composable
internal fun NovelSettingsContent(settings: NovelSettings, store: AppSettingsStore) {
    val context = LocalContext.current
    val fonts = remember(context) { NovelFontStore(context) }
    val scope = rememberCoroutineScope()
    var slot by rememberSaveable { mutableStateOf<NovelFontSlot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = slot
        slot = null
        if (uri != null && target != null) scope.launch {
            busy = true; error = null
            var imported: NovelFont? = null
            try {
                imported = fonts.import(uri)
                val previous = if (target == NovelFontSlot.Mincho) settings.mincho else settings.gothic
                withContext(NonCancellable) { store.setNovelFont(target, imported!!); imported = null }
                withContext(Dispatchers.IO) { fonts.remove(previous) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not import this font." }
            finally { imported?.let { fonts.remove(it) }; busy = false }
        }
    }
    Text("Writing style fallback", style = MaterialTheme.typography.titleMedium)
    Text("Used when neither the series on the Kavita server nor the book sets a writing style.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    NovelSettingChoices(NovelWritingStyle.entries, settings.writingStyle) { scope.launch { store.setNovelWritingStyle(it) } }
    Text("Background", style = MaterialTheme.typography.titleMedium)
    NovelSettingChoices(PageBackground.entries, settings.background) { scope.launch { store.setNovelBackground(it) } }
    Text("Fonts", style = MaterialTheme.typography.titleMedium)
    Text("A font embedded in the book is always used first. Otherwise Mincho is used for serif text and Gothic for sans-serif text.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    for (target in NovelFontSlot.entries) {
        val font = if (target == NovelFontSlot.Mincho) settings.mincho else settings.gothic
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(target.name, style = MaterialTheme.typography.titleSmall)
            Text(font.name ?: if (target == NovelFontSlot.Mincho) "Noto Serif CJK JP (system)" else "Noto Sans CJK JP (system)",
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && slot == null, onClick = {
                    slot = target; error = null; picker.launch(arrayOf("*/*"))
                }) { Icon(Icons.Filled.FileOpen, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Import ${target.name}") }
                if (font.id != null) TextButton(enabled = !busy && slot == null, onClick = {
                    scope.launch {
                        busy = true; error = null
                        try { store.setNovelFont(target, NovelFont()); withContext(Dispatchers.IO) { fonts.remove(font) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Could not reset this font." }
                        finally { busy = false }
                    }
                }) { Icon(Icons.Filled.Restore, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Reset to default") }
            }
        }
    }
    if (busy) Text("Checking font...", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Text("Font for books without one", style = MaterialTheme.typography.titleMedium)
    Text("Used for text the book gives no font for.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    NovelSettingChoices(NovelFontSlot.entries, settings.defaultFont) { scope.launch { store.setNovelDefaultFont(it) } }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T : Enum<T>> NovelSettingChoices(values: List<T>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        values.forEachIndexed { index, value ->
            ToggleButton(checked = selected == value, onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }) { Text(value.name) }
        }
    }
}
