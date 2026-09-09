package li.mof.kamigura.reader.internal

import li.mof.kamigura.ChapterInfoDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.MangaFormat

internal suspend fun loadReaderChapterInfo(
    api: KavitaApi,
    chapterId: Int,
    seriesFormat: Int?
): ChapterInfoDto {
    require(seriesFormat != MangaFormat.Epub) { "EPUB is not supported in Kamigura." }
    val info = api.chapterInfo(
        chapterId = chapterId,
        includeDimensions = true,
        extractPdf = seriesFormat == MangaFormat.Pdf
    )
    check((info.pages ?: 0) > 0) { "Chapter has no readable pages" }
    return info
}
