package li.mof.kamigura.reader.novel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import li.mof.kamigura.BookChapterItemDto
import li.mof.kamigura.KavitaApi
import retrofit2.HttpException

internal data class NovelContentsEntry(val title: String, val part: String, val page: Int, val depth: Int)

internal fun novelContentsAnchor(part: String): String? = when {
    part.isBlank() -> null
    '"' !in part -> "id(\"$part\")"
    '\'' !in part -> "id('$part')"
    else -> "id(concat(${part.split('"').joinToString(", '\"', ") { "\"$it\"" }}))"
}

internal fun flattenNovelContents(items: List<BookChapterItemDto>, pages: Int): List<NovelContentsEntry> = buildList {
    fun append(nodes: List<BookChapterItemDto>, depth: Int) {
        for (node in nodes) {
            if (node.page in 0 until pages) add(NovelContentsEntry(node.title, node.part, node.page, depth))
            append(node.children, depth + 1)
        }
    }
    append(items, 0)
}

internal fun currentNovelContentsEntry(entries: List<NovelContentsEntry>, page: Int, reachedParts: Set<String>): Int? =
    entries.indices.lastOrNull { index ->
        val entry = entries[index]
        entry.page < page || entry.page == page && (entry.part.isBlank() || entry.part in reachedParts)
    }

internal data class NovelContentsState(
    val entries: List<NovelContentsEntry> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

internal class NovelContents(private val api: KavitaApi, private val chapterId: Int, private val pages: Int) {
    var state by mutableStateOf(NovelContentsState())
        private set

    suspend fun refresh() {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        try {
            state = NovelContentsState(flattenNovelContents(api.bookContents(chapterId), pages), loaded = true)
        } catch (cancelled: CancellationException) {
            state = state.copy(loading = false)
            throw cancelled
        } catch (error: Exception) {
            val reason = when (error) {
                is HttpException -> if (error.code() >= 500)
                    "Server error (HTTP ${error.code()}); the EPUB table of contents may be damaged."
                else "Server returned HTTP ${error.code()}."
                else -> "Check your connection."
            }
            state = state.copy(loading = false, loaded = false, error = "Could not load contents. $reason")
        }
    }
}
