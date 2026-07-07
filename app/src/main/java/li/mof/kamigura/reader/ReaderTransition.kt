package li.mof.kamigura.reader

internal enum class ReaderTurnDirection {
    Next,
    Previous
}

internal data class ReaderPageTransition(
    val outgoingPage: Int,
    val targetPage: Int,
    val direction: ReaderTurnDirection,
    // Fraction of the viewport width the slide travels. A spread shift (step 1 on a
    // spread) moves the page strip by one page = half the viewport; everything else
    // sweeps the full width.
    val distanceFraction: Float = 1f
)

internal fun readerSlideDistanceFraction(
    step: Int,
    portrait: Boolean,
    currentSinglePage: Boolean
): Float = if (step == 1 && !portrait && !currentSinglePage) 0.5f else 1f

internal data class PendingReaderTurn(
    val direction: ReaderTurnDirection,
    val step: Int,
    val completeWhenPastEnd: Boolean,
    val slideOnly: Boolean = false
)

private const val ReaderTurnCommitProgressThreshold = 0.12f
private const val ReaderTurnFlingMinimumProgress = 0.04f

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
    visualDistancePx: Float
): Float = (kotlin.math.abs(dragX) / visualDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)

internal fun readerTurnDirectionForDrag(
    dragX: Float,
    rightToLeft: Boolean,
    lockedDirection: ReaderTurnDirection?,
    directionLockEnabled: Boolean
): ReaderTurnDirection =
    if (directionLockEnabled && lockedDirection != null) {
        lockedDirection
    } else {
        readerTurnForDrag(dragX, rightToLeft)
    }

internal fun readerLockedTurnProgress(
    dragX: Float,
    rightToLeft: Boolean,
    direction: ReaderTurnDirection,
    visualDistancePx: Float
): Float {
    val directionalDrag = dragX * readerTurnPhysicalSign(rightToLeft, direction)
    return (directionalDrag.coerceAtLeast(0f) / visualDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

internal fun readerTurnProgressForDrag(
    dragX: Float,
    rightToLeft: Boolean,
    direction: ReaderTurnDirection,
    visualDistancePx: Float,
    directionLockEnabled: Boolean
): Float =
    if (directionLockEnabled) {
        readerLockedTurnProgress(dragX, rightToLeft, direction, visualDistancePx)
    } else {
        readerTurnProgress(dragX, visualDistancePx)
    }

internal fun readerTurnTargetPage(
    currentPage: Int,
    pageCount: Int,
    direction: ReaderTurnDirection,
    step: Int,
    completeWhenPastEnd: Boolean
): Int? {
    if (pageCount <= 0) return null
    return when (direction) {
        ReaderTurnDirection.Next -> {
            val rawTarget = currentPage + step
            when {
                rawTarget < pageCount -> rawTarget
                completeWhenPastEnd -> null
                currentPage < pageCount - 1 -> pageCount - 1
                else -> null
            }
        }
        ReaderTurnDirection.Previous -> {
            if (currentPage == 0) null else (currentPage - step).coerceAtLeast(0)
        }
    }
}

internal fun readerSpreadCurlBackPageIndex(
    targetPage: Int,
    pageCount: Int,
    direction: ReaderTurnDirection,
    targetIsWide: Boolean = false
): Int =
    when {
        // A wide page is one sheet printed across the whole spread, so the flap back
        // face is the wide page itself (its near half emerges from the fold) in both
        // turn directions.
        targetIsWide -> targetPage
        direction == ReaderTurnDirection.Next -> targetPage
        else -> (targetPage + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0))
    }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

internal fun shouldCommitReaderTurn(
    progress: Float,
    velocityX: Float = 0f,
    direction: ReaderTurnDirection? = null,
    rightToLeft: Boolean = true,
    minimumFlingVelocity: Float = Float.POSITIVE_INFINITY
): Boolean {
    if (progress >= ReaderTurnCommitProgressThreshold) return true
    if (progress < ReaderTurnFlingMinimumProgress) return false
    val turnDirection = direction ?: return false
    val flingVelocity = minimumFlingVelocity.takeIf { it.isFinite() } ?: return false
    return velocityX * readerTurnPhysicalSign(rightToLeft, turnDirection) >= flingVelocity
}
