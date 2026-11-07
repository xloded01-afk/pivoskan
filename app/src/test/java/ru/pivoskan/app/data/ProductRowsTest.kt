package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.scan.*

class ProductRowsTest {
    @Test fun largeCityRetainsEveryAddressAndFreshMinimum() {
        val catalogs = (1..63).map { shop ->
            val entries = (1..200).map { product -> offer(shop.toString(), 10000 + shop).copy(
                id = "$shop-$product", product = ProductDescriptor(product.toString(), volumeMl = 500),
                beer = offer().beer.copy(id = product.toString())) }
            StoredCatalog(CatalogKey("balashikha", "magnit", shop.toString()), entries, 100, "run")
        }
        val start = System.nanoTime()
        val result = PriceEngine.products(CityScanSnapshot("balashikha", "run", catalogs), emptySet(), "", SortMode.CHEAPEST)
        println("12600 offers grouped in ${(System.nanoTime() - start) / 1000000} ms")
        assertEquals(200, result.size)
        result.forEach { row ->
            assertEquals(63, row.fresh.size)
            assertEquals(10001, row.minimum!!.priceKopecks)
            assertFalse(row.minimumIsHistorical)
        }
    }
    private fun offer(shop: String = "502731", price: Int = 10000) = ScanFixtures.offer(shop, price).copy(
        product = ProductDescriptor("123", volumeMl = 500, packageType = "can"),
        priceEvidence = OrdinaryPriceEvidence.CONFIRMED)
    private fun rows(vararg offers: Offer, old: Set<String> = emptySet(), run: String? = "run", stores: Set<String> = emptySet()) =
        PriceEngine.products(CityScanSnapshot("balashikha", run, offers.map {
            StoredCatalog(CatalogKey(it.cityId, it.store.id, it.shopId), listOf(it), 100,
                if (it.shopId in old) "old" else run)
        }), stores, "", SortMode.CHEAPEST)
    @Test fun sameSourceProductAcrossShopsGroups() {
        val result = rows(offer(), offer("388020", 9000))
        assertEquals(1, result.size); assertEquals(2, result.single().fresh.size)
        assertEquals(9000, result.single().minimum!!.priceKopecks)
    }
    @Test fun differentPackageOrVolumeNeverGroups() {
        val a = offer()
        for (p in listOf(a.product!!.copy(volumeMl = 450), a.product.copy(packageType = "bottle")))
            assertEquals(2, rows(a, offer("388020").copy(product = p)).size)
    }
    @Test fun missingAttributesDoNotJoinNetworks() {
        assertEquals(2, rows(offer(), offer("other").copy(store = CityCatalog.store("dixy")!!)).size)
    }
    @Test fun gtinConflictSplits() {
        val p = offer().product!!.copy(gtin = "4006381333931")
        assertEquals(2, rows(offer().copy(product = p), offer("388020").copy(product = p.copy(volumeMl = 450))).size)
    }
    @Test fun cardPriceNeverWins() {
        assertEquals(10000, rows(offer().copy(cardPriceKopecks = 5000),
            offer("388020", 7000).copy(priceEvidence = OrdinaryPriceEvidence.CONDITIONAL)).single().minimum!!.priceKopecks)
    }
    @Test fun staleCheaperDoesNotBeatFresh() {
        val row = rows(offer(), offer("388020", 8000), old = setOf("388020")).single()
        assertEquals(10000, row.minimum!!.priceKopecks); assertFalse(row.minimumIsHistorical)
    }
    @Test fun nullRunsAreHistorical() {
        val row = rows(offer(), run = null).single()
        assertTrue(row.fresh.isEmpty()); assertTrue(row.minimumIsHistorical)
    }
    @Test fun cityRangeHasNoAddressWinner() {
        assertNull(rows(offer().copy(scope = PriceScope.CITY_CATALOG, shopId = null, maxPriceKopecks = 12000)).single().minimum)
    }
    @Test fun filterRecalculatesMinimum() {
        val p = offer().product!!.copy(gtin = "4006381333931")
        val result = rows(offer().copy(product = p), offer("other", 8000).copy(product = p,
            store = CityCatalog.store("dixy")!!), stores = setOf("magnit"))
        assertEquals(10000, result.single().minimum!!.priceKopecks)
    }
}
