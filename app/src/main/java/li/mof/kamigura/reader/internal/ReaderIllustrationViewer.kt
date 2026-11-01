package li.mof.kamigura.reader.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

@Composable
internal fun ReaderIllustrationViewer(url: String, imageLoader: ImageLoader, background: Color,
    foreground: Color, zoom: ReaderZoomPanState, onZoom: (ReaderZoomPanState) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnZoom by rememberUpdatedState(onZoom)
    var diskKey by remember(url) { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().background(background).semantics { contentDescription = "Illustration viewer" }) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val bounds = readerPanBoundsPx(width, height, zoom.userScale)
        LaunchedEffect(width, height) {
            val current = latestZoom
            val limit = readerPanBoundsPx(width, height, current.userScale)
            val clamped = current.copy(offsetX = current.offsetX.coerceIn(-limit.maxX, limit.maxX),
                offsetY = current.offsetY.coerceIn(-limit.maxY, limit.maxY))
            if (clamped != current) latestOnZoom(clamped)
        }
        val request = remember(url, width, height) { ImageRequest.Builder(context).data(url)
            .size(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)).build() }
        SubcomposeAsyncImage(request, imageLoader = imageLoader, contentDescription = "Illustration",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.userScale; scaleY = zoom.userScale
                translationX = zoom.offsetX; translationY = zoom.offsetY
            }, onSuccess = { diskKey = it.result.diskCacheKey },
            loading = { Text("...", color = foreground.copy(alpha = .45f)) },
            error = { Text("Could not load this image.", color = foreground) },
            success = {
                ReaderZoomableImage(diskKey?.let { ReaderRegionModel.Cached(imageLoader, it) },
                    modifier = Modifier.fillMaxSize(), motion = ReaderImageZoomMotion(zoom.userScale, zoom.offsetX, zoom.offsetY)) {
                    SubcomposeAsyncImageContent()
                }
            })
        ReaderTapLayer(false, {}, {}, {}, {}, {}, width, zoomPanEnabled = true,
            panOffsetX = zoom.offsetX, panOffsetY = zoom.offsetY, panMaxX = bounds.maxX, panMaxY = bounds.maxY,
            onPan = { x, y -> onZoom(zoom.copy(offsetX = x, offsetY = y)) },
            onDoubleTap = { point -> onZoom(zoom.withDoubleTapZoom(point, 1f, width, height, ReaderZoomPanState())) },
            onTransform = { scale, pan, focal -> onZoom(zoom.withTransform(scale, pan, focal, 1f, width, height)) })
        FilledIconButton(onClick = onClose,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = background.copy(alpha = .92f), contentColor = foreground),
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close illustration", tint = foreground)
        }
    }
}
