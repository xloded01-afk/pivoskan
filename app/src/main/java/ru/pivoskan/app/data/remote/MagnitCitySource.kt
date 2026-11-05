package ru.pivoskan.app.data.remote

import ru.pivoskan.app.data.*
import ru.pivoskan.app.data.scan.*
import java.util.concurrent.Executor

class MagnitCitySource(executor: Executor, factory: () -> CancellableHttpTransport = { HttpsTransport() },
    private val now: () -> Long = System::currentTimeMillis) : DirectCitySource(executor, factory) {
    override val id = "magnit"
    override fun discover(city: City, done: (DiscoveryResult) -> Unit) =
        execute({ MagnitSource(it, now).discover(city) }, { failedDiscovery(it) }, done)
    override fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable {
        val key = CatalogKey(city.id, id, shop?.id)
        return execute({ CatalogResult.Complete(key, MagnitSource(it, now).offers(city, requireNotNull(shop)), now()) as CatalogResult },
            { CatalogResult.Failed(key, sourceProblem(it, "load")) }, done)
    }
}
