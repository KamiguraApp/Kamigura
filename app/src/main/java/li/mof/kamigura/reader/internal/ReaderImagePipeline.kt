package li.mof.kamigura.reader.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import li.mof.kamigura.FileDimensionDto
import li.mof.kamigura.InvertMode
import li.mof.kamigura.download.OfflinePage
import li.mof.kamigura.download.decodeOfflinePage
import kotlin.math.max
import kotlin.math.min
import coil.request.ErrorResult
import java.util.concurrent.TimeoutException

private const val SmartInvertSampleSize = 64
private const val SmartInvertColorThreshold = 0.1f
private const val SmartInvertWhiteChannelMin = 217
private const val SmartInvertSaturationMin = 0.2f
private const val SmartInvertColorValueMin = 40

private sealed interface PageModelState {
    data object Loading : PageModelState
    data object Unavailable : PageModelState
    data class Ready(val model: Any) : PageModelState
}

private val NegativeColorFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/** Internal to reader, not for external use. */
@Composable
internal fun ReaderPageView(
    cursor: Int,
    pageCount: Int,
    portrait: Boolean,
    pageDimensions: Map<Int, FileDimensionDto>,
    rightToLeft: Boolean,
    pageModel: (Int) -> Any?,
    imageLoader: ImageLoader,
    invertMode: InvertMode,
    whiteThreshold: Float,
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>,
    retryState: ReaderPageRetryState? = null,
    interactiveRetry: Boolean = false,
    pageBackground: Color = Color(0xFF111111),
    singlePageAlignmentOverride: Alignment? = null,
    modifier: Modifier = Modifier,
    retryButtonModifier: Modifier = Modifier
) {
    val layout = readerPageLayout(
        page = cursor,
        pageCount = pageCount,
        portrait = portrait,
        pageDimensions = pageDimensions
    )
    val spread = spreadPagesFor(cursor, rightToLeft)
    Row(modifier) {
        if (layout.singlePage) {
            key(cursor) {
                PageImage(
                    page = cursor,
                    smartFailureFallback = { readerSmartInvertFallback(cursor, pageCount, pageModel, whiteThreshold, invertDecisionCache) },
                    retryState = retryState,
                    interactiveRetry = interactiveRetry,
                    retryButtonModifier = retryButtonModifier,
                    model = pageModel(cursor),
                    imageLoader = imageLoader,
                    label = "Page $cursor",
                    alignment = singlePageAlignmentOverride ?: layout.singleAlignment,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = pageBackground
                )
            }
        } else {
            key(spread.leftPage) {
                PageImage(
                    page = spread.leftPage,
                    smartFailureFallback = { readerSmartInvertFallback(spread.leftPage, pageCount, pageModel, whiteThreshold, invertDecisionCache) },
                    retryState = retryState,
                    interactiveRetry = interactiveRetry,
                    retryButtonModifier = retryButtonModifier,
                    model = pageModel(spread.leftPage),
                    imageLoader = imageLoader,
                    label = "Left ${spread.leftPage}",
                    alignment = Alignment.CenterEnd,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = pageBackground
                )
            }
            key(spread.rightPage) {
                PageImage(
                    page = spread.rightPage,
                    smartFailureFallback = { readerSmartInvertFallback(spread.rightPage, pageCount, pageModel, whiteThreshold, invertDecisionCache) },
                    retryState = retryState,
                    interactiveRetry = interactiveRetry,
                    retryButtonModifier = retryButtonModifier,
                    model = pageModel(spread.rightPage),
                    imageLoader = imageLoader,
                    label = "Right ${spread.rightPage}",
                    alignment = Alignment.CenterStart,
                    invertMode = invertMode,
                    whiteThreshold = whiteThreshold,
                    invertDecisionCache = invertDecisionCache,
                    pageBackground = pageBackground
                )
            }
        }
    }
}

@Composable
private fun RowScope.PageImage(
    page: Int,
    smartFailureFallback: () -> Boolean,
    retryState: ReaderPageRetryState?,
    interactiveRetry: Boolean,
    retryButtonModifier: Modifier,
    model: Any?,
    imageLoader: ImageLoader,
    label: String,
    alignment: Alignment,
    contentScale: ContentScale = ContentScale.Fit,
    invertMode: InvertMode = InvertMode.Off,
    whiteThreshold: Float = 0.5f,
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>,
    pageBackground: Color = Color(0xFF111111)
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(pageBackground)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val targetWidth = with(density) { maxWidth.toPx().toInt() }
        val targetHeight = with(density) { maxHeight.toPx().toInt() }
        val pageModelState by produceState<PageModelState>(
            initialValue = when (model) {
                null -> PageModelState.Unavailable
                is OfflinePage -> PageModelState.Loading
                else -> PageModelState.Ready(model)
            },
            model,
            targetWidth,
            targetHeight
        ) {
            value = when (model) {
                null -> PageModelState.Unavailable
                is OfflinePage -> try {
                    decodeOfflinePage(model, targetWidth, targetHeight)
                        ?.let(PageModelState::Ready)
                        ?: PageModelState.Unavailable
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logReaderImageFailure("Reader offline page decode failed.", t, model)
                    PageModelState.Unavailable
                }
                else -> PageModelState.Ready(model)
            }
        }
        val resolvedModel = (pageModelState as? PageModelState.Ready)?.model
        val canRetry = retryState != null && model is String
        val attempt = if (canRetry) retryState?.attempt(page) ?: 0 else 0
        val requestIsRetry = remember(resolvedModel, attempt) { canRetry && retryState?.retrying(page) == true }
        val requestNetworkGeneration = remember(resolvedModel, attempt) { retryState?.networkGeneration ?: 0 }
        val requestModel = remember(resolvedModel, attempt) {
            if (!canRetry || retryState?.needsFreshRequest(page) != true) resolvedModel else {
                ImageRequest.Builder(ctx)
                    .data(resolvedModel)
                    .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    .diskCachePolicy(CachePolicy.WRITE_ONLY)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .addHeader("Cache-Control", "no-cache")
                    .setParameter(ReaderRetryAttemptParameter, attempt, memoryCacheKey = null)
                    .build()
            }
        }
        val cacheKey = model?.let { ReaderInvertCacheKey(it, whiteThreshold) }
        val cachedInvertDecision = cacheKey?.let(invertDecisionCache::get)
        var loadedDrawable by remember(resolvedModel, imageLoader, attempt) {
            mutableStateOf<Drawable?>(null)
        }
        var diskCacheKey by remember(resolvedModel, imageLoader, attempt) { mutableStateOf<String?>(null) }
        val regionModel = when (model) {
            is OfflinePage -> ReaderRegionModel.Offline(model)
            else -> diskCacheKey?.let { ReaderRegionModel.Cached(imageLoader, it, attempt) }
        }
        val shouldInvert by produceState<Boolean?>(
            initialValue = when (invertMode) {
                InvertMode.Off -> false
                InvertMode.Always -> true
                InvertMode.Smart -> cachedInvertDecision
            },
            resolvedModel,
            invertMode,
            whiteThreshold,
            loadedDrawable,
            cachedInvertDecision
        ) {
            value = when (invertMode) {
                InvertMode.Off -> false
                InvertMode.Always -> true
                InvertMode.Smart -> {
                    val loadedModel = resolvedModel
                    val drawable = loadedDrawable
                    if (loadedModel == null || drawable == null) {
                        null
                    } else {
                        val key = cacheKey ?: ReaderInvertCacheKey(loadedModel, whiteThreshold)
                        cachedInvertDecision ?: try {
                            val decision = withTimeoutOrNull(2_000) {
                                try {
                                    withContext(Dispatchers.Default) { analyzeShouldInvert(drawable, whiteThreshold) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (t: Throwable) {
                                    logReaderImageFailure("Reader Smart Invert drawable analysis failed; trying cache.", t, loadedModel)
                                    analyzeShouldInvert(ctx, imageLoader, loadedModel, whiteThreshold)
                                }
                            }
                            if (decision != null) {
                                invertDecisionCache[key] = decision
                                decision
                            } else {
                                logReaderImageFailure("Reader Smart Invert analysis failed; using chapter fallback.",
                                    TimeoutException("Smart analysis exceeded 2000 ms"), loadedModel)
                                smartFailureFallback()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            logReaderImageFailure("Reader Smart Invert analysis failed; using chapter fallback.", t, loadedModel)
                            smartFailureFallback()
                        }
                    }
                }
            }
        }

        val retryClick: (() -> Unit)? = if (canRetry && interactiveRetry) ({ retryState?.activate(page); Unit }) else null
        when (pageModelState) {
            PageModelState.Loading -> ReaderPageLoadingPlaceholder()
            PageModelState.Unavailable -> ReaderPageUnavailablePlaceholder()
            is PageModelState.Ready -> {
                // Curl rekeys its content while folding; known failures must not restart Coil.
                if (canRetry && retryState?.failed(page) == true) {
                    ReaderPageRetryButton(
                        pageBackground = pageBackground,
                        onClick = retryClick,
                        modifier = retryButtonModifier
                    )
                } else key(attempt) {
                    SubcomposeAsyncImage(
                        model = requestModel,
                        imageLoader = imageLoader,
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize(),
                        alignment = alignment,
                        contentScale = contentScale,
                        colorFilter = if (shouldInvert == true) NegativeColorFilter else null,
                        onSuccess = { state ->
                            loadedDrawable = state.result.drawable
                            diskCacheKey = state.result.diskCacheKey
                            if (canRetry) retryState?.onSuccess(page, attempt)
                        },
                        onError = {
                            logReaderImageFailure("Reader page request failed.", it.result.throwable, resolvedModel)
                            if (canRetry) retryState?.onFailure(page, attempt, requestNetworkGeneration)
                        },
                        loading = {
                            PagePlaceholderContainer {
                                ReaderPageRequestPlaceholder(page, attempt, if (canRetry) retryState else null,
                                    requestIsRetry, pageBackground, retryClick, retryButtonModifier)
                            }
                        },
                        error = {
                            PagePlaceholderContainer {
                                if (canRetry) {
                                    ReaderPageRetryButton(
                                        pageBackground = pageBackground,
                                        onClick = retryClick,
                                        modifier = retryButtonModifier
                                    )
                                } else ReaderPageUnavailablePlaceholder()
                            }
                        },
                        success = {
                            if (invertMode == InvertMode.Smart && shouldInvert == null) {
                                PagePlaceholderContainer {
                                    if (requestIsRetry) ReaderPageRetryButton(pageBackground, retryClick, busy = true, modifier = retryButtonModifier)
                                    else ReaderPageLoadingPlaceholder()
                                }
                            } else {
                                ReaderZoomableImage(regionModel, alignment,
                                    colorFilter = if (shouldInvert == true) NegativeColorFilter else null,
                                    modifier = Modifier.fillMaxSize()) {
                                    SubcomposeAsyncImageContent()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPageRetryButton(
    pageBackground: Color,
    onClick: (() -> Unit)? = null,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    cancelsLoad: Boolean = false
) {
    val foreground = (if (pageBackground.red + pageBackground.green + pageBackground.blue > 1.5f) {
        Color(0xFF202020)
    } else Color.White).let { if (busy) it.copy(alpha = 0.45f) else it }
    val border = BorderStroke(1.5.dp, foreground)
    val shape = RoundedCornerShape(8.dp)
    val buttonModifier = modifier.size(ReaderRetryWidth, ReaderRetryHeight).semantics {
        if (cancelsLoad) stateDescription = "Loading" else if (busy) stateDescription = "Retrying"
    }
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (cancelsLoad) Icons.Outlined.Close else Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(if (cancelsLoad) "Cancel" else "Retry")
        }
    }
    if (onClick != null) {
        OutlinedButton(
            onClick = onClick, modifier = buttonModifier, shape = shape, border = border,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = foreground, containerColor = pageBackground,
                disabledContentColor = foreground, disabledContainerColor = pageBackground
            )
        ) { content() }
    } else {
        Surface(modifier = buttonModifier, shape = shape, color = pageBackground, contentColor = foreground, border = border) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
private fun ReaderPageRequestPlaceholder(
    page: Int,
    attempt: Int,
    retryState: ReaderPageRetryState?,
    requestIsRetry: Boolean,
    background: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier
) {
    LaunchedEffect(retryState, page, attempt) {
        retryState?.let {
            delay(it.loadingDelayRemaining(page, attempt))
            it.allowCancellation(page, attempt)
        }
    }
    if (retryState?.canCancel(page) == true) {
        Box(modifier, contentAlignment = Alignment.Center) {
            ReaderPageRetryButton(background, onClick, cancelsLoad = true)
            Text("Loading", color = Color.Gray, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-24).dp))
        }
    } else if (requestIsRetry) ReaderPageRetryButton(background, onClick, busy = true, modifier = modifier)
    else ReaderPageLoadingPlaceholder()
}

@Composable
private fun PagePlaceholderContainer(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

// Loading is transient and usually pre-warmed, so it stays a whisper. Unavailable persists,
// and a scanned book can legitimately contain a blank page, so it gets a mark that cannot be
// mistaken for one — still static and grey, never an animated indicator.
@Composable
private fun ReaderPageLoadingPlaceholder() {
    Text("...", color = Color.Gray)
}

@Composable
private fun ReaderPageUnavailablePlaceholder() {
    Icon(
        imageVector = Icons.Outlined.BrokenImage,
        contentDescription = "Page unavailable",
        tint = Color.Gray,
        modifier = Modifier.size(36.dp)
    )
}

/** Internal to reader, not for external use. */
internal suspend fun prefetchReaderPages(
    context: Context,
    imageLoader: ImageLoader,
    targets: List<ReaderPrefetchTarget>
) = coroutineScope {
    val semaphore = Semaphore(ReaderPrefetchConcurrency)
    targets.map { target ->
        launch {
            semaphore.withPermit {
                // Prefetch targets are already bounded to the reader's RAM working set.
                // Previously displayed pages remain available through Coil's disk LRU.
                val request = ImageRequest.Builder(context)
                    .data(target.model)
                    .size(target.targetWidth, target.targetHeight)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build()
                try {
                    val result = imageLoader.execute(request)
                    if (result is ErrorResult) logReaderImageFailure("Reader prefetch failed.", result.throwable, target.model)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logReaderImageFailure("Reader prefetch failed.", t, target.model)
                    // Prefetch is opportunistic; the visible page reports its own error.
                }
            }
        }
    }.forEach { it.join() }
}

/** Internal to reader, not for external use. */
internal suspend fun preAnalyzeReaderPages(
    context: Context,
    imageLoader: ImageLoader,
    models: List<String>,
    whiteThreshold: Float,
    invertDecisionCache: MutableMap<ReaderInvertCacheKey, Boolean>
) = coroutineScope {
    val semaphore = Semaphore(ReaderPrefetchConcurrency)
    models.map { model ->
        launch {
            semaphore.withPermit {
                val key = ReaderInvertCacheKey(model, whiteThreshold)
                if (key !in invertDecisionCache) {
                    try {
                        // Pre-analyzing Smart Invert keeps page-turn transitions from flashing unfiltered pages.
                        invertDecisionCache[key] = analyzeShouldInvert(
                            context,
                            imageLoader,
                            model,
                            whiteThreshold
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logReaderImageFailure("Reader Smart Invert cache pre-analysis failed.", t, model)
                        // Visible rendering retries the analysis if pre-analysis fails.
                    }
                }
            }
        }
    }.forEach { it.join() }
}

/**
 * Loads a small downscaled copy of [model] and decides whether the page looks like
 * a text page (mostly white, low color) that should be inverted for night reading.
 * Illustration / color pages return false so they are shown normally.
 */
private suspend fun analyzeShouldInvert(
    ctx: Context,
    imageLoader: coil.ImageLoader,
    model: Any,
    whiteThreshold: Float
): Boolean {
    val request = ImageRequest.Builder(ctx)
        .data(model)
        .size(SmartInvertSampleSize)
        .allowHardware(false)
        .networkCachePolicy(CachePolicy.DISABLED)
        .build()
    val result = imageLoader.execute(request)
    if (result !is SuccessResult) {
        if (result is ErrorResult) throw result.throwable
        error("Smart Invert sample image could not be loaded from cache.")
    }
    return analyzeShouldInvert(result.drawable, whiteThreshold)
}

private fun analyzeShouldInvert(drawable: Drawable, whiteThreshold: Float): Boolean {
    val bitmap = drawable
        .toBitmap(SmartInvertSampleSize, SmartInvertSampleSize)
        .copy(Bitmap.Config.ARGB_8888, false)
        ?: error("Smart Invert sample image could not be copied.")

    val width = bitmap.width
    val height = bitmap.height
    val total = width * height
    if (total <= 0) return false

    val pixels = IntArray(total)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var whiteCount = 0
    var colorCount = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mn >= SmartInvertWhiteChannelMin) whiteCount++
        val saturation = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        if (mx >= SmartInvertColorValueMin && saturation >= SmartInvertSaturationMin) colorCount++
    }

    val whiteRatio = whiteCount.toFloat() / total
    val colorRatio = colorCount.toFloat() / total
    return whiteRatio >= whiteThreshold && colorRatio <= SmartInvertColorThreshold
}

