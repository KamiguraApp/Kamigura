package li.mof.kamigura.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class ReaderJumpTextPosition(val text: Int, val offset: Int)

@Serializable
internal data class ReaderJumpLocation(
    val page: Int,
    val screen: Int = 0,
    val anchor: String? = null,
    val textPosition: ReaderJumpTextPosition? = null,
    val lastScreen: Boolean = false
) : Comparable<ReaderJumpLocation> {
    override fun compareTo(other: ReaderJumpLocation): Int {
        if (page != other.page) return page.compareTo(other.page)
        val a = textPosition
        val b = other.textPosition
        return if (a != null && b != null) compareValuesBy(a, b, { it.text }, { it.offset })
        else screen.compareTo(other.screen)
    }
}

@Serializable
internal data class ReaderJumpBackRecord(
    val furthest: ReaderJumpLocation? = null,
    @SerialName("lookingBack") val lookingUp: Boolean = false
)

internal enum class ReaderJumpOpening { Resume, Jump, Restore }

internal class ReaderJumpBackState(
    initial: ReaderJumpBackRecord? = null,
    private val onChange: (ReaderJumpBackRecord) -> Unit = {}
) {
    private var record by mutableStateOf(initial)
    private var alternate by mutableStateOf<ReaderJumpLocation?>(null)

    fun opened(at: ReaderJumpLocation, opening: ReaderJumpOpening) {
        when (opening) {
            ReaderJumpOpening.Jump -> jumped(at)
            ReaderJumpOpening.Resume -> {
                val previous = record
                if (previous == null) update(ReaderJumpBackRecord(at))
                else if (previous.furthest?.let { at.compareTo(it) == 0 } == true) {
                    update(previous.copy(lookingUp = false))
                }
            }
            ReaderJumpOpening.Restore -> Unit
        }
    }

    fun turned(from: ReaderJumpLocation, at: ReaderJumpLocation) {
        val wasReturning = alternate != null
        alternate = null
        val previous = record
        val furthest = previous?.furthest
        update(when {
            // A turn from a peek ahead commits that position even if the turn goes backward.
            furthest != null && from > furthest && (previous.lookingUp || wasReturning) ->
                ReaderJumpBackRecord(maxOf(from, at))
            furthest == null || at > furthest -> ReaderJumpBackRecord(at)
            else -> previous.copy(lookingUp = previous.lookingUp && at < furthest)
        })
    }

    fun jumped(at: ReaderJumpLocation) {
        alternate = null
        recordJump(at)
    }

    fun shifted() {
        alternate = null
    }

    fun returned(from: ReaderJumpLocation, at: ReaderJumpLocation) {
        alternate = from.takeIf { it.compareTo(at) != 0 }
        record?.let { update(it.copy(lookingUp = false)) }
    }

    fun target(at: ReaderJumpLocation): ReaderJumpLocation? =
        alternate?.takeIf { it.compareTo(at) != 0 }
            ?: record?.takeIf { it.lookingUp }?.furthest?.takeIf { at.compareTo(it) != 0 }

    fun reset() {
        alternate = null
        // A known empty history must not be seeded from a saved lookup position on reopen.
        update(ReaderJumpBackRecord())
    }

    private fun recordJump(at: ReaderJumpLocation) {
        val previous = record ?: ReaderJumpBackRecord()
        update(previous.copy(lookingUp = previous.furthest?.let { at.compareTo(it) != 0 } == true))
    }

    private fun update(next: ReaderJumpBackRecord) {
        if (record == next) return
        record = next
        onChange(next)
    }
}
