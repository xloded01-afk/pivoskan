package ru.pivoskan.app.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/** Public catalogs only; standard TLS verification, bounded bodies, no credential storage. */
class HttpsTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        checkInterrupted()
        val url = URL(request.url)
        require(url.protocol == "https" && url.host in setOf("magnit.ru", "api.fix-price.com"))
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Pivoskan/1.1 (Android)")
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
        } finally { connection.disconnect() }
    }
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
