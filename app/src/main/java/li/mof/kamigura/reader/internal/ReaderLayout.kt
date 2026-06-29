package li.mof.kamigura.reader.internal

import androidx.compose.ui.Alignment
import li.mof.kamigura.FileDimensionDto

/** Internal to reader, not for external use. */
internal fun spreadPagesFor(page: Int, rightToLeft: Boolean): ReaderSpreadPages {
    val firstPage = page
    val secondPage = page + 1
    return if (rightToLeft) {
        ReaderSpreadPages(leftPage = secondPage, rightPage = firstPage)
    } else {
        ReaderSpreadPages(leftPage = firstPage, rightPage = secondPage)
    }
}

/** Internal to reader, not for external use. */
internal fun readerPageLayout(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>
): ReaderPageLayout {
    if (portrait) {
        return ReaderPageLayout(
            singlePage = true,
            nextStep = 1,
            previousStep = 1,
            singleAlignment = Alignment.Center
        )
    }

    val currentPageIsWide = pageDimensions.pageIsWide(page)
    val pairedPageIsWide = pageDimensions.pageIsWide(page + 1)
    // The cover (page 0) is always shown alone, matching Kavita's pairing, so the
    // first spread starts at page 1. Foldout/wide misalignment beyond this is still
    // corrected manually with the Shift +1/-1 buttons.
    val isCover = page == 0
    val singlePage = isCover || currentPageIsWide || pairedPageIsWide || page + 1 >= pageCount

    return ReaderPageLayout(
        singlePage = singlePage,
        nextStep = if (singlePage) 1 else 2,
        previousStep = if (currentPageIsWide || pageDimensions.pageIsWide(page - 1) || page - 1 == 0) 1 else 2,
        singleAlignment = Alignment.Center
    )
}

/** Internal to reader, not for external use. */
internal fun readerVisiblePageIndices(
    page: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>
): List<Int> {
    if (page !in 0 until pageCount) return emptyList()
    val layout = readerPageLayout(page, pageCount, portrait, pageDimensions)
    if (layout.singlePage) return listOf(page)
    return listOf(page, page + 1).filter { it in 0 until pageCount }
}
