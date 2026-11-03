package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test

class OfferQueryTest {
    private val store = CityCatalog.stores.first()
    private fun offer(id: String, price: Int, title: String = "Козёл Светлый", old: Int? = null) =
        Offer(id, Beer("same-product", "Козёл", title, 450), store, "balashikha", price, old,
            "Балашиха, улица, 1", sourceUrl = "https://magnit.ru/product/$id", fetchedAt = 1000,
            scope = PriceScope.STORE, shopId = "1")

    @Test fun `sort preserves individual kopecks`() {
        val rows = PriceEngine.query(listOf(offer("a", 8799), offer("b", 8701), offer("c", 8750)), emptySet(), "", SortMode.CHEAPEST)
        assertEquals(listOf("b", "c", "a"), rows.map { it.offer.id })
        assertEquals(listOf("b"), rows.filter { it.isBest }.map { it.offer.id })
    }
    @Test fun `discount order uses ordinary old price not loyalty savings`() {
        val a = offer("a", 8700, old = 10000)
        val b = offer("b", 9000).copy(cardPriceKopecks = 5000)
        assertEquals(listOf("a", "b"), PriceEngine.query(listOf(b, a), emptySet(), "", SortMode.DISCOUNT).map { it.offer.id })
    }
    @Test fun `query normalizes yo case and whitespace and never generates offers`() {
        val a = offer("a", 8799)
        assertEquals(1, PriceEngine.query(listOf(a), emptySet(), " КОЗЕЛ ", SortMode.CHEAPEST).size)
        assertTrue(PriceEngine.query(emptyList(), emptySet(), "", SortMode.CHEAPEST).isEmpty())
        assertTrue(PriceEngine.query(listOf(a), setOf("fixprice"), "", SortMode.CHEAPEST).isEmpty())
        assertFalse(PriceEngine.query(listOf(a), emptySet(), "", SortMode.CHEAPEST).single().isBest)
    }
    @Test fun `city list includes Balashikha and Moscow oblast without losing existing city ids`() {
        assertEquals("Балашиха", CityCatalog.city("balashikha")?.name)
        assertEquals("Московская область", CityCatalog.city("balashikha")?.region)
        assertNotNull(CityCatalog.city("msk"))
        assertNotNull(CityCatalog.city("spb"))
        assertTrue(CityCatalog.cities.count { it.region == "Московская область" } >= 12)
    }

    @Test fun `uncertain variant range cannot establish a minimum price winner`() {
        val range = offer("range", 8500).copy(maxPriceKopecks = 10000)
        val fixed = offer("fixed", 9000)
        assertTrue(PriceEngine.query(listOf(range, fixed), emptySet(), "", SortMode.CHEAPEST).none { it.isBest })
    }
}
