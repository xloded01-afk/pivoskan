package ru.pivoskan.app.data

import ru.pivoskan.app.util.normalize

/** Only filters downloaded offers; there is no generated-price fallback. */
object PriceEngine {
    fun products(snapshot: ru.pivoskan.app.data.scan.CityScanSnapshot, storeIds: Set<String>, search: String,
        sort: SortMode): List<ProductRow> {
        val needle = normalize(search)
        val order = compareBy<Offer> { it.priceKopecks }.thenBy { it.store.id }.thenBy { it.address }.thenBy { it.id }
        val offers = snapshot.offers.filter {
            (storeIds.isEmpty() || it.store.id in storeIds) &&
                (needle.isEmpty() || normalize(it.beer.title).contains(needle) || normalize(it.beer.brand).contains(needle))
        }
        val rows = offers.groupBy { ProductIdentity.key(it.store.id, it.product ?: ProductDescriptor(it.beer.id)) }
            .map { (key, entries) ->
                val sorted = entries.sortedWith(order)
                val fresh = sorted.filter { it.scope == PriceScope.STORE && !it.cached }
                val historical = sorted.filter { it.scope == PriceScope.STORE && it.cached }
                val city = sorted.filter { it.scope == PriceScope.CITY_CATALOG }
                fun ordinary(list: List<Offer>) = list.firstOrNull {
                    it.priceEvidence == OrdinaryPriceEvidence.CONFIRMED && (it.maxPriceKopecks ?: it.priceKopecks) == it.priceKopecks
                }
                val current = ordinary(fresh)
                val minimum = current ?: ordinary(historical)
                ProductRow(key, sorted.first().beer.title, sorted.firstNotNullOfOrNull { it.product?.imageUrl },
                    fresh, historical, city, minimum, minimum != null && current == null)
            }
        return when (sort) {
            SortMode.CHEAPEST -> rows.sortedWith(compareBy<ProductRow> { it.minimum?.priceKopecks ?: Int.MAX_VALUE }.thenBy { it.id })
            SortMode.DISCOUNT -> rows.sortedWith(compareByDescending<ProductRow> { it.minimum?.discountPercent ?: 0 }
                .thenBy { it.minimum?.priceKopecks ?: Int.MAX_VALUE }.thenBy { it.id })
        }
    }

    fun query(offers: List<Offer>, storeIds: Set<String>, search: String, sort: SortMode): List<BeerRow> {
        val needle = normalize(search).split(' ').joinToString(" ") { if (it == "жегули") "жигули" else it }
        val filtered = offers.filter { offer ->
            (storeIds.isEmpty() || offer.store.id in storeIds) &&
                (needle.isEmpty() || normalize(offer.beer.title).contains(needle) || normalize(offer.beer.brand).contains(needle))
        }
        val winners = filtered.groupBy { it.beer.id }.values.filter { group ->
            group.size > 1 && group.none { (it.maxPriceKopecks ?: it.priceKopecks) > it.priceKopecks }
        }
            .map { group -> group.minWith(compareBy<Offer> { it.priceKopecks }.thenBy { it.id }).id }.toSet()
        val rows = filtered.map { BeerRow(it, it.id in winners) }
        return when (sort) {
            SortMode.CHEAPEST -> rows.sortedWith(compareBy<BeerRow> { it.offer.priceKopecks }.thenBy { it.offer.beer.title })
            SortMode.DISCOUNT -> rows.sortedWith(compareByDescending<BeerRow> { it.offer.discountPercent }.thenBy { it.offer.priceKopecks })
        }
    }

    fun alternatives(offers: List<Offer>, beerId: String): List<Offer> =
        offers.filter { it.beer.id == beerId }.sortedWith(compareBy<Offer> { it.priceKopecks }.thenBy { it.id })
}
