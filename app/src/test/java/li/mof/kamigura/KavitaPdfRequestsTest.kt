package li.mof.kamigura

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KavitaPdfRequestsTest {
    @Test
    fun seriesFormatDecodesWithoutRequiringItOnOlderResponses() {
        assertEquals(MangaFormat.Pdf, Json.decodeFromString<SeriesDto>(
            """{"id":1,"name":"PDF","format":4}"""
        ).format)
        assertEquals(MangaFormat.Epub, Json.decodeFromString<SeriesDto>(
            """{"id":2,"name":"EPUB","format":3}"""
        ).format)
        assertNull(Json.decodeFromString<SeriesDto>("""{"id":3,"name":"Archive"}""").format)
    }

    @Test
    fun onlyPdfUrlsChange() {
        val oldUrl = "https://example.test/kavita/api/Reader/image?chapterId=42&apiKey=old&page=7"
        val ordinaryUrl = withPdfExtraction(oldUrl, extractPdf = false)
        assertEquals(oldUrl, ordinaryUrl)
        val pdfUrl = withPdfExtraction(oldUrl, extractPdf = true)
        assertTrue(pdfUrl.endsWith("&extractPdf=true"))
    }

    @Test
    fun extractionWaitsPastNormalReadAndCallDeadlinesForBothEndpoints() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            Thread.sleep(300)
            val body = "ready".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .callTimeout(100, TimeUnit.MILLISECONDS)
            .build()
        try {
            val factory = pdfExtractionCallFactory(client)
            for (endpoint in listOf("chapter-info", "image")) {
                val request = Request.Builder()
                    .url("http://127.0.0.1:${server.address.port}/kavita/api/Reader/$endpoint?extractPdf=true")
                    .build()
                factory.newCall(request).execute().use {
                    assertEquals("ready", it.body!!.string())
                }
            }
            for (path in listOf("api/Reader/chapter-info?extractPdf=false",
                "api/Reader/image", "api/Health?extractPdf=true")) {
                val call = factory.newCall(Request.Builder()
                    .url("http://127.0.0.1:${server.address.port}/$path").build())
                assertEquals(TimeUnit.MILLISECONDS.toNanos(100), call.timeout().timeoutNanos())
                try {
                    call.execute().use { fail("Ordinary requests must still time out") }
                } catch (_: IOException) {
                    // The unchanged ordinary client retains its short timeout.
                }
            }
        } finally {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun cancellingAnExtractionInterruptsTheWait() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        try {
            val call = pdfExtractionCallFactory(client).newCall(Request.Builder()
                .url("http://127.0.0.1:${server.address.port}/api/Reader/chapter-info?extractPdf=true")
                .build())
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failed.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
            assertTrue(received.await(5, TimeUnit.SECONDS))
            call.cancel()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
