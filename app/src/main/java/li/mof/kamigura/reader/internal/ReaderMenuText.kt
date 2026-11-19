package li.mof.kamigura.reader.internal

internal fun readerMenuSubtitle(issueLabel: String, position: String): String =
    if (issueLabel.isBlank()) position else "$issueLabel \u2022 $position"

internal fun readerPageLabel(page: Int): String = "p. ${page + 1}"

internal fun readerMenuPosition(page: Int, pages: Int, screen: Int = 0, screens: Int = 1): String =
    buildString {
        append("${readerPageLabel(page)} / $pages")
        if (screens > 1) append(" \u2022 screen ${screen + 1} / $screens")
    }
