package ru.pivoskan.app.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import ru.pivoskan.app.data.scan.ScanPolicy

/** Public catalogs only; standard TLS verification, bounded bodies, no credential storage. */
class HttpsTransport(private val allowRequest: () -> Boolean = { true }) : CancellableHttpTransport {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpsURLConnection? = null
    private var lastRequestAt = 0L
    override fun cancel() { cancelled = true; active?.disconnect() }
    override fun execute(request: HttpRequest): HttpResponse {
        var last: IOException? = null
        repeat(ScanPolicy.maxTransientRetries + 1) { attempt ->
            checkInterrupted()
            try {
                val response = requestOnce(request)
                if (response.status !in 500..599 || attempt == ScanPolicy.maxTransientRetries) return response
            } catch (e: IOException) {
                checkInterrupted()
                last = e
                if (attempt == ScanPolicy.maxTransientRetries) throw e
            }
            Thread.sleep(ScanPolicy.retryDelayMs)
        }
        throw last ?: IOException("No response")
    }
    private fun requestOnce(request: HttpRequest): HttpResponse {
        checkInterrupted()
        val wait = ScanPolicy.minimumRequestGapMs - (System.nanoTime() / 1_000_000 - lastRequestAt)
        if (wait > 0) Thread.sleep(wait)
        checkInterrupted()
        lastRequestAt = System.nanoTime() / 1_000_000
        val url = URL(request.url)
        require(url.protocol == "https" && url.host in setOf("magnit.ru", "api.fix-price.com"))
        val connection = url.openConnection() as HttpsURLConnection
        active = connection
        try {
            checkInterrupted()
            connection.connectTimeout = ScanPolicy.directConnectMs
            connection.readTimeout = ScanPolicy.directReadMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Pivoskan/1.4 (Android)")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val headers = connection.headerFields.filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(",") }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val limit = if (status in 200..299) 8 * 1024 * 1024 else 128 * 1024
            val body = if (stream == null) "" else {
                val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(stream) else stream
                input.use { source ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkInterrupted()
                        val size = source.read(buffer)
                        if (size == -1) break
                        if (output.size() + size > limit) throw IOException("Catalog response too large")
                        output.write(buffer, 0, size)
                    }
                    output.toString("UTF-8")
                }
            }
            return HttpResponse(status, body, headers)
        } finally { active = null; connection.disconnect() }
    }
    private fun checkInterrupted() {
        if (cancelled || !allowRequest() || Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
