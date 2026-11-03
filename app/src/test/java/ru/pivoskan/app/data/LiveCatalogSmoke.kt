package ru.pivoskan.app.data

import ru.pivoskan.app.data.remote.FixPriceSource
import ru.pivoskan.app.data.remote.HttpsTransport
import ru.pivoskan.app.data.remote.MagnitSource

/** Explicit, optional live check; normal unit tests never depend on a retailer being online. */
object LiveCatalogSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val city = requireNotNull(CityCatalog.city("balashikha"))
        val transport = HttpsTransport()
        val magnit = MagnitSource(transport)
        val shops = magnit.shops(city)
        check(shops.isNotEmpty()) { "No confirmed shops in Balashikha" }
        val shop = shops.first()
        val magnitOffers = magnit.offers(city, shop)
        check(magnitOffers.isNotEmpty()) { "No real Magnit beer offers" }
        check(magnitOffers.all { it.shopId == shop.id && it.priceKopecks > 0 && it.address == shop.address })
        println("Magnit: ${shops.size} selectable shops; ${magnitOffers.size} beer offers; exact-shop provenance verified")
        val fixOffers = FixPriceSource(transport).offers(city)
        check(fixOffers.isNotEmpty()) { "No real Fix Price beer offers" }
        check(fixOffers.all { it.cityId == city.id && it.scope == PriceScope.CITY_CATALOG && it.priceKopecks > 0 })
        println("Fix Price: ${fixOffers.size} beer offers; city response header verified; no invented store address")
        println("LIVE CATALOG CHECK PASSED")
    }
}
