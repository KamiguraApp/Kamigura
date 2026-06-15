package li.mof.kamigura

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("kamigura_settings")

enum class EdgeDoubleTapAction {
    ZoomToggle,
    PageTurnTwo
}

enum class InvertMode {
    Off,
    Smart,
    Always
}

data class ReaderSettings(
    val rightToLeft: Boolean = true,
    val edgeDoubleTapAction: EdgeDoubleTapAction = EdgeDoubleTapAction.PageTurnTwo,
    val invertMode: InvertMode = InvertMode.Off,
    val invertWhiteThreshold: Float = 0.5f
)

data class AppSettings(
    val reader: ReaderSettings = ReaderSettings()
)

class AppSettingsStore(private val context: Context) {
    private val KEY_RTL = booleanPreferencesKey("reader_rtl")
    private val KEY_EDGE_DOUBLE_TAP_ACTION = stringPreferencesKey("reader_edge_double_tap_action")
    private val KEY_INVERT_MODE = stringPreferencesKey("reader_invert_mode")
    private val KEY_INVERT_WHITE_THRESHOLD = floatPreferencesKey("reader_invert_white_threshold")

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            reader = ReaderSettings(
                rightToLeft = prefs[KEY_RTL] ?: true,
                edgeDoubleTapAction = prefs[KEY_EDGE_DOUBLE_TAP_ACTION]
                    ?.let { runCatching { EdgeDoubleTapAction.valueOf(it) }.getOrNull() }
                    ?: EdgeDoubleTapAction.PageTurnTwo,
                invertMode = prefs[KEY_INVERT_MODE]
                    ?.let { runCatching { InvertMode.valueOf(it) }.getOrNull() }
                    ?: InvertMode.Off,
                invertWhiteThreshold = prefs[KEY_INVERT_WHITE_THRESHOLD] ?: 0.5f
            )
        )
    }

    suspend fun setRightToLeft(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_RTL] = value }
    }

    suspend fun setEdgeDoubleTapAction(value: EdgeDoubleTapAction) {
        context.settingsDataStore.edit { it[KEY_EDGE_DOUBLE_TAP_ACTION] = value.name }
    }

    suspend fun setInvertMode(value: InvertMode) {
        context.settingsDataStore.edit { it[KEY_INVERT_MODE] = value.name }
    }

    suspend fun setInvertWhiteThreshold(value: Float) {
        context.settingsDataStore.edit { it[KEY_INVERT_WHITE_THRESHOLD] = value }
    }
}
