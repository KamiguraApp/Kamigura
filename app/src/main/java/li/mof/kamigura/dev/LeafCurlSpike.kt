package li.mof.kamigura.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import eu.wewox.pagecurl.ExperimentalPageCurlApi
import eu.wewox.pagecurl.config.PageCurlConfig
import eu.wewox.pagecurl.config.rememberPageCurlConfig
import eu.wewox.pagecurl.page.PageCurl
import eu.wewox.pagecurl.page.rememberPageCurlState

// Phase 0 spike for the spread leaf curl (docs/0-17-pagecurl-restart.md).
// Verifies on device: custom back face content (n+2), fold stopping at the spine,
// rapid-tap acceleration, Coil pages during curl (upstream issue #36), RTL mirror trick.
private const val SpikePageCount = 12
private const val SpikeSpreadCount = SpikePageCount / 2

@OptIn(ExperimentalPageCurlApi::class)
@Composable
fun LeafCurlSpikeScreen(onBack: () -> Unit) {
    val spreadState = rememberPageCurlState(turnEndFractionX = 0.5f)
    // Portrait shows single pages with the untouched Stage 1 behavior (spec anchor A6).
    val singleState = rememberPageCurlState()
    var rightToLeft by rememberSaveable { mutableStateOf(false) }
    var coilPages by rememberSaveable { mutableStateOf(false) }
    val spreadConfig = rememberPageCurlConfig(
        // The flap shows the real incoming page; keep only a faint paper tint on top.
        backPageColor = Color(0xFFFAF7F2),
        backPageContentAlpha = 0.96f,
        // PageEdge anchors the paper edge (not the crease) to the finger, which is the
        // physical mapping for a spine-bound leaf: the crease stops at the spine on its own.
        dragInteraction = PageCurlConfig.StartEndDragInteraction(
            pointerBehavior = PageCurlConfig.DragInteraction.PointerBehavior.PageEdge
        )
    )
    val singleConfig = rememberPageCurlConfig()
    // The double scaleX = -1f trick from Stage 0: the outer flip makes library-forward a
    // physical left-edge turn (RTL), the inner flip restores page content orientation.
    val mirror = if (rightToLeft) -1f else 1f

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxHeight > maxWidth
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onBack) { Text("Back") }
                    Text(
                        text = if (portrait) {
                            "Page ${singleState.current + 1} / $SpikePageCount"
                        } else {
                            "Spread ${spreadState.current + 1} / $SpikeSpreadCount"
                        },
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Text("RTL", color = Color.White)
                    Switch(checked = rightToLeft, onCheckedChange = { rightToLeft = it })
                    Text("Coil", color = Color.White)
                    Switch(checked = coilPages, onCheckedChange = { coilPages = it })
                }

                if (portrait) {
                    PageCurl(
                        count = SpikePageCount,
                        state = singleState,
                        config = singleConfig,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = mirror }
                    ) { page ->
                        SpikePage(page, coilPages, mirror)
                    }
                } else {
                    PageCurl(
                        count = SpikeSpreadCount,
                        state = spreadState,
                        config = spreadConfig,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = mirror },
                        backContent = { current, forward ->
                            val pageIndex = leafCurlSpikeBackPageIndex(current, forward)
                            if (pageIndex in 0 until SpikePageCount) {
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(0.5f)
                                            .align(if (forward) Alignment.CenterStart else Alignment.CenterEnd)
                                    ) {
                                        SpikePage(pageIndex, coilPages, mirror)
                                    }
                                }
                            }
                        }
                    ) { spread ->
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                SpikePage(spread * 2, coilPages, mirror)
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                SpikePage(spread * 2 + 1, coilPages, mirror)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun leafCurlSpikeBackPageIndex(current: Int, forward: Boolean): Int =
    if (forward) {
        (current + 1) * 2
    } else {
        (current - 1) * 2 + 1
    }

private val SpikePageColors = listOf(
    Color(0xFF315C8C),
    Color(0xFF704A88),
    Color(0xFF2F7458),
    Color(0xFF9A5C36),
    Color(0xFF8A3F52),
    Color(0xFF4A6B7C)
)

// Deliberately asymmetric page content (corner number + single edge stripe) so any
// mirroring mistake in the flap transforms is immediately visible on device.
@Composable
private fun SpikePage(index: Int, coil: Boolean, mirror: Float) {
    val background = SpikePageColors[index / 2 % SpikePageColors.size]
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = mirror }
            .background(if (coil) Color.DarkGray else background)
    ) {
        if (coil) {
            AsyncImage(
                model = "https://picsum.photos/seed/kamigura$index/500/700",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxHeight()
                .width(10.dp)
                .align(Alignment.CenterEnd)
                .background(Color(0xFFEFC94C))
        )
        Text(
            text = "$index",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
        )
    }
}
