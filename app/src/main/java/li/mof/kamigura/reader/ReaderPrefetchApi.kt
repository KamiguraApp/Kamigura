package li.mof.kamigura.reader

import li.mof.kamigura.FileDimensionDto

/** Internal to reader, not for external use. */
internal fun readerPrefetchPageIndices(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    turns: Int
): List<Int> {
    return li.mof.kamigura.reader.internal.readerPrefetchPageIndices(
        page = page,
        pageCount = pageCount,
        portrait = portrait,
        pageDimensions = pageDimensions,
        turns = turns
    )
}
