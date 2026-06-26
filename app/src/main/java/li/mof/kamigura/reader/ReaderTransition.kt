package li.mof.kamigura.reader

internal enum class ReaderTurnDirection {
    Next,
    Previous
}

internal data class ReaderPageTransition(
    val outgoingPage: Int,
    val targetPage: Int,
    val direction: ReaderTurnDirection
)

internal data class PendingReaderTurn(
    val direction: ReaderTurnDirection,
    val step: Int,
    val completeWhenPastEnd: Boolean
)

internal fun readerTurnPhysicalSign(
    rightToLeft: Boolean,
    direction: ReaderTurnDirection
): Float = when (direction) {
    ReaderTurnDirection.Next -> if (rightToLeft) 1f else -1f
    ReaderTurnDirection.Previous -> if (rightToLeft) -1f else 1f
}

internal fun readerTurnForDrag(
    dragX: Float,
    rightToLeft: Boolean
): ReaderTurnDirection = if (
    dragX * readerTurnPhysicalSign(rightToLeft, ReaderTurnDirection.Next) >= 0f
) {
    ReaderTurnDirection.Next
} else {
    ReaderTurnDirection.Previous
}

internal fun readerTurnProgress(
    dragX: Float,
    commitDistancePx: Float
): Float = (kotlin.math.abs(dragX) / commitDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)

internal fun shouldCommitReaderTurn(progress: Float): Boolean = progress >= 1f
