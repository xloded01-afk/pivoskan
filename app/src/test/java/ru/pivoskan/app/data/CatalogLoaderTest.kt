package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.remote.SourceException

class CatalogLoaderTest {
    private val city = CityCatalog.city("balashikha")!!
    private val shop = RemoteShop("502731", city.id, "Московская обл, Балашиха, ул Кудаковского, 11")
    private fun offer(store: String = "magnit", shopId: String? = shop.id) = Offer(
        "real-$store", Beer("beer", "", "Пиво 450мл", 450), CityCatalog.store(store)!!, city.id, 8799,
        address = if (store == "magnit") shop.address else "", sourceUrl = if (store == "magnit")
            "https://magnit.ru/product/123-real" else "https://fix-price.com/catalog/real", fetchedAt = 1000,
        scope = if (store == "magnit") PriceScope.STORE else PriceScope.CITY_CATALOG, shopId = shopId)
    private class MemoryCache(var value: CatalogSnapshot? = null) : CatalogCache {
        override fun read(cityId: String) = value?.takeIf { it.cityId == cityId }
        override fun write(snapshot: CatalogSnapshot) { value = snapshot }
    }

    @Test fun `one failed source does not discard successful real prices`() {
        val result = CatalogLoader(MemoryCache(), { listOf(shop) }, { _, _ -> listOf(offer()) },
            { throw SourceException() }, { 2000 }).load(city, null)
        assertEquals(listOf("real-magnit"), result.offers.map { it.id })
        assertEquals(SourceStatus.AVAILABLE, result.sources.single { it.storeId == "magnit" }.status)
        assertEquals(SourceStatus.ERROR, result.sources.single { it.storeId == "fixprice" }.status)
    }
    @Test fun `changing store never falls back to cached prices of previous shop`() {
        val previous = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        val result = CatalogLoader(MemoryCache(previous), { listOf(shop) }, { _, _ -> throw SourceException() },
            { emptyList() }, { 2000 }).load(city, "another-shop")
        assertTrue(result.offers.none { it.store.id == "magnit" })
    }
    @Test fun `offline keeps same shops checked prices and marks cache`() {
        val previous = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        val result = CatalogLoader(MemoryCache(previous), { throw SourceException() }, { _, _ -> emptyList() },
            { throw SourceException() }, { 2000 }).load(city, shop.id)
        assertEquals(8799, result.offers.single().priceKopecks)
        assertEquals(1000L, result.offers.single().fetchedAt)
        assertEquals(SourceStatus.CACHED, result.sources.single { it.storeId == "magnit" }.status)
        assertTrue(result.fromCache)
    }
    @Test fun `cache rejects a different city and former demo schemas`() {
        val codec = SnapshotCodec()
        val snapshot = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        val encoded = codec.encode(snapshot)
        assertEquals(8799, codec.decode(encoded, city.id)!!.offers.single().priceKopecks)
        assertNull(codec.decode(encoded, "msk"))
        assertNull(codec.decode("{\"cityId\":\"balashikha\",\"price\":55}", city.id))
        assertNull(codec.decode("broken", city.id))
    }

    @Test fun `partial refresh retains previous other provider prices`() {
        val previous = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        var partial: CatalogSnapshot? = null
        CatalogLoader(MemoryCache(previous), { listOf(shop) }, { _, _ -> throw SourceException() },
            { listOf(offer("fixprice", null).copy(fetchedAt = 2000)) }, { 2000 })
            .load(city, shop.id) { partial = it }
        assertTrue(partial!!.offers.any { it.store.id == "magnit" && it.fetchedAt == 1000L })
    }

    @Test fun `mixed fresh and cached providers remain marked as containing cache`() {
        val previous = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        val result = CatalogLoader(MemoryCache(previous), { listOf(shop) }, { _, _ -> throw SourceException() },
            { listOf(offer("fixprice", null).copy(fetchedAt = 2000)) }, { 2000 }).load(city, shop.id)
        assertTrue(result.fromCache)
        assertTrue(result.offers.single { it.store.id == "magnit" }.cached)
        assertFalse(result.offers.single { it.store.id == "fixprice" }.cached)
    }

    @Test fun `returning to previous shop offline restores its own cached prices`() {
        val cache = CatalogMemoryCache(MemoryCache())
        val a = CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000)
        val bShop = shop.copy(id = "999", address = "Балашиха, другой магазин")
        val b = a.copy(selectedShop = bShop, shops = listOf(shop, bShop),
            offers = listOf(offer().copy(id = "other", shopId = "999", address = bShop.address, priceKopecks = 9900)))
        cache.write(a)
        cache.write(b)
        val result = CatalogLoader(cache, { throw SourceException() }, { _, _ -> emptyList() },
            { throw SourceException() }, { 2000 }).load(city, shop.id)
        assertEquals("502731", result.selectedShop?.id)
        assertEquals(8799, result.offers.single().priceKopecks)
        assertEquals("502731", result.offers.single().shopId)
    }

    @Test fun `cancelled partial refresh cannot hide previous provider cache`() {
        val cache = CatalogMemoryCache(MemoryCache())
        cache.write(CatalogSnapshot(city.id, shop, listOf(shop), listOf(offer()), emptyList(), 1000))
        val loader = CatalogLoader(cache, { listOf(shop) }, { _, _ -> emptyList() },
            { listOf(offer("fixprice", null).copy(fetchedAt = 2000)) }, { 2000 })
        runCatching { loader.load(city, shop.id) { partial -> cache.remember(partial); throw InterruptedException() } }
        assertTrue(cache.read(city.id)!!.offers.any { it.store.id == "magnit" })
        val offline = CatalogLoader(cache, { throw SourceException() }, { _, _ -> emptyList() },
            { throw SourceException() }, { 3000 }).load(city, shop.id)
        assertEquals(8799, offline.offers.single { it.store.id == "magnit" }.priceKopecks)
    }
}
