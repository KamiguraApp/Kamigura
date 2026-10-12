package li.mof.kamigura.reader

import android.view.KeyEvent
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.ReaderSettings
import li.mof.kamigura.reader.internal.readerKeyTurn
import org.junit.Assert.*
import org.junit.Test

class ReaderDeviceControlsTest {
    @Test
    fun screenStaysOnByDefaultButVolumeKeysAreOptIn() {
        val settings = ReaderSettings()
        assertTrue(settings.keepScreenOn)
        assertFalse(settings.turnPagesWithVolumeKeys)
    }

    @Test
    fun volumeKeysAreNotClaimedWhenDisabled() {
        ReaderReadingDirection.entries.forEach { direction ->
            assertNull(readerKeyTurn(KeyEvent.KEYCODE_VOLUME_DOWN, direction, false))
            assertNull(readerKeyTurn(KeyEvent.KEYCODE_VOLUME_UP, direction, false))
            assertEquals(ReaderTurnDirection.Next, readerKeyTurn(KeyEvent.KEYCODE_VOLUME_DOWN, direction, true))
            assertEquals(ReaderTurnDirection.Previous, readerKeyTurn(KeyEvent.KEYCODE_VOLUME_UP, direction, true))
        }
    }

    @Test
    fun pageAndVerticalArrowKeysFollowLogicalReadingOrder() {
        ReaderReadingDirection.entries.forEach { direction ->
            listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE).forEach {
                assertEquals(ReaderTurnDirection.Next, readerKeyTurn(it, direction, false))
            }
            listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP).forEach {
                assertEquals(ReaderTurnDirection.Previous, readerKeyTurn(it, direction, false))
            }
            assertEquals(ReaderTurnDirection.Previous, readerKeyTurn(KeyEvent.KEYCODE_SPACE, direction, false, shift = true))
        }
    }

    @Test
    fun horizontalArrowsMatchPhysicalTapZones() {
        ReaderReadingDirection.entries.forEach { direction ->
            val left = if (direction == ReaderReadingDirection.RightToLeft) ReaderTurnDirection.Next else ReaderTurnDirection.Previous
            val right = if (direction == ReaderReadingDirection.RightToLeft) ReaderTurnDirection.Previous else ReaderTurnDirection.Next
            assertEquals(left, readerKeyTurn(KeyEvent.KEYCODE_DPAD_LEFT, direction, false))
            assertEquals(right, readerKeyTurn(KeyEvent.KEYCODE_DPAD_RIGHT, direction, false))
        }
    }

    @Test
    fun unrelatedKeysAreNotClaimed() {
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_A).forEach {
            assertNull(readerKeyTurn(it, ReaderReadingDirection.RightToLeft, true))
        }
    }
}
