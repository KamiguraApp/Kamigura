package li.mof.kamigura.reader.internal

import li.mof.kamigura.ChapterInfoDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.MangaFormat
import retrofit2.HttpException

internal suspend fun loadReaderChapterInfo(
    api: KavitaApi,
    chapterId: Int,
    seriesFormat: Int?
): ChapterInfoDto {
    require(seriesFormat != MangaFormat.Epub) { "EPUB is not supported yet." }
    val info = api.chapterInfo(
        chapterId = chapterId,
        includeDimensions = true,
        extractPdf = seriesFormat == MangaFormat.Pdf
    )
    check((info.pages ?: 0) > 0) { "Chapter has no readable pages" }
    return info
}

/**
 * A proxy in front of Kavita gives up with 504 while the server is still extracting a large
 * PDF. The extraction itself keeps going, so the honest advice is to wait and retry rather
 * than to call the book too big.
 */
internal fun readerLoadErrorMessage(throwable: Throwable): String {
    val status = (throwable as? HttpException)?.code()
    return when (status) {
        504, 502, 408 -> "The server took too long to prepare this file. A large PDF can " +
            "outlast a proxy timeout while the server keeps working on it — wait a few " +
            "minutes and open it again, or raise the timeout on your server."
        else -> throwable.message ?: throwable.toString()
    }
}
