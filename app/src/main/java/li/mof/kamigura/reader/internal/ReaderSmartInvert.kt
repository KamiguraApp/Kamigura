package li.mof.kamigura.reader.internal

internal fun readerSmartInvertFallback(
    page: Int,
    pageCount: Int,
    pageModel: (Int) -> Any?,
    whiteThreshold: Float,
    decisions: Map<ReaderInvertCacheKey, Boolean>
): Boolean {
    if (page !in 0 until pageCount) return true
    for (distance in 1 until pageCount) {
        // On an equal-distance tie, prefer the preceding page. Only this chapter's models
        // and this threshold are eligible; the shared cache may also contain earlier chapters.
        for (candidate in listOf(page - distance, page + distance)) {
            if (candidate !in 0 until pageCount) continue
            val model = pageModel(candidate) ?: continue
            decisions[ReaderInvertCacheKey(model, whiteThreshold)]?.let { return it }
        }
    }
    return true
}
