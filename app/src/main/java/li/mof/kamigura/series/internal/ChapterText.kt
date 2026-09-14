package li.mof.kamigura.series.internal

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.VolumeDto
/** Internal to series, not for external use. */
internal fun ChapterDto.displayTitle(): String {
    title?.takeIf { it.isDisplayableChapterLabel() }?.let { return it }
    number.displayText()?.takeIf { it.isDisplayableChapterLabel() }?.let { return it }
    return id.toString()
}

/**
 * "Issue 4" (or "#4" with the short prefix) for a numbered chapter, its own name otherwise,
 * or null when Kavita only has placeholder numbers for it (never the database id).
 */
internal fun ChapterDto.issueLabel(numberPrefix: String = "Issue "): String? {
    val name = title.kavitaLabel() ?: number.displayText().kavitaLabel()
    return name?.let { if (it.toFloatOrNull() != null) "$numberPrefix$it" else it }
}

// Kavita writes -100000 where a file has no chapter number and 100000 on specials.
private fun String?.kavitaLabel(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it != "-100000" && it != "100000" }

/** Internal to series, not for external use. */
internal fun VolumeDto.displayName(): String? {
    name?.takeIf { it.isDisplayableVolumeLabel() }?.let {
        return if (it.toFloatOrNull() != null) "Volume $it" else it
    }
    val numberText = number.displayText()
    return numberText
        ?.takeIf { it.isDisplayableVolumeLabel() }
        ?.let { "Volume $it" }
}

/** Internal to series, not for external use. */
internal fun VolumeDto.displayShortName(): String? {
    return displayName()?.replaceFirst("Volume ", "Vol ")
}

/** Internal to series, not for external use. */
internal fun ChapterDto.releaseDateText(): String? {
    return releaseDate
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("0001-01-01") }
        ?.substringBefore("T")
        ?.takeIf { it.isNotBlank() }
}

private fun String?.isDisplayableChapterLabel(): Boolean {
    return !isNullOrBlank() && this != "-100000"
}

private fun String?.isDisplayableVolumeLabel(): Boolean {
    // 100000 is the volume Kavita files specials under, not a real volume number.
    return !isNullOrBlank() && this != "-100000" && this != "100000" && this != "0"
}


private fun kotlinx.serialization.json.JsonElement?.displayText(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        null -> null
        else -> toString()
    }?.trim('"')
}
