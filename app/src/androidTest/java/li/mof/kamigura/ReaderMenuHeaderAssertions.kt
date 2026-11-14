package li.mof.kamigura

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

internal fun SemanticsNodeInteraction.assertReaderSubtitleFits() {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val result = layouts.single()
    assertFalse("Subtitle ${result.size}: width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, ellipsis=${result.isLineEllipsized(0)}",
        result.hasVisualOverflow)
}

internal fun ComposeTestRule.captureReaderHeader(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val bitmap = onNodeWithTag("Reader menu header").captureToImage().asAndroidBitmap()
    File(context.cacheDir, "v031-menu-$name.png").outputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
