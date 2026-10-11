package li.mof.kamigura.reader.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import li.mof.kamigura.KamiguraLog

internal val ReaderRetryWidth = 112.dp
internal val ReaderRetryHeight = 48.dp

private enum class ReaderPageRetryStatus { Failed, Retrying }

internal class ReaderPageRetryState {
    private val attempts = mutableStateMapOf<Int, Int>()
    private val statuses = mutableStateMapOf<Int, ReaderPageRetryStatus>()
    private var visiblePages = emptySet<Int>()
    var networkGeneration: Int = 0
        private set

    fun attempt(page: Int): Int = attempts[page] ?: 0
    fun failed(page: Int): Boolean = statuses[page] == ReaderPageRetryStatus.Failed
    fun retrying(page: Int): Boolean = statuses[page] == ReaderPageRetryStatus.Retrying
    fun hasRetryPlaceholder(page: Int): Boolean = statuses.containsKey(page)
    fun needsFreshRequest(page: Int): Boolean = retrying(page)

    fun onFailure(page: Int, attempt: Int, requestNetworkGeneration: Int = networkGeneration) {
        // Curl may retain multiple copies. Ignore callbacks from requests superseded by Retry.
        if (attempt == attempt(page)) {
            statuses[page] = ReaderPageRetryStatus.Failed
            // A request started before reconnection can finish failing just after it.
            if (requestNetworkGeneration < networkGeneration) retry(page)
        }
    }

    fun onSuccess(page: Int, attempt: Int) {
        if (attempt != attempt(page)) return
        statuses.remove(page)
    }

    fun retry(page: Int): Boolean {
        if (!failed(page)) return false
        statuses[page] = ReaderPageRetryStatus.Retrying
        attempts[page] = attempt(page) + 1
        return true
    }

    fun updateVisiblePages(pages: Set<Int>) {
        (pages - visiblePages).forEach(::retry)
        visiblePages = pages
    }

    fun onNetworkRestored() {
        networkGeneration++
        statuses.keys.toList().forEach(::retry)
    }
}

internal data class ReaderPageRetryTarget(val page: Int, val bounds: Rect, val enabled: Boolean = true)

internal fun readerPageRetryTargets(
    pagesInPhysicalOrder: List<Int>,
    failed: (Int) -> Boolean,
    viewportWidth: Float,
    viewportHeight: Float,
    buttonWidth: Float,
    buttonHeight: Float,
    retrying: (Int) -> Boolean = { false }
): List<ReaderPageRetryTarget> {
    if (pagesInPhysicalOrder.isEmpty()) return emptyList()
    val slotWidth = viewportWidth / pagesInPhysicalOrder.size
    return pagesInPhysicalOrder.mapIndexedNotNull { slot, page ->
        if (!failed(page)) return@mapIndexedNotNull null
        val centerX = slotWidth * (slot + 0.5f)
        val centerY = viewportHeight / 2f
        val width = buttonWidth.coerceAtMost(slotWidth)
        val height = buttonHeight.coerceAtMost(viewportHeight)
        ReaderPageRetryTarget(page, Rect(
            centerX - width / 2f, centerY - height / 2f,
            centerX + width / 2f, centerY + height / 2f
        ), enabled = !retrying(page))
    }
}

internal fun List<ReaderPageRetryTarget>.retryPageAt(position: Offset): Int? =
    firstOrNull { it.bounds.contains(position) }?.page

@Composable
internal fun ReaderPageRetryEffects(
    state: ReaderPageRetryState,
    visiblePages: Set<Int>,
    enabled: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state, visiblePages, enabled) {
        state.updateVisiblePages(if (enabled) visiblePages else emptySet())
    }
    DisposableEffect(context, state, enabled) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var registered = false
        if (enabled) {
            var availableNetwork = manager.activeNetwork
            var validated = manager.getNetworkCapabilities(availableNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        if (availableNetwork != network) {
                            availableNetwork = network
                            validated = false
                            // Kavita may be LAN-only; public Internet validation is not required.
                            state.onNetworkRestored()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    scope.launch {
                        if (availableNetwork == network) {
                            availableNetwork = null
                            validated = false
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    scope.launch {
                        if (availableNetwork != network) return@launch
                        // If the first request beat routing/validation, give it one more chance.
                        if (available && !validated) state.onNetworkRestored()
                        validated = available
                    }
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
                registered = true
            } catch (error: RuntimeException) {
                KamiguraLog.w("Could not observe reader network recovery.", error)
            }
            onDispose { if (registered) manager.unregisterNetworkCallback(callback) }
        } else {
            onDispose { }
        }
    }
}
