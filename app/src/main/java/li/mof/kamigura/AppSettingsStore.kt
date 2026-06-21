package li.mof.kamigura

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

internal const val DefaultMeteredPrefetchTurns = 2
internal const val DefaultUnmeteredPrefetchTurns = 4
internal const val MaxReaderPrefetchTurns = 8

data class ReaderSettings(
    val rightToLeft: Boolean = true,
    val edgeDoubleTapAction: EdgeDoubleTapAction = EdgeDoubleTapAction.PageTurnTwo,
    val invertMode: InvertMode = InvertMode.Off,
    val invertWhiteThreshold: Float = 0.5f,
    val meteredPrefetchTurns: Int = DefaultMeteredPrefetchTurns,
    val unmeteredPrefetchTurns: Int = DefaultUnmeteredPrefetchTurns
)

data class AppSettings(
    val reader: ReaderSettings = ReaderSettings()
)

class AppSettingsStore(private val context: Context) {
    private val KEY_RTL = booleanPreferencesKey("reader_rtl")
    private val KEY_EDGE_DOUBLE_TAP_ACTION = stringPreferencesKey("reader_edge_double_tap_action")
    private val KEY_INVERT_MODE = stringPreferencesKey("reader_invert_mode")
    private val KEY_INVERT_WHITE_THRESHOLD = floatPreferencesKey("reader_invert_white_threshold")
    private val KEY_METERED_PREFETCH_TURNS = intPreferencesKey("reader_metered_prefetch_turns")
    private val KEY_UNMETERED_PREFETCH_TURNS = intPreferencesKey("reader_unmetered_prefetch_turns")

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
                invertWhiteThreshold = prefs[KEY_INVERT_WHITE_THRESHOLD] ?: 0.5f,
                meteredPrefetchTurns = (prefs[KEY_METERED_PREFETCH_TURNS]
                    ?: DefaultMeteredPrefetchTurns)
                    .coerceIn(0, MaxReaderPrefetchTurns),
                unmeteredPrefetchTurns = (prefs[KEY_UNMETERED_PREFETCH_TURNS]
                    ?: DefaultUnmeteredPrefetchTurns)
                    .coerceIn(0, MaxReaderPrefetchTurns)
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

    suspend fun setMeteredPrefetchTurns(value: Int) {
        context.settingsDataStore.edit { it[KEY_METERED_PREFETCH_TURNS] = value.coerceIn(0, MaxReaderPrefetchTurns) }
    }

    suspend fun setUnmeteredPrefetchTurns(value: Int) {
        context.settingsDataStore.edit { it[KEY_UNMETERED_PREFETCH_TURNS] = value.coerceIn(0, MaxReaderPrefetchTurns) }
    }
}
