package li.mof.kamigura

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import li.mof.kamigura.reader.novel.NovelReaderOverrides
import li.mof.kamigura.reader.novel.resolveNovelReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsMigrationTest {
    @Test fun upgradeRetainsAnimationChoiceAndMigratesNovelBackgroundOnlyOnce() = runBlocking {
        for (invert in InvertMode.entries) for (background in PageBackground.entries)
            for (animation in listOf(false, true)) for (mode in PageTurnMode.entries) {
                val old = emptyPreferences().toMutablePreferences().apply {
                    this[stringPreferencesKey("reader_invert_mode")] = invert.name
                    this[stringPreferencesKey("reader_page_background")] = background.name
                    this[booleanPreferencesKey("reader_page_transition_animation")] = animation
                    this[stringPreferencesKey("reader_page_turn_mode")] = mode.name
                    this[stringPreferencesKey("reader_reading_direction")] = "LeftToRight"
                    this[intPreferencesKey("reader_prefetch_turns")] = 7
                    this[booleanPreferencesKey("reader_show_spread_shift_buttons")] = false
                }
                assertTrue(ReaderSettingsMigration.shouldMigrate(old))
                val migrated = ReaderSettingsMigration.migrate(old)
                for ((key, value) in old.asMap()) assertEquals(value, migrated.asMap()[key])
                assertEquals(if (!animation) "Off" else mode.name, migrated[stringPreferencesKey("reader_page_transition")])
                assertEquals(if (invert != InvertMode.Off || background == PageBackground.Dark) "Dark" else "Paper",
                    migrated[stringPreferencesKey("novel_background")])
                assertFalse(ReaderSettingsMigration.shouldMigrate(migrated))
                assertEquals(migrated, ReaderSettingsMigration.migrate(migrated))
            }
    }

    @Test fun freshInstallUsesPaperAndCurl() = runBlocking {
        val fresh = ReaderSettingsMigration.migrate(emptyPreferences())
        assertEquals("Paper", fresh[stringPreferencesKey("novel_background")])
        assertEquals("Curl", fresh[stringPreferencesKey("reader_page_transition")])
    }

    @Test fun novelStyleUsesOverrideThenNonDefaultProfileThenBookDetectionAndFallback() {
        val fallback = NovelSettings(writingStyle = NovelWritingStyle.Vertical)
        val default = UserReadingProfileDto(kind = 0, bookReaderWritingStyle = 0)
        val automatic = resolveNovelReaderPreferences(default, null, novelSettings = fallback)
        assertTrue(automatic.automaticWritingStyle)
        assertTrue(automatic.vertical)
        val profile = resolveNovelReaderPreferences(default.copy(kind = 1), null, novelSettings = fallback)
        assertFalse(profile.automaticWritingStyle)
        assertFalse(profile.vertical)
        val override = resolveNovelReaderPreferences(default.copy(kind = 1), NovelReaderOverrides(writingStyle = 1))
        assertFalse(override.automaticWritingStyle)
        assertTrue(override.vertical)
        assertTrue(resolveNovelReaderPreferences(null, null, novelSettings = fallback).vertical)
    }
}
