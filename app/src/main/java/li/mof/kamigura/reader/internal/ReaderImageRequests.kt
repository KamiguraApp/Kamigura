package li.mof.kamigura.reader.internal

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call

/** Coil's body-to-disk copy is blocking after headers; cancel its Call explicitly as well. */
private class ReaderImageTransfer {
    private val calls = mutableListOf<Call>()
    private var cancelled = false

    fun attach(call: Call) = synchronized(this) {
        if (cancelled) call.cancel() else calls.add(call)
        Unit
    }

    fun cancel() {
        val pending = synchronized(this) { cancelled = true; calls.toList().also { calls.clear() } }
        pending.forEach(Call::cancel)
    }
}

internal fun readerImageCallFactory(delegate: Call.Factory) = Call.Factory { request ->
    delegate.newCall(request).also { request.tag(ReaderImageTransfer::class.java)?.attach(it) }
}

internal const val ReaderRetryAttemptParameter = "readerRetryAttempt"

/** Share identical decodes while any reader surface still needs them, not across sizes/attempts. */
internal class ReaderImageRequestInterceptor : Interceptor {
    private class Flight(val scope: CoroutineScope, val result: Deferred<ImageResult>,
                         val transfer: ReaderImageTransfer, var users: Int = 0)
    private val flights = mutableMapOf<List<Any?>, Flight>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.data !is String) return chain.proceed(request)
        val context = currentCoroutineContext().minusKey(Job)
        val key = listOf(request.data, request.diskCacheKey, request.memoryCacheKey, chain.size,
            request.scale, request.precision, request.bitmapConfig, request.colorSpace,
            request.allowHardware, request.allowRgb565, request.allowConversionToBitmap,
            request.premultipliedAlpha, request.transformations, request.fetcherFactory,
            request.decoderFactory, request.headers, request.parameters, request.tags, request.memoryCachePolicy,
            request.diskCachePolicy, request.networkCachePolicy)
        val flight = synchronized(flights) {
            flights.getOrPut(key) {
                val scope = CoroutineScope(context + SupervisorJob())
                val transfer = ReaderImageTransfer()
                val tagged = request.newBuilder().tag(ReaderImageTransfer::class.java, transfer).build()
                // Memory hits must finish inline, before Curl paints this newly composed sheet.
                Flight(scope, scope.async(start = CoroutineStart.UNDISPATCHED) {
                    val result = chain.proceed(tagged)
                    if (result is ErrorResult && tagged.networkCachePolicy.readEnabled &&
                        tagged.diskCachePolicy != CachePolicy.DISABLED && readerDiskCacheCanBypass(result.throwable)
                    ) {
                        // Keep the same transfer tag, memory identity and consumers for the fallback.
                        chain.proceed(tagged.newBuilder().diskCachePolicy(CachePolicy.DISABLED).build())
                    } else result
                }, transfer)
            }.also { it.users++ }
        }
        try {
            return when (val result = flight.result.await()) {
                is SuccessResult -> result.copy(request = request,
                    drawable = result.drawable.constantState?.newDrawable(request.context.resources)?.mutate() ?: result.drawable)
                is ErrorResult -> result.copy(request = request)
            }
        } finally {
            synchronized(flights) {
                if (--flight.users == 0) {
                    if (flights[key] === flight) flights.remove(key)
                    if (!flight.result.isCompleted) flight.transfer.cancel()
                    flight.scope.cancel()
                }
            }
        }
    }
}
