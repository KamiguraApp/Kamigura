package li.mof.kamigura.reader.novel

import androidx.compose.ui.graphics.toArgb
import li.mof.kamigura.PageBackground
import li.mof.kamigura.NovelSettings
import li.mof.kamigura.NovelMargins
import li.mof.kamigura.NovelFontSizeRange
import li.mof.kamigura.NovelLineSpacingRange
import li.mof.kamigura.NovelWritingStyle
import li.mof.kamigura.UserReadingProfileDto
import li.mof.kamigura.reader.readerPageBackgroundColor

internal data class NovelReaderOverrides(val writingStyle: Int? = null, val fontSize: Int? = null,
    val background: PageBackground? = null)

internal data class NovelReaderPreferences(
    val writingStyle: Int = 0,
    val fontSize: Int = 100,
    val lineSpacing: Int = 100,
    val margin: Int = NovelMargins.Normal.dp,
    val readingDirection: Int = 0,
    val background: PageBackground = PageBackground.Paper,
    val usePureColors: Boolean = false,
    val automaticWritingStyle: Boolean = false,
    val fonts: NovelSettings = NovelSettings()
) {
    val vertical: Boolean get() = writingStyle == 1
    val rightToLeft: Boolean get() = vertical || readingDirection == 1
}

internal fun resolveNovelReaderPreferences(
    profile: UserReadingProfileDto?, overrides: NovelReaderOverrides?,
    defaultBackground: PageBackground = PageBackground.Paper, usePureColors: Boolean = false,
    novelSettings: NovelSettings = NovelSettings()
): NovelReaderPreferences = NovelReaderPreferences(
    writingStyle = overrides?.writingStyle?.takeIf { it in 0..1 }
        ?: profile?.takeIf { it.kind != 0 }?.bookReaderWritingStyle?.takeIf { it in 0..1 }
        ?: if (novelSettings.writingStyle == NovelWritingStyle.Vertical) 1 else 0,
    automaticWritingStyle = overrides?.writingStyle?.takeIf { it in 0..1 } == null &&
        profile?.takeIf { it.kind != 0 }?.bookReaderWritingStyle?.takeIf { it in 0..1 } == null,
    fontSize = overrides?.fontSize?.takeIf { it in 1..1000 }
        ?: novelSettings.fontSize.coerceIn(NovelFontSizeRange),
    lineSpacing = novelSettings.lineSpacing.coerceIn(NovelLineSpacingRange),
    margin = novelSettings.margins.dp,
    readingDirection = profile?.takeIf { it.kind != 0 }?.bookReaderReadingDirection?.takeIf { it in 0..1 } ?: 0,
    background = overrides?.background ?: defaultBackground,
    usePureColors = usePureColors,
    fonts = novelSettings
)

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
