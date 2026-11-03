package ru.pivoskan.app.data.remote

data class HttpRequest(val url: String, val method: String = "GET", val body: String? = null,
    val headers: Map<String, String> = emptyMap())
data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}
fun interface HttpTransport { fun execute(request: HttpRequest): HttpResponse }

/** Sources stop at access restrictions; no authenticated tokens or anti-bot bypass. */
class SourceException(val blocked: Boolean = false, message: String = "Source unavailable") : Exception(message)
