package li.mof.kamigura.reader

import li.mof.kamigura.FileDimensionDto
import li.mof.kamigura.reader.internal.ReaderVerticalPosition
import li.mof.kamigura.reader.internal.ReaderVerticalVisibleItem
import li.mof.kamigura.reader.internal.readerVerticalPageAspectRatio
import li.mof.kamigura.reader.internal.readerVerticalPrefetchPageIndicesAround
import li.mof.kamigura.reader.internal.readerVerticalPrefetchSize
import li.mof.kamigura.reader.internal.readerVerticalPosition
import li.mof.kamigura.reader.internal.readerVerticalRetryOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderVerticalScrollTest {
    @Test
    fun retryFollowsTheVisiblePartOfAnOversizedPage() {
        assertEquals(-1120, readerVerticalRetryOffset(0, 3840, 0, 1600))
        assertEquals(0, readerVerticalRetryOffset(-1120, 3840, 0, 1600))
        assertEquals(1120, readerVerticalRetryOffset(-2240, 3840, 0, 1600))
        assertEquals(-1720, readerVerticalRetryOffset(1200, 3840, 0, 1600))
        assertEquals(1800, readerVerticalRetryOffset(-3600, 3840, 0, 1600))
    }

    @Test
    fun fullyVisiblePagesKeepTheirCenteredRetry() {
        assertEquals(0, readerVerticalRetryOffset(200, 500, 0, 1600))
        assertEquals(0, readerVerticalRetryOffset(0, 1600, 0, 1600))
    }

    @Test
    fun partiallyVisibleShortPagesAndPaddedViewportsKeepRetryInView() {
        assertEquals(-150, readerVerticalRetryOffset(1400, 500, 0, 1600))
        assertEquals(-1020, readerVerticalRetryOffset(0, 3840, 100, 1700))
        assertEquals(0, readerVerticalRetryOffset(2000, 500, 0, 1600))
        assertEquals(0, readerVerticalRetryOffset(0, 0, 0, 1600))
    }

    @Test
    fun currentPageIsTheOneWithTheLargestVisibleArea() {
        assertEquals(
            ReaderVerticalPosition.Page(4),
            readerVerticalPosition(
                visibleItems = listOf(
                    ReaderVerticalVisibleItem(index = 4, offset = -700, size = 900),
                    ReaderVerticalVisibleItem(index = 5, offset = 200, size = 900)
                ),
                viewportStartOffset = 0,
                viewportEndOffset = 1_000,
                pageCount = 20
            )
        )
    }

    @Test
    fun boundaryItemsAreDistinguishedFromPages() {
        assertEquals(
            ReaderVerticalPosition.PreviousBoundary,
            readerVerticalPosition(
                visibleItems = listOf(ReaderVerticalVisibleItem(0, 0, 1_000)),
                viewportStartOffset = 0,
                viewportEndOffset = 1_000,
                pageCount = 10
            )
        )
        assertEquals(
            ReaderVerticalPosition.NextBoundary,
            readerVerticalPosition(
                visibleItems = listOf(ReaderVerticalVisibleItem(11, 0, 1_000)),
                viewportStartOffset = 0,
                viewportEndOffset = 1_000,
                pageCount = 10
            )
        )
    }

    @Test
    fun firstReadablePageIsOffsetPastThePreviousBoundary() {
        assertEquals(
            ReaderVerticalPosition.Page(0),
            readerVerticalPosition(
                visibleItems = listOf(ReaderVerticalVisibleItem(1, 0, 1_000)),
                viewportStartOffset = 0,
                viewportEndOffset = 1_000,
                pageCount = 10
            )
        )
    }

    @Test
    fun pageAspectRatioUsesDimensionsAndFallsBackWhenMissing() {
        assertEquals(
            0.5f,
            readerVerticalPageAspectRatio(
                dimension = FileDimensionDto(width = 1_000, height = 2_000),
                fallbackAspectRatio = 0.75f
            ),
            0f
        )
        assertEquals(
            0.75f,
            readerVerticalPageAspectRatio(dimension = null, fallbackAspectRatio = 0.75f),
            0f
        )
    }

    @Test
    fun prefetchKeepsThePreviousPageWarmThenLoadsAhead() {
        assertEquals(
            listOf(4, 6, 7, 8, 9),
            readerVerticalPrefetchPageIndicesAround(
                page = 5,
                pageCount = 20,
                pagesAhead = 4
            )
        )
        assertEquals(
            listOf(1, 2),
            readerVerticalPrefetchPageIndicesAround(
                page = 0,
                pageCount = 3,
                pagesAhead = 4
            )
        )
        assertEquals(
            emptyList<Int>(),
            readerVerticalPrefetchPageIndicesAround(
                page = 5,
                pageCount = 20,
                pagesAhead = 0
            )
        )
    }

    @Test
    fun prefetchSizeMatchesFullWidthVerticalPageGeometry() {
        assertEquals(
            1_080,
            readerVerticalPrefetchSize(
                page = 3,
                pageDimensions = mapOf(3 to FileDimensionDto(width = 1_000, height = 2_000)),
                viewportWidthPx = 1_080f,
                fallbackAspectRatio = 0.5f
            ).width
        )
        assertEquals(
            2_160,
            readerVerticalPrefetchSize(
                page = 3,
                pageDimensions = mapOf(3 to FileDimensionDto(width = 1_000, height = 2_000)),
                viewportWidthPx = 1_080f,
                fallbackAspectRatio = 0.5f
            ).height
        )
        assertEquals(
            1_440,
            readerVerticalPrefetchSize(
                page = 4,
                pageDimensions = emptyMap(),
                viewportWidthPx = 1_080f,
                fallbackAspectRatio = 0.75f
            ).height
        )
    }
}
