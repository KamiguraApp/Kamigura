package li.mof.kamigura.reader.novel

import li.mof.kamigura.UserReadingProfileDto

internal data class NovelReaderOverrides(val writingStyle: Int? = null, val fontSize: Int? = null)

internal data class NovelReaderPreferences(
    val writingStyle: Int = 0,
    val fontSize: Int = 100,
    val lineSpacing: Int = 100,
    val margin: Int = 15,
    val readingDirection: Int = 0
) {
    val vertical: Boolean get() = writingStyle == 1
    val rightToLeft: Boolean get() = vertical || readingDirection == 1
}

internal fun resolveNovelReaderPreferences(
    profile: UserReadingProfileDto?, overrides: NovelReaderOverrides?
): NovelReaderPreferences = NovelReaderPreferences(
    writingStyle = overrides?.writingStyle?.takeIf { it in 0..1 }
        ?: profile?.bookReaderWritingStyle?.takeIf { it in 0..1 } ?: 0,
    fontSize = overrides?.fontSize?.takeIf { it in 1..1000 }
        ?: profile?.bookReaderFontSize?.takeIf { it in 1..1000 } ?: 100,
    lineSpacing = profile?.bookReaderLineSpacing?.takeIf { it in 1..1000 } ?: 100,
    margin = profile?.bookReaderMargin?.coerceIn(0, 45) ?: 15,
    readingDirection = profile?.bookReaderReadingDirection?.takeIf { it in 0..1 } ?: 0
)
