package ru.pivoskan.app.data

import ru.pivoskan.app.data.remote.SourceException

interface CatalogCache {
    fun read(cityId: String): CatalogSnapshot?
    fun readShop(cityId: String, shopId: String): CatalogSnapshot? =
        read(cityId)?.takeIf { it.selectedShop?.id == shopId }
    fun write(snapshot: CatalogSnapshot)
}

/** Business rules for partial failures. A previous shop's prices never become another shop's cache. */
class CatalogLoader(
    private val cache: CatalogCache,
    private val findShops: (City) -> List<RemoteShop>,
    private val loadMagnit: (City, RemoteShop) -> List<Offer>,
    private val loadFix: (City) -> List<Offer>,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun load(city: City, preferredShopId: String?, onPartial: (CatalogSnapshot) -> Unit = {}): CatalogSnapshot {
        val latestCity = cache.read(city.id)
        val previous = preferredShopId?.let { cache.readShop(city.id, it) }?.let { shopCache ->
            shopCache.copy(offers = shopCache.offers.filter { it.store.id == "magnit" } +
                (latestCity?.offers ?: shopCache.offers).filter { it.store.id == "fixprice" })
        } ?: latestCity
        var shops = previous?.shops.orEmpty()
        var selected = previous?.selectedShop?.takeIf { preferredShopId == null || it.id == preferredShopId }
        val offers = previous?.offers.orEmpty().filter {
            it.cityId == city.id && (it.store.id == "fixprice" || (it.store.id == "magnit" && it.shopId == selected?.id))
        }.map { it.copy(cached = true) }.toMutableList()
        val states = linkedMapOf(
            "magnit" to SourceState("magnit", SourceStatus.LOADING),
            "fixprice" to SourceState("fixprice", SourceStatus.LOADING),
            "dixy" to SourceState("dixy", SourceStatus.BLOCKED),
            "pyaterochka" to SourceState("pyaterochka", SourceStatus.BLOCKED)
        )
        listOf("magnit", "fixprice").forEach { id ->
            val count = offers.count { it.store.id == id }
            if (count > 0) states[id] = SourceState(id, SourceStatus.CACHED, count)
        }
        fun snapshot() = CatalogSnapshot(city.id, selected, shops, offers.toList(), states.values.toList(),
            offers.maxOfOrNull { it.fetchedAt } ?: previous?.updatedAt ?: now(), offers.any { it.cached })
        fun cancelled() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
        fun failure(id: String, error: Exception) {
            cancelled()
            val cached = previous?.offers.orEmpty().filter { it.store.id == id && it.cityId == city.id &&
                (id != "magnit" || (selected?.id != null && it.shopId == selected?.id)) }
                .map { it.copy(cached = true) }
            offers.removeAll { it.store.id == id }
            offers += cached
            states[id] = SourceState(id, if (cached.isNotEmpty()) SourceStatus.CACHED
                else if (error is SourceException && error.blocked) SourceStatus.BLOCKED else SourceStatus.ERROR, cached.size)
        }
        // The city catalog is quick and can be displayed while the individual shop is loading.
        try {
            cancelled()
            val values = loadFix(city)
            require(values.all { it.cityId == city.id && it.store.id == "fixprice" })
            offers.removeAll { it.store.id == "fixprice" }
            offers += values
            states["fixprice"] = SourceState("fixprice", if (values.isEmpty()) SourceStatus.EMPTY else SourceStatus.AVAILABLE, values.size)
        } catch (error: Exception) { failure("fixprice", error) }
        cancelled()
        onPartial(snapshot())
        try {
            shops = findShops(city).also { values -> require(values.all { it.cityId == city.id }) }
            selected = if (preferredShopId != null) shops.firstOrNull { it.id == preferredShopId }
                ?: throw SourceException(message = "Selected shop not in current city")
            else selected?.id?.let { id -> shops.firstOrNull { it.id == id } } ?: shops.firstOrNull()
            val values = selected?.let { loadMagnit(city, it) }.orEmpty()
            require(values.all { it.cityId == city.id && it.store.id == "magnit" && it.shopId == selected?.id })
            offers.removeAll { it.store.id == "magnit" }
            offers += values
            states["magnit"] = SourceState("magnit", if (values.isEmpty()) SourceStatus.EMPTY else SourceStatus.AVAILABLE, values.size)
        } catch (error: Exception) { failure("magnit", error) }
        cancelled()
        return snapshot().also { value ->
            // Disk failures don't turn a successful catalog response into a failed refresh.
            runCatching { cache.write(value) }
        }
    }
}
