package li.mof.kamigura.reader.internal

import li.mof.kamigura.VolumeDto
import li.mof.kamigura.series.internal.displayName
import li.mof.kamigura.series.internal.displayTitle

/** A non-special chapter in the same order as the series screen. */
internal data class ReaderChapterEntry(
    val chapterId: Int,
    val volumeId: Int,
    val volumeName: String?,
    val chapterName: String
) {
    val displayName: String
        get() = listOfNotNull(volumeName, chapterName).joinToString(" · ")
}

internal data class ReaderChapterNeighbors(
    val previous: ReaderChapterEntry?,
    val next: ReaderChapterEntry?
)

/** Internal to reader, not for external use. */
internal fun readerChapterSequence(volumes: List<VolumeDto>): List<ReaderChapterEntry> =
    volumes.flatMap { volume ->
        volume.chapters
            .asSequence()
            .filterNot { it.isSpecial }
            .map { chapter ->
                ReaderChapterEntry(
                    chapterId = chapter.id,
                    volumeId = chapter.volumeId ?: volume.id,
                    volumeName = volume.displayName(),
                    chapterName = chapter.displayTitle()
                )
            }
            .toList()
    }

/** Internal to reader, not for external use. */
internal fun readerChapterNeighbors(
    chapters: List<ReaderChapterEntry>,
    chapterId: Int
): ReaderChapterNeighbors {
    val index = chapters.indexOfFirst { it.chapterId == chapterId }
    if (index < 0) return ReaderChapterNeighbors(previous = null, next = null)
    return ReaderChapterNeighbors(
        previous = chapters.getOrNull(index - 1),
        next = chapters.getOrNull(index + 1)
    )
}
