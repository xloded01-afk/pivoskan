package ru.pivoskan.app.data

/** Merge only verified pages, keeping separate prices for every shop in the city. */
object CatalogImports {
    /** A complete shop scan replaces only that shop; other city shops remain independently dated. */
    fun mergeCityShop(previous: CatalogSnapshot?, city: City, imported: List<Offer>,
                      scanStartedAt: Long = Long.MAX_VALUE): CatalogSnapshot {
        val fresh = merge(null, city, imported) // Same strict city/single-shop validation as manual import.
        val old = previous?.takeIf { it.cityId == city.id }
        val shopId = imported.first().shopId
        val address = addressKey(imported.first().address)
        val retained = old?.offers.orEmpty().filter { it.store.id != "dixy" ||
            (it.shopId != shopId && addressKey(it.address) != address) }.map {
            if (it.store.id == "dixy" && it.fetchedAt < scanStartedAt) it.copy(cached = true) else it
        }
        val offers = retained + imported
        val dixy = offers.filter { it.store.id == "dixy" }
        val sources = fresh.sources.map { source ->
            if (source.storeId == "dixy") source.copy(offerCount = dixy.size,
                status = if (dixy.any { it.cached }) SourceStatus.CACHED else SourceStatus.AVAILABLE)
            else old?.sources?.firstOrNull { it.storeId == source.storeId } ?: source
        }
        return fresh.copy(selectedShop = old?.selectedShop, shops = old?.shops.orEmpty(), offers = offers,
            sources = sources, updatedAt = offers.maxOf { it.fetchedAt }, fromCache = offers.any { it.cached })
    }

    fun merge(previous: CatalogSnapshot?, city: City, imported: List<Offer>): CatalogSnapshot {
        require(imported.isNotEmpty())
        require(imported.all { it.store.id == "dixy" && it.cityId == city.id && it.scope == PriceScope.STORE &&
            it.priceKopecks > 0 && it.fetchedAt > 0 && !it.shopId.isNullOrBlank() &&
            CityCatalog.addressMatches(city, it.address) })
        require(imported.map { it.shopId to it.address }.distinct().size == 1)
        val old = previous?.takeIf { it.cityId == city.id }
        val newIds = imported.map { it.id }.toSet()
        val titles = imported.map { it.beer.id }.toSet()
        val currentAddress = addressKey(imported.first().address)
        val retainedPages = old?.offers.orEmpty().filter { it.store.id == "dixy" &&
            it.id !in newIds && !(addressKey(it.address) == currentAddress && it.beer.id in titles)
        }.map { it.copy(cached = true) }
        val offers = old?.offers.orEmpty().filter { it.store.id != "dixy" } + retainedPages + imported
        val sources = CityCatalog.stores.map { store ->
            if (store.id == "dixy") SourceState(store.id,
                if (retainedPages.isEmpty()) SourceStatus.AVAILABLE else SourceStatus.CACHED,
                retainedPages.size + imported.size)
            else old?.sources?.firstOrNull { it.storeId == store.id }
                ?: SourceState(store.id, if (store.id == "pyaterochka") SourceStatus.BLOCKED else SourceStatus.ERROR)
        }
        return CatalogSnapshot(city.id, old?.selectedShop, old?.shops.orEmpty(), offers, sources,
            offers.maxOf { it.fetchedAt }, offers.any { it.cached })
    }

    private fun addressKey(value: String) = value.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
}
