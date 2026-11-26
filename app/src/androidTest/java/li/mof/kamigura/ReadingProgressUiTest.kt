package li.mof.kamigura

import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import li.mof.kamigura.series.IssueDetailSideSheet
import li.mof.kamigura.series.internal.ChapterCardItem
import li.mof.kamigura.series.internal.ChapterGridCard
import li.mof.kamigura.ui.KavitaCoverAspectRatio
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.theme.KamiguraBackground
import li.mof.kamigura.ui.theme.KamiguraTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReadingProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private val amounts = listOf(0, 35, 100)

    @Test fun captureSeriesAndIssueCards() {
        compose.setContent { Cards() }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        amounts.forEachIndexed { index, pagesRead ->
            for ((type, card) in listOf("series" to seriesCard(index), "issue" to issueCard(index))) {
                val bitmap = card.captureToImage().asAndroidBitmap()
                cache.resolve("reading-progress-$type-$pagesRead.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
    }

    @Test fun progressIsAnnouncedOnTheExistingClickableCards() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.setContent { Cards() }
        val expected = listOf("Unread", "35% read", "Read")
        expected.forEachIndexed { index, description ->
            val progress = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)
            seriesCard(index).assert(progress)
            issueCard(index).assert(progress)
        }
        assertNoSeparateProgressNode()

        if (Build.VERSION.SDK_INT >= 30) {
            var nodes = emptyList<AccessibilityNodeInfo>()
            compose.waitUntil(5_000) {
                nodes = automation.rootInActiveWindow?.let { accessibilityNodes(it).toList() }.orEmpty()
                nodes.any { it.isClickable && it.stateDescription?.toString() in expected }
            }
            val progressCards = nodes.filter { it.stateDescription?.toString() in expected }
            assertEquals(
                expected.flatMap { listOf(it, it) }.sorted(),
                progressCards.map { it.stateDescription.toString() }.sorted()
            )
            assertTrue(progressCards.all { it.isClickable })
            assertTrue(nodes.none { it.className.toString() == "android.widget.ProgressBar" })
        }
    }

    @Test fun sheetCoverAnnouncesProgressWithoutASeparateIndicator() {
        compose.setContent {
            KamiguraTheme {
                IssueDetailSideSheet(
                    visible = true,
                    seriesName = "Series",
                    volume = VolumeDto(id = 1, name = "1"),
                    chapter = ChapterDto(id = 1, title = "1", pages = 100, pagesRead = 35),
                    fileSizeBytes = null,
                    downloadRecord = null,
                    loading = false,
                    actionBusy = false,
                    session = KavitaSession(),
                    actionColor = Color.Green,
                    onDismissRequest = {},
                    onRead = {},
                    onReadIncognito = {},
                    onMarkRead = {},
                    onMarkUnread = {},
                    onDownload = {},
                    onRemoveDownload = {}
                )
            }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "35% read"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        assertNoSeparateProgressNode()
    }

    @Test fun progressStripSitsBelowTheCoverWithoutOverlappingIt() {
        compose.setContent { Cards() }
        val strips = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility), useUnmergedTree = true
        ).fetchSemanticsNodes().map { it.boundsInRoot }
        val stripHeight = with(compose.density) { 3.dp.toPx() }
        amounts.indices.forEach { index ->
            for ((name, card) in listOf("series $index" to seriesCard(index), "issue $index" to issueCard(index))) {
                val bounds = card.fetchSemanticsNode().boundsInRoot
                val strip = strips.single { bounds.contains(it.center) }
                // Both cards draw the cover at the Kavita aspect from the card's top edge.
                val coverBottom = bounds.top + bounds.width / KavitaCoverAspectRatio
                assertTrue("$name strip starts inside the cover: $strip, cover ends at $coverBottom",
                    strip.top >= coverBottom - 0.5f)
                assertEquals("$name strip top", coverBottom, strip.top, 1f)
                assertEquals("$name strip height", stripHeight, strip.height, 1f)
                assertEquals("$name strip left", bounds.left, strip.left, 1f)
                assertEquals("$name strip right", bounds.right, strip.right, 1f)
            }
        }
    }

    private fun assertNoSeparateProgressNode() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
    }

    private fun accessibilityNodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(accessibilityNodes(it)) }
    }

    @Composable
    private fun Cards() {
        KamiguraTheme {
            Column(
                Modifier.background(KamiguraBackground),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    amounts.forEachIndexed { index, pagesRead ->
                        SeriesPosterCard(
                            SeriesDto(id = index + 1, name = "Series ${index + 1}", pages = 100, pagesRead = pagesRead),
                            KavitaSession(),
                            Modifier.width(191.dp).clickable {}
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    amounts.forEachIndexed { index, pagesRead ->
                        Column(Modifier.width(191.dp)) {
                            ChapterGridCard(
                                ChapterCardItem(
                                    VolumeDto(id = 1, name = "1"),
                                    ChapterDto(id = index + 1, title = "${index + 1}", pages = 100, pagesRead = pagesRead)
                                ),
                                KavitaSession(),
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
    }

    private fun seriesCard(index: Int): SemanticsNodeInteraction =
        compose.onNode(hasClickAction() and hasText("Series ${index + 1}"))

    private fun issueCard(index: Int): SemanticsNodeInteraction =
        compose.onNode(hasClickAction() and hasText("#${index + 1} \u2022 Vol. 1"))
}
