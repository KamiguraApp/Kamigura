package li.mof.kamigura.reader.internal

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import li.mof.kamigura.ReaderReadingDirection
import li.mof.kamigura.reader.ReaderTurnDirection

internal fun readerKeyTurn(
    keyCode: Int,
    readingDirection: ReaderReadingDirection,
    volumeKeys: Boolean,
    shift: Boolean = false
): ReaderTurnDirection? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) ReaderTurnDirection.Next else null
    KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) ReaderTurnDirection.Previous else null
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> ReaderTurnDirection.Next
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> ReaderTurnDirection.Previous
    KeyEvent.KEYCODE_SPACE -> if (shift) ReaderTurnDirection.Previous else ReaderTurnDirection.Next
    KeyEvent.KEYCODE_DPAD_LEFT -> if (readingDirection == ReaderReadingDirection.RightToLeft) {
        ReaderTurnDirection.Next
    } else ReaderTurnDirection.Previous
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (readingDirection == ReaderReadingDirection.RightToLeft) {
        ReaderTurnDirection.Previous
    } else ReaderTurnDirection.Next
    else -> null
}

@Composable
internal fun ReaderKeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        if (enabled) view.keepScreenOn = true
        onDispose { if (enabled) view.keepScreenOn = previous }
    }
}

@Composable
internal fun ReaderKeyEffect(
    readingDirection: ReaderReadingDirection,
    volumeKeys: Boolean,
    isBlocked: () -> Boolean,
    onTurn: (ReaderTurnDirection) -> Unit
) {
    val view = LocalView.current
    val latestDirection by rememberUpdatedState(readingDirection)
    val latestVolumeKeys by rememberUpdatedState(volumeKeys)
    val latestIsBlocked by rememberUpdatedState(isBlocked)
    val latestOnTurn by rememberUpdatedState(onTurn)
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val original = window?.callback
        if (window == null || original == null) {
            onDispose {}
        } else {
            // Intercept before focused Compose controls, without changing reader focus.
            val pressed = mutableSetOf<Int>()
            val callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_UP && pressed.remove(event.keyCode)) return true
                    val direction = if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) null else {
                        readerKeyTurn(event.keyCode, latestDirection, latestVolumeKeys, event.isShiftPressed)
                    }
                    if (direction == null || event.action != KeyEvent.ACTION_DOWN) {
                        return original.dispatchKeyEvent(event)
                    }
                    // A touch drag can start before the next composition updates derived guards.
                    if (event.repeatCount == 0 && pressed.add(event.keyCode) && !latestIsBlocked()) {
                        latestOnTurn(direction)
                    }
                    return true
                }

                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) pressed.clear()
                    original.onWindowFocusChanged(hasFocus)
                }
            }
            window.callback = callback
            onDispose { if (window.callback === callback) window.callback = original }
        }
    }
}
