package li.mof.kamigura.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial
import li.mof.kamigura.ui.theme.ReadingProgressInProgress
import li.mof.kamigura.ui.theme.ReadingProgressRead
import li.mof.kamigura.ui.theme.ReadingProgressTrack

@Composable
internal fun BrowsePageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF202222))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = if (onBack == null) 16.dp else 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = if (onBack == null) 0.dp else 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            content = content
        )
    }
}

@Composable
internal fun <T> PosterGrid(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(items = items, key = key) { item -> itemContent(item) }
    }
}

@Composable
internal fun SeriesPosterCard(
    series: SeriesDto,
    session: KavitaSession,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                if (session.baseUrl.isNotBlank() && session.apiKey.isNotBlank()) {
                    AsyncImage(
                        model = seriesCoverUrl(session, series.id),
                        contentDescription = series.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        seriesInitial(series.name),
                        color = Color(0xFFB9BDBD),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Box(Modifier.fillMaxWidth()) {
                SeriesReadingProgressBar(
                    progress = series.readingProgress(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(3.dp)
                )
                Text(
                    text = series.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SeriesReadingProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val boundedProgress = (progress ?: 0f).coerceIn(0f, 1f)
    val fillColor = if (boundedProgress >= 1f) ReadingProgressRead else ReadingProgressInProgress
    Box(modifier = modifier.background(ReadingProgressTrack)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(boundedProgress)
                .background(fillColor)
        )
    }
}

private fun SeriesDto.readingProgress(): Float? {
    val total = pages ?: return null
    if (total <= 0) return null
    val read = (pagesRead ?: 0).coerceIn(0, total)
    return read.toFloat() / total.toFloat()
}
