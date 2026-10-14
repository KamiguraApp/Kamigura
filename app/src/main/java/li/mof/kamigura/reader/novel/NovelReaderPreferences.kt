package li.mof.kamigura.reader.novel

import androidx.compose.ui.graphics.toArgb
import li.mof.kamigura.InvertMode
import li.mof.kamigura.PageBackground
import li.mof.kamigura.UserReadingProfileDto
import li.mof.kamigura.reader.readerPageBackgroundColor

internal data class NovelReaderOverrides(val writingStyle: Int? = null, val fontSize: Int? = null,
    val background: PageBackground? = null)

internal data class NovelReaderPreferences(
    val writingStyle: Int = 0,
    val fontSize: Int = 100,
    val lineSpacing: Int = 100,
    val margin: Int = 15,
    val readingDirection: Int = 0,
    val background: PageBackground = PageBackground.Paper,
    val usePureColors: Boolean = false
) {
    val vertical: Boolean get() = writingStyle == 1
    val rightToLeft: Boolean get() = vertical || readingDirection == 1
}

internal fun resolveNovelReaderPreferences(
    profile: UserReadingProfileDto?, overrides: NovelReaderOverrides?,
    defaultBackground: PageBackground = PageBackground.Paper, usePureColors: Boolean = false
): NovelReaderPreferences = NovelReaderPreferences(
    writingStyle = overrides?.writingStyle?.takeIf { it in 0..1 }
        ?: profile?.bookReaderWritingStyle?.takeIf { it in 0..1 } ?: 0,
    fontSize = overrides?.fontSize?.takeIf { it in 1..1000 }
        ?: profile?.bookReaderFontSize?.takeIf { it in 1..1000 } ?: 100,
    lineSpacing = profile?.bookReaderLineSpacing?.takeIf { it in 1..1000 } ?: 100,
    margin = profile?.bookReaderMargin?.coerceIn(0, 45) ?: 15,
    readingDirection = profile?.bookReaderReadingDirection?.takeIf { it in 0..1 } ?: 0,
    background = overrides?.background ?: defaultBackground,
    usePureColors = usePureColors
)

internal fun novelDefaultBackground(background: PageBackground, invert: InvertMode): PageBackground =
    if (invert == InvertMode.Off) background else PageBackground.Dark

internal data class NovelReaderColors(val background: Int, val text: Int) {
    val backgroundCss: String get() = cssColor(background)
    val textCss: String get() = cssColor(text)
    private fun cssColor(value: Int) = "#" + (value and 0xFFFFFF).toString(16).padStart(6, '0')
}

internal fun novelReaderColors(background: PageBackground, pure: Boolean): NovelReaderColors {
    val dark = background == PageBackground.Dark
    return NovelReaderColors(readerPageBackgroundColor(dark, pure).toArgb(), when {
        dark && pure -> 0xFFFFFFFF.toInt()
        dark -> 0xFFD9D4CB.toInt()
        pure -> 0xFF000000.toInt()
        else -> 0xFF101010.toInt()
    })
}
