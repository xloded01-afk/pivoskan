package ru.pivoskan.app.data.scan

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import ru.pivoskan.app.data.*
import java.net.URI

class CityScanCodec {
    private val gson = GsonBuilder().serializeNulls().enableComplexMapKeySerialization().create()
    fun encode(value: CityScanSnapshot): String {
        require(CityCatalog.city(value.cityId) != null)
        require(value.catalogs.all { it.key.cityId == value.cityId && valid(it) })
        return gson.toJson(mapOf("schema" to 2, "snapshot" to value)).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES) { "City cache limit" }
        }
    }
    fun decode(json: String, cityId: String): CityScanSnapshot? = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_CACHE_BYTES)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema").asInt == 2)
        // Gson bypasses Kotlin defaults for old records. Fill absent enum values before decoding.
        root.getAsJsonObject("snapshot").getAsJsonArray("catalogs").forEach { catalog ->
            catalog.asJsonObject.getAsJsonArray("offers").forEach { entry ->
                CachedOfferDefaults.apply(entry.asJsonObject)
            }
        }
        val value = gson.fromJson(root.get("snapshot"), CityScanSnapshot::class.java)
        require(value.cityId == cityId && CityCatalog.city(cityId) != null)
        val catalogs = value.catalogs.filter { runCatching { it.key.cityId == cityId && valid(it) }.getOrDefault(false) }
            .groupBy { it.key }.values.map { entries -> entries.maxBy { it.checkedAt }.copy(runId = null) }
        val sources = value.sources.filter { source -> runCatching {
            CityCatalog.store(source.sourceId) != null && source.results.keys.all {
                it.cityId == cityId && it.sourceId == source.sourceId
            } && source.discovery?.shops.orEmpty().all { it.cityId == cityId }
        }.getOrDefault(false) }.map { source ->
            if (source.phase in setOf(SourcePhase.WAITING, SourcePhase.RUNNING))
                source.copy(phase = SourcePhase.PARTIAL, current = null)
            else source
        }
        value.copy(runId = null, catalogs = catalogs, sources = sources)
    }.getOrNull()

    companion object {
        // A city has many individual 8 MiB-bounded source responses; the aggregate has its own limit.
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        fun valid(catalog: StoredCatalog): Boolean = runCatching {
            val key = catalog.key
            val city = CityCatalog.city(key.cityId) ?: return false
            if (CityCatalog.store(key.sourceId) == null || catalog.checkedAt <= 0 || catalog.offers.size > ScanPolicy.maxOffersPerShop) return false
            if (key.shopId != null && !key.shopId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) return false
            if (key.sourceId != "fixprice" && key.shopId == null) return false
            val ids = mutableSetOf<String>()
            catalog.offers.all { offer ->
                // Gson can populate null even in Kotlin non-null properties. Reject at the boundary.
                requireNotNull(offer.hours)
                requireNotNull(offer.priceEvidence)
                requireNotNull(offer.beer.brand)
                requireNotNull(offer.beer.packageName)
                requireNotNull(offer.store.name)
                requireNotNull(offer.store.shortName)
                val uri = URI(offer.sourceUrl)
                val host = when (key.sourceId) { "magnit" -> "magnit.ru"; "fixprice" -> "fix-price.com"; "dixy" -> "dixy.ru"; else -> "5ka.ru" }
                offer.cityId == city.id && offer.store.id == key.sourceId && offer.shopId == key.shopId &&
                    offer.priceKopecks > 0 && offer.fetchedAt > 0 && offer.fetchedAt <= catalog.checkedAt &&
                    offer.id.isNotBlank() && ids.add(offer.id) && offer.beer.id.isNotBlank() &&
                    offer.beer.title.isNotBlank() && offer.beer.title.length <= 1024 &&
                    (offer.product?.let { it.sourceProductId.isNotBlank() && it.sourceProductId.length <= 256 &&
                        (it.imageUrl == null || ru.pivoskan.app.ui.ProductImagePolicy.allowed(it.imageUrl)) } ?: true) &&
                    uri.scheme == "https" && uri.host == host && uri.userInfo == null && uri.port in listOf(-1, 443) &&
                    if (key.shopId == null) offer.scope == PriceScope.CITY_CATALOG && offer.address.isBlank()
                    else offer.scope == PriceScope.STORE && CityCatalog.addressMatches(city, offer.address)
            }
        }.getOrDefault(false)
    }
}
