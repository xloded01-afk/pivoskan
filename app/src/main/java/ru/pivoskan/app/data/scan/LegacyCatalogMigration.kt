package ru.pivoskan.app.data.scan

import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.pivoskan.app.data.Offer

object LegacyCatalogMigration {
    fun decode(json: String, cityId: String): List<StoredCatalog> = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= CityScanCodec.MAX_CACHE_BYTES)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema").asInt == 1)
        val snapshot = root.getAsJsonObject("snapshot")
        require(snapshot.get("cityId").asString == cityId)
        val gson = Gson()
        snapshot.getAsJsonArray("offers").mapNotNull { item -> runCatching {
            val offer = gson.fromJson(item, Offer::class.java)
            val key = CatalogKey(cityId, offer.store.id, offer.shopId)
            StoredCatalog(key, listOf(offer), offer.fetchedAt, null, complete = false)
                .takeIf { CityScanCodec.valid(it) }
        }.getOrNull() }.groupBy { it.key }.map { (key, rows) ->
            val offers = rows.flatMap { it.offers }.groupBy { it.id }.values.map { it.maxBy { offer -> offer.fetchedAt } }
            // v1 did not persist proof of per-shop pagination completeness.
            StoredCatalog(key, offers, offers.maxOf { it.fetchedAt }, null, complete = false)
        }
    }.getOrDefault(emptyList())
}
