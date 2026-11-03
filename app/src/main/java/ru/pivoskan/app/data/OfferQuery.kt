package ru.pivoskan.app.data

import ru.pivoskan.app.util.normalize

/** Only filters downloaded offers; there is no generated-price fallback. */
object PriceEngine {
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
