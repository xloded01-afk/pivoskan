package ru.pivoskan.app.data

/** Merge only the pages actually read. Changing the Dixy shop replaces that retailer's old shop. */
object CatalogImports {
    fun merge(previous: CatalogSnapshot?, city: City, imported: List<Offer>): CatalogSnapshot {
        require(imported.isNotEmpty())
        require(imported.all { it.store.id == "dixy" && it.cityId == city.id && it.scope == PriceScope.STORE &&
            it.priceKopecks > 0 && it.fetchedAt > 0 && !it.shopId.isNullOrBlank() &&
            CityCatalog.addressMatches(city, it.address) })
        require(imported.map { it.shopId to it.address }.distinct().size == 1)
        val old = previous?.takeIf { it.cityId == city.id }
        val newIds = imported.map { it.id }.toSet()
        val retainedPages = old?.offers.orEmpty().filter { it.store.id == "dixy" &&
            it.shopId == imported.first().shopId && it.id !in newIds }.map { it.copy(cached = true) }
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
}
