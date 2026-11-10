package li.mof.kamigura.reader.internal

import java.net.URLEncoder
import li.mof.kamigura.KamiguraLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun readerImageFailureDetails(error: Throwable, model: Any? = null): String {
    val source = model as? String
    val url = source?.toHttpUrlOrNull()
    val secrets = url?.queryParameterNames.orEmpty().filter {
        it.lowercase() in setOf("apikey", "api_key", "token", "jwt", "access_token")
    }.flatMap { url!!.queryParameterValues(it).filterNotNull() }.filter { it.isNotBlank() }
    fun safe(value: String): String {
        var message = value
        (listOfNotNull(source, url?.let { "${it.host}:${it.port}" }, url?.host, url?.encodedPath?.takeIf { it != "/" }) +
            secrets.flatMap { listOf(it, URLEncoder.encode(it, "UTF-8")) })
            .sortedByDescending { it.length }.forEach { if (it.isNotBlank()) message = message.replace(it, "[redacted]") }
        message = ImageDiagnosticUrl.replace(message, "[redacted]")
        message = ImageDiagnosticRelativeUrl.replace(message, "[redacted]")
        message = ImageDiagnosticBearer.replace(message, "[redacted]")
        message = ImageDiagnosticSecret.replace(message, "[redacted]")
        message = ImageDiagnosticJwt.replace(message, "[redacted]")
        return message.replace('\n', ' ').replace('\r', ' ').take(240)
    }
    return generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(4)
        .joinToString("; caused by ") { "${it.javaClass.simpleName}: ${safe(it.message ?: "no message")}" }
}

internal fun logReaderImageFailure(message: String, error: Throwable, model: Any? = null) {
    // Never pass the throwable: its stack/cause messages can contain authenticated image URLs.
    KamiguraLog.w("$message ${readerImageFailureDetails(error, model)}")
}

private val ImageDiagnosticUrl = Regex("(?i)(?:[a-z][a-z0-9+.-]*(?::|%3a)(?:/|%2f){2}|//)[^\\s<>\"']+")
private val ImageDiagnosticRelativeUrl = Regex("(?:^|[\\s\"'])(/[^\\s<>\"']+)")
private val ImageDiagnosticBearer = Regex("(?i)\\bBearer\\s+[^\\s,;\"'}]+")
private val ImageDiagnosticSecret = Regex("(?i)(?:api[_-]?key|authorization|jwt|access_token|token)[\"']?\\s*[:=]\\s*[\"']?[^\\s,;\"'}]+")
private val ImageDiagnosticJwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)?")
