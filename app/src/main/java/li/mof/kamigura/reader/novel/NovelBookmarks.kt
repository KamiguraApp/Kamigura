package li.mof.kamigura.reader.novel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import li.mof.kamigura.CreatePersonalToCDto
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.PersonalToCDto
import retrofit2.HttpException

internal data class NovelBookmarkContext(val anchor: String, val title: String, val visible: Set<Int>)

internal data class NovelBookmarkState(
    val items: List<PersonalToCDto> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val busy get() = loading || saving
}

internal fun novelBookmarkSubtitle(item: PersonalToCDto): String =
    item.chapterTitle?.takeIf { it.isNotBlank() }?.let { "Chapter $it, Page ${item.pageNumber + 1}" }
        ?: "Page ${item.pageNumber + 1}"

internal fun novelBookmarkTitle(base: String, page: Int, items: List<PersonalToCDto>): String {
    val title = base.trim().ifEmpty { "Page ${page + 1}" }
    val existing = items.filter { it.pageNumber == page }.map { it.title }.toSet()
    if (title !in existing) return title
    var suffix = 2
    while ("$title ($suffix)" in existing) suffix++
    return "$title ($suffix)"
}

internal class NovelBookmarks(
    private val api: KavitaApi,
    private val chapterId: Int,
    private val volumeId: Int,
    private val seriesId: Int,
    private val libraryId: Int,
    private val pages: Int
) {
    var state by mutableStateOf(NovelBookmarkState())
        private set

    private suspend fun readItems() = api.personalTableOfContents(chapterId)
        .filter { it.chapterId == chapterId && it.pageNumber in 0 until pages }

    suspend fun refresh() {
        if (state.busy) return
        state = state.copy(loading = true, error = null, message = null)
        try {
            state = state.copy(items = readItems(), loaded = true, loading = false)
        } catch (cancelled: CancellationException) {
            state = state.copy(loading = false)
            throw cancelled
        } catch (error: Exception) {
            state = state.copy(loading = false, loaded = false,
                error = failure("load", error))
        }
    }

    suspend fun toggle(page: Int, context: NovelBookmarkContext) {
        if (!state.loaded || state.busy || page !in 0 until pages) return
        val visible = state.items.filter { it.id in context.visible && it.pageNumber == page }
        mutate(if (visible.isEmpty()) "save" else "remove") {
            if (visible.isNotEmpty()) {
                for (item in visible) delete(item)
            } else {
                create(page, context)
            }
            if (visible.isEmpty()) "Page ${page + 1} bookmarked." else "Bookmarks removed from page ${page + 1}."
        }
    }

    suspend fun remove(item: PersonalToCDto) {
        if (!state.loaded || state.busy || item !in state.items) return
        mutate("remove") { delete(item); "Bookmark removed." }
    }

    private suspend fun delete(item: PersonalToCDto) =
        api.deletePersonalToC(chapterId, item.pageNumber, item.title)

    private suspend fun create(page: Int, context: NovelBookmarkContext) {
        var title = novelBookmarkTitle(context.title, page, state.items)
        repeat(3) { attempt ->
            try {
                api.createPersonalToC(CreatePersonalToCDto(chapterId, volumeId, seriesId, libraryId,
                    page, title, context.anchor))
                return
            } catch (error: HttpException) {
                // Retry only a confirmed duplicate, never an uncertain write or an unrelated 400.
                if (error.code() != 400 || attempt == 2) throw error
                val items = readItems()
                if (items.none { it.pageNumber == page && it.title == title }) throw error
                state = state.copy(items = items)
                title = novelBookmarkTitle(context.title, page, items)
            }
        }
    }

    private suspend fun mutate(action: String, operation: suspend () -> String) {
        state = state.copy(saving = true, error = null, message = null)
        try {
            val message = operation()
            state = state.copy(message = message)
            state = state.copy(items = readItems(), loaded = true, saving = false)
        } catch (cancelled: CancellationException) {
            state = state.copy(saving = false, loaded = false)
            throw cancelled
        } catch (error: Exception) {
            state = state.copy(saving = false, loaded = false, error = failure(action, error))
        }
    }

    private fun failure(action: String, error: Exception): String {
        val reason = when (error) {
            is HttpException -> if (action == "save" && error.code() >= 500)
                "Server error (HTTP ${error.code()}); the EPUB table of contents may be damaged."
            else "Server returned HTTP ${error.code()}."
            else -> "Check your connection."
        }
        return "Could not $action bookmarks. $reason Refresh to check status."
    }
}
