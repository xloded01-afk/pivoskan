package ru.pivoskan.app

import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.CityCatalog
import java.util.Locale

/** A completed shop is one whose entire rendered catalog has actually been persisted. */
class DixyCityScanState(val total: Int) {
    init { require(total >= 0) }
    var completed: Int = 0
        private set
    val failures = linkedMapOf<String, String>()
    val attempted: Int get() = completed + failures.size
    val remaining: Int get() = total - attempted
    val allCompleted: Boolean get() = total > 0 && completed == total && failures.isEmpty()
    fun saved() { require(attempted < total); completed++ }
    fun failed(address: String, reason: String) {
        require(attempted < total && address !in failures)
        failures[address] = reason
    }

    companion object {
        /** The site's optional AdvCake short lookup can fail while its actual catalog loads. */
        fun isRequiredCatalogRequest(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            val query = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                .filter { it.size == 2 }.associate { it[0] to it[1] }
            uri.scheme == "https" && uri.host == "dixy.ru" && uri.path == "/ajax/listing-json.php" &&
                query["block"] == "product-list" && !query["perPage"].isNullOrBlank()
        }.getOrDefault(false)

        fun normalized(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        fun sameAddress(city: City, planned: String, actual: String): Boolean =
            actual.isNotBlank() && normalized(planned) == normalized(actual) &&
                CityCatalog.addressMatches(city, actual)
    }
}
