package li.mof.kamigura.reader.internal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import li.mof.kamigura.reader.ReaderTurnDirection
import li.mof.kamigura.reader.readerTurnForDrag
import li.mof.kamigura.reader.readerTurnProgress

/** Internal to reader, not for external use. */
@Composable
internal fun ReaderTapLayer(
    rightToLeft: Boolean,
    onNextSpread: () -> Unit,
    onPreviousSpread: () -> Unit,
    onNextSingle: () -> Unit,
    onPreviousSingle: () -> Unit,
    onCenterTap: () -> Unit,
    turnVisualDistancePx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit = { _, _ -> },
    onTurnDragEnd: () -> Unit = {},
    onTurnDragCancel: () -> Unit = {},
    closeSwipeEnabled: Boolean = false,
    closeVisualDistancePx: Float = 1f,
    onCloseDrag: (Float) -> Unit = {},
    onCloseDragEnd: (Boolean) -> Unit = {},
    onCloseDragCancel: () -> Unit = {},
    onDoubleTap: (Offset) -> Unit = {},
    onTransform: (Float, Offset, Offset) -> Unit = { _, _, _ -> }
) {
    val latestOnNextSpread by rememberUpdatedState(onNextSpread)
    val latestOnPreviousSpread by rememberUpdatedState(onPreviousSpread)
    val latestOnNextSingle by rememberUpdatedState(onNextSingle)
    val latestOnPreviousSingle by rememberUpdatedState(onPreviousSingle)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)
    val latestOnDoubleTap by rememberUpdatedState(onDoubleTap)

    Box(
        Modifier
            .fillMaxSize()
            .readerPinchZoom(onTransform = onTransform)
            .readerGestures(
                rightToLeft = rightToLeft,
                turnVisualDistancePx = turnVisualDistancePx,
                zoomPanEnabled = zoomPanEnabled,
                panOffsetX = panOffsetX,
                panOffsetY = panOffsetY,
                panMaxX = panMaxX,
                panMaxY = panMaxY,
                onPan = onPan,
                onTurnDrag = onTurnDrag,
                onTurnDragEnd = onTurnDragEnd,
                onTurnDragCancel = onTurnDragCancel,
                closeSwipeEnabled = closeSwipeEnabled,
                closeVisualDistancePx = closeVisualDistancePx,
                onCloseDrag = onCloseDrag,
                onCloseDragEnd = onCloseDragEnd,
                onCloseDragCancel = onCloseDragCancel,
                onLeftTap = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnNextSpread() else latestOnPreviousSpread()
                    }
                },
                onCenterTap = latestOnCenterTap,
                onRightTap = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnPreviousSpread() else latestOnNextSpread()
                    }
                },
                onLeftLongPress = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnNextSingle() else latestOnPreviousSingle()
                    }
                },
                onRightLongPress = {
                    if (!zoomPanEnabled) {
                        if (rightToLeft) latestOnPreviousSingle() else latestOnNextSingle()
                    }
                },
                onCenterDoubleTap = latestOnDoubleTap
            )
    )
}

@Composable
private fun Modifier.readerPinchZoom(
    onTransform: (Float, Offset, Offset) -> Unit
): Modifier {
    val latestOnTransform by rememberUpdatedState(onTransform)
    return pointerInput(Unit) {
        awaitEachGesture {
            var lastCentroid: Offset? = null
            var lastSpan = 0f

            while (true) {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                if (pressedChanges.isEmpty()) break
                if (pressedChanges.size < 2) {
                    lastCentroid = null
                    lastSpan = 0f
                    continue
                }

                var centroidX = 0f
                var centroidY = 0f
                pressedChanges.forEach { change ->
                    centroidX += change.position.x
                    centroidY += change.position.y
                }
                val centroid = Offset(
                    x = centroidX / pressedChanges.size,
                    y = centroidY / pressedChanges.size
                )
                val span = pressedChanges
                    .map { (it.position - centroid).getDistance() }
                    .average()
                    .toFloat()

                val previousCentroid = lastCentroid
                if (previousCentroid != null && lastSpan > 0f && span > 0f) {
                    latestOnTransform(span / lastSpan, centroid - previousCentroid, centroid)
                    pressedChanges.forEach { it.consume() }
                }

                lastCentroid = centroid
                lastSpan = span
            }
        }
    }
}

@Composable
private fun Modifier.readerGestures(
    rightToLeft: Boolean,
    turnVisualDistancePx: Float,
    zoomPanEnabled: Boolean = false,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    panMaxX: Float = 0f,
    panMaxY: Float = 0f,
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onTurnDrag: (ReaderTurnDirection, Float) -> Unit,
    onTurnDragEnd: () -> Unit,
    onTurnDragCancel: () -> Unit,
    closeSwipeEnabled: Boolean = false,
    closeVisualDistancePx: Float = 1f,
    onCloseDrag: (Float) -> Unit = {},
    onCloseDragEnd: (Boolean) -> Unit = {},
    onCloseDragCancel: () -> Unit = {},
    onLeftTap: () -> Unit = {},
    onCenterTap: () -> Unit = {},
    onRightTap: () -> Unit = {},
    onLeftLongPress: () -> Unit = {},
    onRightLongPress: () -> Unit = {},
    onCenterDoubleTap: (Offset) -> Unit = {}
): Modifier {
    val latestRightToLeft by rememberUpdatedState(rightToLeft)
    val latestTurnVisualDistancePx by rememberUpdatedState(turnVisualDistancePx)
    val latestZoomPanEnabled by rememberUpdatedState(zoomPanEnabled)
    val latestPanOffsetX by rememberUpdatedState(panOffsetX)
    val latestPanOffsetY by rememberUpdatedState(panOffsetY)
    val latestPanMaxX by rememberUpdatedState(panMaxX)
    val latestPanMaxY by rememberUpdatedState(panMaxY)
    val latestCloseSwipeEnabled by rememberUpdatedState(closeSwipeEnabled)
    val latestCloseVisualDistancePx by rememberUpdatedState(closeVisualDistancePx)
    val latestOnPan by rememberUpdatedState(onPan)
    val latestOnTurnDrag by rememberUpdatedState(onTurnDrag)
    val latestOnTurnDragEnd by rememberUpdatedState(onTurnDragEnd)
    val latestOnTurnDragCancel by rememberUpdatedState(onTurnDragCancel)
    val latestOnCloseDrag by rememberUpdatedState(onCloseDrag)
    val latestOnCloseDragEnd by rememberUpdatedState(onCloseDragEnd)
    val latestOnCloseDragCancel by rememberUpdatedState(onCloseDragCancel)
    val latestOnLeftTap by rememberUpdatedState(onLeftTap)
    val latestOnCenterTap by rememberUpdatedState(onCenterTap)
    val latestOnRightTap by rememberUpdatedState(onRightTap)
    val latestOnLeftLongPress by rememberUpdatedState(onLeftLongPress)
    val latestOnRightLongPress by rememberUpdatedState(onRightLongPress)
    val latestOnCenterDoubleTap by rememberUpdatedState(onCenterDoubleTap)
    // Keep the pointerInput key stable; volatile gesture state is read through rememberUpdatedState to avoid restarting mid-swipe.
    return pointerInput(Unit) {
        var totalDragX = 0f
        var gestureDragX = 0f
        var gestureDragY = 0f
        var dragMode = ReaderDragMode.Pending
        var closeDragOffsetY = 0f
        var activePanX = 0f
        var activePanY = 0f
        var dragStartedAtNegativePanEdge = false
        var dragStartedAtPositivePanEdge = false
        val panEdgeTolerancePx = 1f
        val horizontalIntentSlopPx = 8f
        val directionLockSlopPx = 4f
        val verticalCloseIntentSlopPx = 12f
        val tapMoveSlopPx = directionLockSlopPx
        val doubleTapSlopPx = 64f
        val longPressTimeoutMillis = 500L
        val doubleTapTimeoutMillis = 300L
        var pendingCenterTap: ReaderPendingCenterTap? = null

        fun closeMaxDragPx(): Float = latestCloseVisualDistancePx.coerceAtLeast(1f)

        fun closeCommitDistancePx(): Float = closeMaxDragPx() * 0.18f

        fun resetDragState() {
            totalDragX = 0f
            gestureDragX = 0f
            gestureDragY = 0f
            dragMode = ReaderDragMode.Pending
            closeDragOffsetY = 0f
            activePanX = latestPanOffsetX
            activePanY = latestPanOffsetY
            dragStartedAtNegativePanEdge = activePanX <= -latestPanMaxX + panEdgeTolerancePx
            dragStartedAtPositivePanEdge = activePanX >= latestPanMaxX - panEdgeTolerancePx
        }

        fun finishDrag() {
            when (dragMode) {
                ReaderDragMode.VerticalClose -> {
                    latestOnCloseDragEnd(closeDragOffsetY >= closeCommitDistancePx())
                }
                ReaderDragMode.HorizontalTurn,
                ReaderDragMode.ZoomEdgeTurn -> latestOnTurnDragEnd()
                ReaderDragMode.Pending -> Unit
            }
        }

        fun cancelDrag() {
            when (dragMode) {
                ReaderDragMode.VerticalClose -> latestOnCloseDragCancel()
                ReaderDragMode.HorizontalTurn,
                ReaderDragMode.ZoomEdgeTurn -> latestOnTurnDragCancel()
                ReaderDragMode.Pending -> Unit
            }
        }

        fun tapZone(position: Offset): ReaderTapZone {
            val thirdWidth = size.width / 3f
            return when {
                position.x < thirdWidth -> ReaderTapZone.Left
                position.x >= thirdWidth * 2f -> ReaderTapZone.Right
                else -> ReaderTapZone.Center
            }
        }

        fun flushPendingCenterTap() {
            if (pendingCenterTap != null) {
                pendingCenterTap = null
                latestOnCenterTap()
            }
        }

        fun dropPendingCenterTap() {
            pendingCenterTap = null
        }

        fun handleTap(position: Offset, uptimeMillis: Long) {
            when (tapZone(position)) {
                ReaderTapZone.Left -> {
                    flushPendingCenterTap()
                    latestOnLeftTap()
                }
                ReaderTapZone.Right -> {
                    flushPendingCenterTap()
                    latestOnRightTap()
                }
                ReaderTapZone.Center -> {
                    val pending = pendingCenterTap
                    if (
                        pending != null &&
                        uptimeMillis - pending.uptimeMillis <= doubleTapTimeoutMillis &&
                        (position - pending.position).getDistance() <= doubleTapSlopPx
                    ) {
                        pendingCenterTap = null
                        latestOnCenterDoubleTap(position)
                    } else {
                        pendingCenterTap = ReaderPendingCenterTap(position, uptimeMillis)
                    }
                }
            }
        }

        fun handleLongPress(position: Offset) {
            flushPendingCenterTap()
            when (tapZone(position)) {
                ReaderTapZone.Left -> latestOnLeftLongPress()
                ReaderTapZone.Right -> latestOnRightLongPress()
                ReaderTapZone.Center -> Unit
            }
        }

        fun handleDrag(change: PointerInputChange, dragAmount: Offset) {
            gestureDragX += dragAmount.x
            gestureDragY += dragAmount.y
            if (latestZoomPanEnabled) {
                if (dragMode == ReaderDragMode.ZoomEdgeTurn) {
                    totalDragX += dragAmount.x
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, latestRightToLeft),
                        readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                    )
                    change.consume()
                    return
                }

                val stillAtStartedNegativeEdge =
                    latestPanMaxX > 0f &&
                        dragStartedAtNegativePanEdge &&
                        activePanX <= -latestPanMaxX + panEdgeTolerancePx
                val stillAtStartedPositiveEdge =
                    latestPanMaxX > 0f &&
                        dragStartedAtPositivePanEdge &&
                        activePanX >= latestPanMaxX - panEdgeTolerancePx
                val draggingOutFromStartEdge =
                    (stillAtStartedNegativeEdge && dragAmount.x < 0f) ||
                        (stillAtStartedPositiveEdge && dragAmount.x > 0f)
                val horizontalIntent =
                    abs(gestureDragX) >= horizontalIntentSlopPx &&
                        abs(gestureDragX) > abs(gestureDragY) * 1.2f
                if (!draggingOutFromStartEdge || !horizontalIntent) {
                    activePanX = (activePanX + dragAmount.x).coerceIn(-latestPanMaxX, latestPanMaxX)
                    activePanY = (activePanY + dragAmount.y).coerceIn(-latestPanMaxY, latestPanMaxY)
                    latestOnPan(activePanX, activePanY)
                    change.consume()
                    return
                }

                totalDragX = gestureDragX
                dragMode = ReaderDragMode.ZoomEdgeTurn
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, latestRightToLeft),
                    readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                )
                change.consume()
                return
            }

            if (dragMode == ReaderDragMode.VerticalClose) {
                closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx())
                latestOnCloseDrag(closeDragOffsetY)
                change.consume()
                return
            }

            if (dragMode == ReaderDragMode.HorizontalTurn) {
                totalDragX += dragAmount.x
                latestOnTurnDrag(
                    readerTurnForDrag(totalDragX, latestRightToLeft),
                    readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                )
                change.consume()
                return
            }

            val absGestureX = abs(gestureDragX)
            val hasDirectionIntent = hypot(gestureDragX, gestureDragY) >= directionLockSlopPx
            val downwardCloseIntent =
                latestCloseSwipeEnabled &&
                    gestureDragY >= verticalCloseIntentSlopPx &&
                    gestureDragY > absGestureX * 1.4f
            if (!hasDirectionIntent) {
                change.consume()
                return
            }

            when {
                downwardCloseIntent -> {
                    dragMode = ReaderDragMode.VerticalClose
                    closeDragOffsetY = gestureDragY.coerceIn(0f, closeMaxDragPx())
                    latestOnCloseDrag(closeDragOffsetY)
                }
                else -> {
                    totalDragX = gestureDragX
                    dragMode = ReaderDragMode.HorizontalTurn
                    latestOnTurnDrag(
                        readerTurnForDrag(totalDragX, latestRightToLeft),
                        readerTurnProgress(totalDragX, latestTurnVisualDistancePx)
                    )
                }
            }
            change.consume()
        }

        awaitEachGesture {
            val down = if (pendingCenterTap != null) {
                withTimeoutOrNull(doubleTapTimeoutMillis) {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                } ?: run {
                    flushPendingCenterTap()
                    return@awaitEachGesture
                }
            } else {
                awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
            }
            pendingCenterTap?.let { pending ->
                if ((down.position - pending.position).getDistance() > doubleTapSlopPx) {
                    flushPendingCenterTap()
                }
            }
            resetDragState()
            var previousPosition = down.position
            val pointerId = down.id
            var lastEventUptimeMillis = down.uptimeMillis
            var tapCandidate = true
            var longPressFired = false

            while (true) {
                val timeUntilLongPressMillis = longPressTimeoutMillis -
                    (lastEventUptimeMillis - down.uptimeMillis)
                val event = if (tapCandidate && !longPressFired && timeUntilLongPressMillis > 0L) {
                    withTimeoutOrNull(timeUntilLongPressMillis) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                } else {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
                if (event == null) {
                    if (tapCandidate && !longPressFired) {
                        handleLongPress(down.position)
                        longPressFired = true
                        tapCandidate = false
                        continue
                    } else {
                        finishDrag()
                        break
                    }
                }
                val pressedChanges = event.changes.filter { it.pressed }
                if (pressedChanges.size > 1) {
                    dropPendingCenterTap()
                    cancelDrag()
                    break
                }

                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) {
                    val release = event.changes.firstOrNull { it.id == pointerId }
                    if (tapCandidate && !longPressFired) {
                        handleTap(
                            position = release?.position ?: previousPosition,
                            uptimeMillis = release?.uptimeMillis ?: lastEventUptimeMillis
                        )
                    } else {
                        finishDrag()
                    }
                    break
                }
                lastEventUptimeMillis = change.uptimeMillis

                val dragAmount = change.position - previousPosition
                previousPosition = change.position
                if (longPressFired) {
                    change.consume()
                    continue
                }
                if (dragAmount != Offset.Zero) {
                    if (tapCandidate && (change.position - down.position).getDistance() >= tapMoveSlopPx) {
                        tapCandidate = false
                        dropPendingCenterTap()
                    }
                    if (tapCandidate) {
                        change.consume()
                    }
                    handleDrag(change, dragAmount)
                }
            }
        }
    }
}

