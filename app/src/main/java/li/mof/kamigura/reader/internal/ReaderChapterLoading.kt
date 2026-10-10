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
    val info = api.chapterInfo(
        chapterId = chapterId,
        includeDimensions = true,
        extractPdf = seriesFormat == MangaFormat.Pdf
    )
    check((info.pages ?: 0) > 0) { "Chapter has no readable pages" }
    return info
}

internal data class ReaderChapterPages(
    val info: ChapterInfoDto,
    val epubImageUrls: List<String>? = null
)

internal suspend fun loadReaderChapterPages(
    api: KavitaApi,
    chapterId: Int,
    seriesFormat: Int?,
    baseUrl: String,
    parseEpubPage: (String) -> EpubImagePageContent = ::parseEpubImagePage
): ReaderChapterPages {
    val info = loadReaderChapterInfo(api, chapterId, seriesFormat)
    val epubImages = if (seriesFormat == MangaFormat.Epub) {
        loadImageEpubPages(api, chapterId, requireNotNull(info.pages), baseUrl, parseEpubPage)
    } else null
    return ReaderChapterPages(info, epubImages)
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
