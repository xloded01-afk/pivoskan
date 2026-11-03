package ru.pivoskan.app.data

/** Keep every visited shop, not just the last selected shop in a city. */
class CatalogMemoryCache(private val disk: CatalogCache) : CatalogCache {
    private val cities = mutableMapOf<String, CatalogSnapshot>()
    private val shops = mutableMapOf<Pair<String, String>, CatalogSnapshot>()

    @Synchronized override fun read(cityId: String): CatalogSnapshot? = cities[cityId]
        ?: disk.read(cityId)?.also(::remember)

    @Synchronized override fun readShop(cityId: String, shopId: String): CatalogSnapshot? =
        shops[cityId to shopId] ?: disk.readShop(cityId, shopId)?.also { shops[cityId to shopId] = it }

    @Synchronized fun remember(value: CatalogSnapshot) {
        cities[value.cityId] = value
        value.selectedShop?.id?.let { shops[value.cityId to it] = value }
    }

    @Synchronized override fun write(snapshot: CatalogSnapshot) {
        remember(snapshot)
        disk.write(snapshot)
    }
}
