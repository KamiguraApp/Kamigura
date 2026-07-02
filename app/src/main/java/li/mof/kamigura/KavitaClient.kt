package li.mof.kamigura

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

private const val CoverImageDiskCacheBytes = 256L * 1024L * 1024L
private const val ReaderPageDiskCacheBytes = 256L * 1024L * 1024L

class KavitaClient(
    private val context: Context,
    private val store: KavitaSessionStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildApi(): Pair<KavitaApi, OkHttpClient> {
        val session = store.load()
        val rootUrl = normalizeBaseUrl(session.baseUrl)

        // Plain client (no auth) used to mint a fresh JWT from the saved apiKey.
        val mintClient = OkHttpClient.Builder().build()

        // Inject auth dynamically so a freshly refreshed token is picked up by
        // later requests on the same client (no snapshot of the token here).
        val headerInterceptor = Interceptor { chain ->
            val current = runBlocking { store.load() }
            val req = chain.request().newBuilder()
                .apply {
                    if (current.jwt.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${current.jwt}")
                    } else if (current.apiKey.isNotBlank()) {
                        addHeader("x-api-key", current.apiKey)
                    }
                }
                .build()
            chain.proceed(req)
        }

        // On 401, transparently re-authenticate using the saved apiKey (Kavita's
        // JWT expires after ~10 days) so the user is not forced to log in again.
        val tokenAuthenticator = Authenticator { _, response ->
            val current = runBlocking { store.load() }
            if (current.apiKey.isBlank()) return@Authenticator null
            if (responseCount(response) >= 3) return@Authenticator null

            val failedAuth = response.request.header("Authorization")
            // Another request may have already refreshed the token; reuse it.
            if (current.jwt.isNotBlank() && failedAuth != "Bearer ${current.jwt}") {
                return@Authenticator response.request.newBuilder()
                    .header("Authorization", "Bearer ${current.jwt}")
                    .build()
            }

            val newJwt = mintJwtFromApiKey(mintClient, rootUrl, current.apiKey)
                ?: return@Authenticator null
            runBlocking { store.save(current.copy(jwt = newJwt)) }
            response.request.newBuilder()
                .header("Authorization", "Bearer $newJwt")
                .build()
        }

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .authenticator(tokenAuthenticator)
            .build()

        val baseUrl = ("$rootUrl/")
            .toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Server URL must start with http:// or https://")

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create<KavitaApi>() to okHttp
    }

    /** Exchanges the saved apiKey for a fresh JWT via Kavita's Plugin/authenticate. */
    private fun mintJwtFromApiKey(client: OkHttpClient, rootUrl: String, apiKey: String): String? {
        return try {
            val url = "$rootUrl/api/Plugin/authenticate?apiKey=${Uri.encode(apiKey)}&pluginName=Kamigura"
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                json.decodeFromString(UserDto.serializer(), body).token?.takeIf { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            KamiguraLog.w("Could not refresh Kavita JWT from API key.", t)
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var prior = response.priorResponse
        var count = 1
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    fun buildImageLoader(okHttp: OkHttpClient): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient(okHttp)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("cover_image_cache"))
                    .maxSizeBytes(CoverImageDiskCacheBytes)
                    .build()
            }
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    fun buildReaderImageLoader(okHttp: OkHttpClient): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient(okHttp)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("reader_page_cache"))
                    .maxSizeBytes(ReaderPageDiskCacheBytes)
                    .build()
            }
            .build()
    }

    fun pageImageUrl(baseUrl: String, apiKey: String, chapterId: Int, page: Int): String {
        val root = normalizeBaseUrl(baseUrl)
        return "$root/api/Reader/image?chapterId=$chapterId${apiKeyQuery(apiKey)}&page=$page"
    }

    fun seriesCoverUrl(baseUrl: String, apiKey: String, seriesId: Int): String {
        val root = normalizeBaseUrl(baseUrl)
        return "$root/api/Image/series-cover?seriesId=$seriesId${apiKeyQuery(apiKey)}"
    }

    fun chapterCoverUrl(baseUrl: String, apiKey: String, chapterId: Int): String {
        val root = normalizeBaseUrl(baseUrl)
        return "$root/api/Image/chapter-cover?chapterId=$chapterId${apiKeyQuery(apiKey)}"
    }

    fun loginUrl(baseUrl: String): String {
        return "${normalizeBaseUrl(baseUrl)}/api/Account/login"
    }

    private fun normalizeBaseUrl(baseUrl: String): String = normalizeKavitaBaseUrl(baseUrl)

    private fun apiKeyQuery(apiKey: String): String {
        // Coil image requests can be retried outside Retrofit's auth flow, so Kavita image endpoints keep apiKey in the URL.
        return apiKey.takeIf { it.isNotBlank() }?.let { "&apiKey=${Uri.encode(it)}" }.orEmpty()
    }
}

private val Ipv4Regex = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/**
 * Normalizes a user-entered Kavita server URL: trims trailing slashes, drops a
 * trailing /api or /login, and when no scheme is given, prepends one. An explicit
 * http(s):// is always respected. For scheme-less input the host is used to guess:
 * IP literals and localhost get http://, everything else https://. Shared by the
 * API client and the image-URL builders so covers and API calls use the same host.
 */
internal fun normalizeKavitaBaseUrl(baseUrl: String): String {
    var url = baseUrl.trim().trimEnd('/')
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        val host = url.substringBefore('/').substringBefore(':')
        val scheme = if (host.equals("localhost", ignoreCase = true) || Ipv4Regex.matches(host)) {
            "http://"
        } else {
            "https://"
        }
        url = scheme + url
    }
    if (url.endsWith("/api", ignoreCase = true)) {
        url = url.dropLast(4)
    }
    if (url.endsWith("/login", ignoreCase = true)) {
        url = url.dropLast(6)
    }
    return url.trimEnd('/')
}
