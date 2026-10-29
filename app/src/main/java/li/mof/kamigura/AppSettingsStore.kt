package li.mof.kamigura

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("kamigura_settings",
    produceMigrations = { listOf(ReaderSettingsMigration) })

enum class InvertMode {
    Off,
    Smart,
    Always
}

enum class PageTurnMode {
    Slide,
    Curl
}

enum class PageTransition { Off, Slide, Curl }
enum class NovelWritingStyle { Horizontal, Vertical }
enum class NovelFontSlot { Mincho, Gothic }
enum class NovelMargins(val dp: Int) { Narrow(12), Normal(20), Wide(32) }

data class NovelFont(val id: String? = null, val name: String? = null)
data class CommonReaderSettings(
    val keepScreenOn: Boolean = true,
    val turnPagesWithVolumeKeys: Boolean = false,
    val usePurePageBackgroundColors: Boolean = false
)
data class NovelSettings(
    val background: PageBackground = PageBackground.Paper,
    val writingStyle: NovelWritingStyle = NovelWritingStyle.Horizontal,
    val defaultFont: NovelFontSlot = NovelFontSlot.Mincho,
    val margins: NovelMargins = NovelMargins.Normal,
    val mincho: NovelFont = NovelFont(),
    val gothic: NovelFont = NovelFont()
)

private val NovelBackgroundKey = stringPreferencesKey("novel_background")
private val TransitionKey = stringPreferencesKey("reader_page_transition")

internal object ReaderSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData[NovelBackgroundKey] == null || currentData[TransitionKey] == null
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        if (this[NovelBackgroundKey] == null) {
            val dark = this[stringPreferencesKey("reader_invert_mode")] in setOf("Smart", "Always") ||
                this[stringPreferencesKey("reader_page_background")] == "Dark"
            this[NovelBackgroundKey] = (if (dark) PageBackground.Dark else PageBackground.Paper).name
        }
        if (this[TransitionKey] == null) {
            this[TransitionKey] = if (this[booleanPreferencesKey("reader_page_transition_animation")] == false) "Off"
                else this[stringPreferencesKey("reader_page_turn_mode")]?.takeIf { it == "Slide" || it == "Curl" } ?: "Curl"
        }
    }
    override suspend fun cleanUp() = Unit
}

enum class ReaderReadingDirection {
    RightToLeft,
    Vertical,
    LeftToRight
}

/** Colour of the margins around a page (and of the curl flap's back face). */
enum class PageBackground {
    Paper,
    Dark
}

internal const val DefaultReaderPrefetchTurns = 4
internal const val MaxReaderPrefetchTurns = 8

data class ImageReaderSettings(
    val readingDirection: ReaderReadingDirection = ReaderReadingDirection.RightToLeft,
    val invertMode: InvertMode = InvertMode.Off,
    val invertWhiteThreshold: Float = 0.5f,
    val prefetchTurns: Int = DefaultReaderPrefetchTurns,
    val pageTransition: PageTransition = PageTransition.Curl,
    val showPortraitPageBackContent: Boolean = true,
    val showSpreadShiftButtons: Boolean = true
) {
    val pageTransitionAnimation: Boolean get() = pageTransition != PageTransition.Off
    val pageTurnMode: PageTurnMode get() = if (pageTransition == PageTransition.Curl) PageTurnMode.Curl else PageTurnMode.Slide
    val rightToLeft: Boolean
        get() = readingDirection == ReaderReadingDirection.RightToLeft
}

data class ReaderSettings(
    val common: CommonReaderSettings = CommonReaderSettings(),
    val image: ImageReaderSettings = ImageReaderSettings(),
    val novel: NovelSettings = NovelSettings()
)

data class AppSettings(
    val reader: ReaderSettings = ReaderSettings()
)

class AppSettingsStore(private val context: Context) {
    private val KEY_RTL = booleanPreferencesKey("reader_rtl")
    private val KEY_READING_DIRECTION = stringPreferencesKey("reader_reading_direction")
    private val KEY_INVERT_MODE = stringPreferencesKey("reader_invert_mode")
    private val KEY_INVERT_WHITE_THRESHOLD = floatPreferencesKey("reader_invert_white_threshold")
    private val KEY_PREFETCH_TURNS = intPreferencesKey("reader_prefetch_turns")
    private val KEY_LEGACY_UNMETERED_PREFETCH_TURNS = intPreferencesKey("reader_unmetered_prefetch_turns")
    private val KEY_NOVEL_WRITING = stringPreferencesKey("novel_writing_style")
    private val KEY_NOVEL_FONT = stringPreferencesKey("novel_default_font")
    private val KEY_NOVEL_MARGINS = stringPreferencesKey("novel_margins")
    private val KEY_SHOW_PORTRAIT_PAGE_BACK_CONTENT =
        booleanPreferencesKey("reader_show_portrait_page_back_content")
    private val KEY_USE_PURE_PAGE_BACKGROUND_COLORS =
        booleanPreferencesKey("reader_use_pure_page_background_colors")
    private val KEY_SHOW_SPREAD_SHIFT_BUTTONS = booleanPreferencesKey("reader_show_spread_shift_buttons")
    private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("reader_keep_screen_on")
    private val KEY_VOLUME_KEYS = booleanPreferencesKey("reader_turn_pages_with_volume_keys")

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            reader = ReaderSettings(common = CommonReaderSettings(
                keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: true,
                turnPagesWithVolumeKeys = prefs[KEY_VOLUME_KEYS] ?: false,
                usePurePageBackgroundColors = prefs[KEY_USE_PURE_PAGE_BACKGROUND_COLORS] ?: false
            ), novel = NovelSettings(
                background = enumValue(prefs[NovelBackgroundKey], PageBackground.Paper),
                writingStyle = enumValue(prefs[KEY_NOVEL_WRITING], NovelWritingStyle.Horizontal),
                defaultFont = enumValue(prefs[KEY_NOVEL_FONT], NovelFontSlot.Mincho),
                margins = enumValue(prefs[KEY_NOVEL_MARGINS], NovelMargins.Normal),
                mincho = NovelFont(prefs[fontKey(NovelFontSlot.Mincho)], prefs[fontNameKey(NovelFontSlot.Mincho)]),
                gothic = NovelFont(prefs[fontKey(NovelFontSlot.Gothic)], prefs[fontNameKey(NovelFontSlot.Gothic)])
            ), image = ImageReaderSettings(
                readingDirection = readerReadingDirection(
                    storedName = prefs[KEY_READING_DIRECTION],
                    legacyRightToLeft = prefs[KEY_RTL]
                ),
                invertMode = prefs[KEY_INVERT_MODE]
                    ?.let { runCatching { InvertMode.valueOf(it) }.getOrNull() }
                    ?: InvertMode.Off,
                invertWhiteThreshold = prefs[KEY_INVERT_WHITE_THRESHOLD] ?: 0.5f,
                prefetchTurns = (prefs[KEY_PREFETCH_TURNS]
                    ?: prefs[KEY_LEGACY_UNMETERED_PREFETCH_TURNS]
                    ?: DefaultReaderPrefetchTurns)
                    .coerceIn(0, MaxReaderPrefetchTurns),
                pageTransition = enumValue(prefs[TransitionKey], PageTransition.Curl),
                showPortraitPageBackContent = prefs[KEY_SHOW_PORTRAIT_PAGE_BACK_CONTENT] ?: true,
                showSpreadShiftButtons = prefs[KEY_SHOW_SPREAD_SHIFT_BUTTONS] ?: true
            ))
        )
    }

    suspend fun setRightToLeft(value: Boolean) {
        setReadingDirection(
            if (value) ReaderReadingDirection.RightToLeft else ReaderReadingDirection.LeftToRight
        )
    }

    suspend fun setReadingDirection(value: ReaderReadingDirection) {
        context.settingsDataStore.edit { it[KEY_READING_DIRECTION] = value.name }
    }

    suspend fun setInvertMode(value: InvertMode) {
        context.settingsDataStore.edit { it[KEY_INVERT_MODE] = value.name }
    }

    suspend fun setInvertWhiteThreshold(value: Float) {
        context.settingsDataStore.edit { it[KEY_INVERT_WHITE_THRESHOLD] = value }
    }

    suspend fun setPrefetchTurns(value: Int) {
        context.settingsDataStore.edit { it[KEY_PREFETCH_TURNS] = value.coerceIn(0, MaxReaderPrefetchTurns) }
    }

    suspend fun setPageTransition(value: PageTransition) {
        context.settingsDataStore.edit { it[TransitionKey] = value.name }
    }

    suspend fun setNovelBackground(value: PageBackground) {
        context.settingsDataStore.edit { it[NovelBackgroundKey] = value.name }
    }

    suspend fun setNovelWritingStyle(value: NovelWritingStyle) {
        context.settingsDataStore.edit { it[KEY_NOVEL_WRITING] = value.name }
    }

    suspend fun setNovelDefaultFont(value: NovelFontSlot) {
        context.settingsDataStore.edit { it[KEY_NOVEL_FONT] = value.name }
    }

    suspend fun setNovelMargins(value: NovelMargins) {
        context.settingsDataStore.edit { it[KEY_NOVEL_MARGINS] = value.name }
    }

    suspend fun setNovelFont(slot: NovelFontSlot, font: NovelFont) {
        context.settingsDataStore.edit {
            if (font.id == null) { it.remove(fontKey(slot)); it.remove(fontNameKey(slot)) }
            else { it[fontKey(slot)] = font.id; it[fontNameKey(slot)] = font.name.orEmpty() }
        }
    }

    suspend fun setShowPortraitPageBackContent(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_PORTRAIT_PAGE_BACK_CONTENT] = value }
    }

    suspend fun setUsePurePageBackgroundColors(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_USE_PURE_PAGE_BACKGROUND_COLORS] = value }
    }

    suspend fun setShowSpreadShiftButtons(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_SPREAD_SHIFT_BUTTONS] = value }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_KEEP_SCREEN_ON] = value }
    }

    suspend fun setTurnPagesWithVolumeKeys(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_VOLUME_KEYS] = value }
    }
}

private fun fontKey(slot: NovelFontSlot) = stringPreferencesKey("novel_font_${slot.name}")
private fun fontNameKey(slot: NovelFontSlot) = stringPreferencesKey("novel_font_name_${slot.name}")
private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

internal fun readerReadingDirection(
    storedName: String?,
    legacyRightToLeft: Boolean?
): ReaderReadingDirection {
    return storedName
        ?.let { runCatching { ReaderReadingDirection.valueOf(it) }.getOrNull() }
        ?: if (legacyRightToLeft != false) {
            ReaderReadingDirection.RightToLeft
        } else {
            ReaderReadingDirection.LeftToRight
        }
}
