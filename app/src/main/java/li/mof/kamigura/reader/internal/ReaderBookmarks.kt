package li.mof.kamigura.reader.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import li.mof.kamigura.BookmarkPageDto
import li.mof.kamigura.KavitaApi

internal fun readerCanManageBookmarks(incognito: Boolean, offline: Boolean): Boolean =
    !incognito && !offline

internal data class ReaderBookmarkState(
    val pages: Set<Int> = emptySet(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val savingPage: Int? = null,
    val message: String? = null,
    val error: String? = null
) {
    val busy: Boolean get() = loading || savingPage != null
}

internal class ReaderBookmarks(
    private val api: KavitaApi,
    private val seriesId: Int,
    private val volumeId: Int,
    private val chapterId: Int,
    private val pageCount: Int
) {
    var state by mutableStateOf(ReaderBookmarkState())
        private set

    suspend fun refresh() {
        if (state.busy) return
        state = state.copy(loading = true, error = null)
        try {
            val pages = api.chapterBookmarks(chapterId)
                .filter { it.chapterId == chapterId && it.page in 0 until pageCount }
                .mapTo(mutableSetOf()) { it.page }
            state = state.copy(pages = pages, loaded = true, loading = false)
        } catch (cancelled: CancellationException) {
            state = state.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            state = state.copy(loading = false, loaded = false,
                error = "Could not load bookmarks. Tap to retry.")
        }
    }

    suspend fun toggle(page: Int) {
        if (!state.loaded || state.busy || page !in 0 until pageCount) return
        val removing = page in state.pages
        val target = BookmarkPageDto(seriesId, volumeId, chapterId, page)
        state = state.copy(savingPage = page, message = null, error = null)
        try {
            if (removing) api.unbookmarkPage(target) else api.bookmarkPage(target)
            state = state.copy(
                pages = if (removing) state.pages - page else state.pages + page,
                savingPage = null,
                message = if (removing) "Bookmark removed from page ${page + 1}."
                    else "Page ${page + 1} bookmarked."
            )
        } catch (cancelled: CancellationException) {
            state = state.copy(savingPage = null, loaded = false)
            throw cancelled
        } catch (_: Exception) {
            // A timed-out request may still have committed on the server. Re-read status
            // before allowing another mutation; never guess or automatically replay the POST.
            state = state.copy(savingPage = null, loaded = false,
                error = "Could not ${if (removing) "remove" else "save"} bookmark for page ${page + 1}. Tap to check status.")
        }
    }
}
