package li.mof.kamigura.reader

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import li.mof.kamigura.reader.internal.readerImageFailureDetails
import org.junit.Assert.*
import org.junit.Test

class ReaderImageDiagnosticsTest {
    private val url = "https://example.test/api/Reader/image?chapterId=1&apiKey=super-secret-value&page=2"

    @Test fun retainsExceptionClassAndReasonWithoutUrlOrBareKnownKey() {
        val message = readerImageFailureDetails(SocketTimeoutException("timeout at $url using super-secret-value"), url)
        assertTrue(message.contains("SocketTimeoutException"))
        assertTrue(message.contains("timeout"))
        assertFalse(message.contains("super-secret-value"))
        assertFalse(message.contains("example.test"))
        assertFalse(message.contains("/api/Reader"))
    }

    @Test fun encodedAndRelativeUrlsAndLabelledSecretsAreRedacted() {
        val message = readerImageFailureDetails(IOException("bad ${URLEncoder.encode(url, "UTF-8")} /api/Reader/image?apiKey=other apiKey=unknown \"token\":\"private\" Bearer header-secret"), url)
        for (secret in listOf("https", "example.test", "/api/Reader", "unknown", "private", "header-secret", "super-secret-value")) {
            assertFalse(secret, message.contains(secret))
        }
    }

    @Test fun nestedFailureMessagesAreSanitizedWithoutPrintingStacks() {
        val error = IllegalStateException("analysis failed", IOException("download $url"))
        val message = readerImageFailureDetails(error, url)
        assertTrue(message.contains("IllegalStateException: analysis failed"))
        assertTrue(message.contains("IOException"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("super-secret-value"))
    }
}
