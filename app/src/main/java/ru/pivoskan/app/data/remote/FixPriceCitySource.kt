package ru.pivoskan.app.data.remote

import ru.pivoskan.app.data.*
import ru.pivoskan.app.data.scan.*
import java.util.concurrent.Executor

class FixPriceCitySource(executor: Executor, factory: () -> CancellableHttpTransport = { HttpsTransport() },
    private val now: () -> Long = System::currentTimeMillis) : DirectCitySource(executor, factory) {
    override val id = "fixprice"
    override fun discover(city: City, done: (DiscoveryResult) -> Unit): Cancellable {
        done(DiscoveryResult(emptyList(), PriceScope.CITY_CATALOG, true))
        return Cancellable { }
    }
    override fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable {
        val key = CatalogKey(city.id, id, null)
        return execute({
            require(shop == null)
            CatalogResult.Complete(key, FixPriceSource(it, now).offers(city), now()) as CatalogResult
        }, { CatalogResult.Failed(key, sourceProblem(it, "load")) }, done)
    }
}
