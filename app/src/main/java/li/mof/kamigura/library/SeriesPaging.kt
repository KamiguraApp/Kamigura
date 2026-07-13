package li.mof.kamigura.library

import li.mof.kamigura.SeriesDto

internal data class SeriesPage(
    val items: List<SeriesDto>,
    val hasMore: Boolean
)

internal fun List<SeriesDto>.appendDistinct(page: List<SeriesDto>): List<SeriesDto> =
    (this + page).distinctBy { it.id }
