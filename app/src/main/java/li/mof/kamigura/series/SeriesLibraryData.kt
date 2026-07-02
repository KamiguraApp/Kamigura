package li.mof.kamigura.series

import li.mof.kamigura.KavitaApi
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto

internal const val SeriesLibraryPageSize = 300

internal suspend fun KavitaApi.loadAllSeriesForLibrary(
    libraryId: Int,
    pageSize: Int = SeriesLibraryPageSize
): List<SeriesDto> {
    val body = librarySeriesFilter(libraryId)
    return loadPagedSeries(pageSize = pageSize) { pageNumber, requestedPageSize ->
        allSeriesV2(
            body = body,
            pageNumber = pageNumber,
            pageSize = requestedPageSize
        )
    }
        .filter { it.libraryId == null || it.libraryId == libraryId }
        .sortedBy { it.name }
}

internal suspend fun loadPagedSeries(
    pageSize: Int = SeriesLibraryPageSize,
    loadPage: suspend (pageNumber: Int, pageSize: Int) -> List<SeriesDto>
): List<SeriesDto> {
    require(pageSize > 0) { "pageSize must be positive" }

    val result = mutableListOf<SeriesDto>()
    var pageNumber = 0
    while (true) {
        val page = loadPage(pageNumber, pageSize)
        result += page
        if (page.size < pageSize) break
        pageNumber += 1
    }
    return result
}

internal fun librarySeriesFilter(libraryId: Int): SeriesFilterV2Dto {
    return SeriesFilterV2Dto(
        statements = listOf(
            SeriesFilterStatementDto(
                comparison = 0,
                field = 19,
                value = libraryId.toString()
            )
        )
    )
}

internal fun normalizeSeriesSearchQuery(query: String): String {
    return query.replace('\u3000', ' ').trim()
}

internal fun SeriesDto.matchesSeriesTitle(query: String): Boolean {
    return listOfNotNull(name, localizedName, originalName)
        .any { it.contains(query, ignoreCase = true) }
}

internal fun Int.seriesCountLabel(): String = "$this series"
