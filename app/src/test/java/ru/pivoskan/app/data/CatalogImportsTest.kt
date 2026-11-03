package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test

class CatalogImportsTest {
    private val city = CityCatalog.city("balashikha")!!
    private fun offer(id: String, shop: String = "dixy-350", price: Int = 10990, time: Long = 1000) = Offer(
        id, Beer(id, "", "Пиво Балтика 0,5л", 500), CityCatalog.store("dixy")!!, city.id, price,
        address = "Московская обл, Балашиха, Гагарина, 30", sourceUrl = "https://dixy.ru/product/pivo-$id/",
        fetchedAt = time, scope = PriceScope.STORE, shopId = shop)

    @Test fun `saving next page keeps prior pages with original date and cached label`() {
        val first = CatalogImports.merge(null, city, listOf(offer("a")))
        val next = CatalogImports.merge(first, city, listOf(offer("b", time = 2000)))
        assertEquals(2, next.offers.size)
        assertEquals(1000L, next.offers.single { it.id == "a" }.fetchedAt)
        assertTrue(next.offers.single { it.id == "a" }.cached)
        assertFalse(next.offers.single { it.id == "b" }.cached)
    }

    @Test fun `saving same page replaces old price without duplicates`() {
        val first = CatalogImports.merge(null, city, listOf(offer("a")))
        val next = CatalogImports.merge(first, city, listOf(offer("a", price = 9990, time = 2000)))
        assertEquals(9990, next.offers.single().priceKopecks)
        assertEquals(SourceStatus.AVAILABLE, next.sources.single { it.storeId == "dixy" }.status)
    }

    @Test fun `manual import keeps other city shops and other retailers`() {
        val magnit = offer("magnit").copy(store = CityCatalog.store("magnit")!!, shopId = "502731")
        val first = CatalogImports.merge(null, city, listOf(offer("a"))).let { it.copy(offers = it.offers + magnit) }
        val next = CatalogImports.merge(first, city, listOf(offer("b", shop = "dixy-999")))
        assertEquals(setOf("a", "b", "magnit"), next.offers.map { it.id }.toSet())
        assertEquals(setOf("dixy-350", "dixy-999"), next.offers.filter { it.store.id == "dixy" }.map { it.shopId }.toSet())
    }

    @Test fun `new city never inherits prices from previous city`() {
        val previous = CatalogImports.merge(null, city, listOf(offer("a"))).copy(cityId = "msk")
        assertEquals(listOf("b"), CatalogImports.merge(previous, city, listOf(offer("b"))).offers.map { it.id })
    }

    @Test fun `wrong city address and mixed shops fail without replacing prior data`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogImports.merge(null, city,
            listOf(offer("a").copy(address = "Москва, Валовая, 20"))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogImports.merge(null, city,
            listOf(offer("a"), offer("b", "dixy-999"))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogImports.merge(null, city, emptyList()) }
    }

    @Test fun `failed atomic import keeps previous in memory and disk`() {
        val first = CatalogImports.merge(null, city, listOf(offer("a")))
        var diskValue = first
        var failWrite = false
        val cache = CatalogMemoryCache(object : CatalogCache {
            override fun read(cityId: String) = diskValue
            override fun write(snapshot: CatalogSnapshot) {
                if (failWrite) throw java.io.IOException("disk full")
                diskValue = snapshot
            }
        })
        assertEquals(listOf("a"), cache.read(city.id)!!.offers.map { it.id })
        failWrite = true
        assertThrows(java.io.IOException::class.java) {
            cache.update(city.id) { CatalogImports.merge(it, city, listOf(offer("b"))) }
        }
        assertEquals(listOf("a"), cache.read(city.id)!!.offers.map { it.id })
        assertEquals(listOf("a"), diskValue.offers.map { it.id })
    }

    @Test fun `cancelled native writer cannot replace a completed import`() {
        val cache = CatalogMemoryCache(object : CatalogCache {
            override fun read(cityId: String): CatalogSnapshot? = null
            override fun write(snapshot: CatalogSnapshot) = Unit
        })
        cache.update(city.id) { CatalogImports.merge(it, city, listOf(offer("a"))) }
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) {
                cache.write(CatalogImports.merge(null, city, listOf(offer("old", shop = "dixy-old"))))
            }
        } finally { Thread.interrupted() }
        assertEquals(listOf("a"), cache.read(city.id)!!.offers.map { it.id })
    }

    @Test fun `city scan keeps prices from every completed shop`() {
        val a = offer("a", shop = "dixy-a")
        val b = offer("b", shop = "dixy-b").copy(address = "Московская обл, Балашиха, Гагарина, 31")
        val first = CatalogImports.mergeCityShop(null, city, listOf(a))
        val second = CatalogImports.mergeCityShop(first, city, listOf(b))
        assertEquals(setOf("dixy-a", "dixy-b"), second.offers.map { it.shopId }.toSet())
        assertTrue(second.offers.single { it.id == "a" }.cached)
        assertFalse(second.offers.single { it.id == "b" }.cached)
        assertEquals(2, second.sources.single { it.storeId == "dixy" }.offerCount)
    }

    @Test fun `completed shop refresh replaces its old assortment only`() {
        val first = CatalogImports.mergeCityShop(null, city, listOf(offer("old", "dixy-a")))
        val second = CatalogImports.mergeCityShop(first, city, listOf(offer("b", "dixy-b")
            .copy(address = "Московская обл, Балашиха, Гагарина, 31")))
        val refreshed = CatalogImports.mergeCityShop(second, city, listOf(offer("new", "dixy-a", 9990, 3000)))
        assertEquals(setOf("b", "new"), refreshed.offers.map { it.id }.toSet())
        assertEquals(3000L, refreshed.offers.single { it.id == "new" }.fetchedAt)
    }

    @Test fun `city scan rejects mixed shops and foreign city prices`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogImports.mergeCityShop(null, city,
            listOf(offer("a", "dixy-a"), offer("b", "dixy-b"))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogImports.mergeCityShop(null, city,
            listOf(offer("a").copy(address = "Москва, Валовая, 20"))) }
    }

    @Test fun `canonical address refresh removes old raw hash inventory`() {
        val old = CatalogImports.mergeCityShop(null, city, listOf(offer("obsolete", "dixy-raw")))
        val changed = offer("new", "dixy-canonical", 9990, 3000)
            .copy(address = " МОСКОВСКАЯ ОБЛ,  БАЛАШИХА, ГАГАРИНА, 30 ")
        val result = CatalogImports.mergeCityShop(old, city, listOf(changed))
        assertEquals(listOf("new"), result.offers.map { it.id })
    }
}
