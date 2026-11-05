package ru.pivoskan.app.data.scan

import com.google.gson.GsonBuilder

data class ScanDiagnosticEvent(val runId: String, val sourceId: String, val shopId: String?,
    val stage: String, val code: String, val at: Long, val count: Int)

object ScanDiagnostics {
    private val stages = setOf("discover", "select", "load", "save", "login", "cancel", "lifecycle")
    private val codes = setOf("OK", "UNKNOWN", "NETWORK", "TIMEOUT", "INVALID_DATA", "LIMIT",
        "STORAGE", "CANCELLED", "UNSUPPORTED", "NEEDS_LOGIN", "NEEDS_ACTION", "INCOMPLETE_LIST")
    private val id = Regex("[a-zA-Z0-9_-]{1,80}")
    fun export(events: List<ScanDiagnosticEvent>, appVersion: String, androidVersion: String, webViewVersion: String): String {
        fun safeId(value: String) = value.takeIf { it.matches(id) } ?: "unknown"
        val clean = events.takeLast(2_000).map { event -> event.copy(
            runId = safeId(event.runId), sourceId = safeId(event.sourceId), shopId = event.shopId?.let(::safeId),
            stage = event.stage.takeIf { it in stages } ?: "load",
            code = event.code.takeIf { it in codes || it.matches(Regex("HTTP_[1-5][0-9]{2}")) } ?: "UNKNOWN",
            at = event.at.coerceAtLeast(0), count = event.count.coerceAtLeast(0)) }
        return GsonBuilder().serializeNulls().create().toJson(mapOf("appVersion" to safeId(appVersion.replace('.', '-')),
            "androidVersion" to safeId(androidVersion.replace('.', '-')),
            "webViewVersion" to safeId(webViewVersion.replace('.', '-')), "events" to clean))
    }
}
