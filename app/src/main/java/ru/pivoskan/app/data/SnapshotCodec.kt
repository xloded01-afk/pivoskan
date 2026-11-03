package ru.pivoskan.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/** Versioned cache: rejects old demo files, foreign cities and untrusted product links. */
class SnapshotCodec {
    private val gson = Gson()
    fun encode(snapshot: CatalogSnapshot): String = gson.toJson(JsonObject().apply {
        addProperty("schema", 1)
        add("snapshot", gson.toJsonTree(snapshot))
    })
    fun decode(json: String, cityId: String): CatalogSnapshot? = try {
        val envelope = JsonParser.parseString(json).asJsonObject
        require(envelope.get("schema").asInt == 1)
        val value = gson.fromJson(envelope.get("snapshot"), CatalogSnapshot::class.java)
        require(value.cityId == cityId && CityCatalog.city(cityId) != null && value.updatedAt > 0)
        require(value.shops.all { it.cityId == cityId })
        require(value.offers.all { offer ->
            val uri = URI(offer.sourceUrl)
            offer.cityId == cityId && offer.priceKopecks > 0 && offer.fetchedAt > 0 &&
                ((offer.store.id == "magnit" && uri.host == "magnit.ru" && offer.scope == PriceScope.STORE &&
                    offer.shopId == value.selectedShop?.id && offer.address.isNotBlank()) ||
                (offer.store.id == "fixprice" && uri.host == "fix-price.com" && offer.scope == PriceScope.CITY_CATALOG) ||
                (offer.store.id == "dixy" && uri.host == "dixy.ru" && uri.path.startsWith("/product/") &&
                    offer.scope == PriceScope.STORE && !offer.shopId.isNullOrBlank() &&
                    CityCatalog.addressMatches(CityCatalog.city(cityId)!!, offer.address))) &&
                uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443)
        })
        value.copy(fromCache = value.offers.isNotEmpty(), offers = value.offers.map { it.copy(cached = true) }, sources = value.sources.map { source ->
            if (value.offers.any { it.store.id == source.storeId }) source.copy(status = SourceStatus.CACHED) else source
        })
    } catch (_: Exception) { null }
}
