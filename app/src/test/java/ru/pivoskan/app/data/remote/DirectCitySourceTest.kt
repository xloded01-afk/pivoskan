package ru.pivoskan.app.data.remote

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.data.RemoteShop
import ru.pivoskan.app.data.scan.*
import java.util.concurrent.Executor

class DirectCitySourceTest {
    private val city = CityCatalog.city("balashikha")!!
    private val inline = Executor { it.run() }
    @Test fun fixIsNeverAddressScoped() {
        val source = FixPriceCitySource(inline, { error("discovery must not fetch prices") })
        var found: DiscoveryResult? = null
        source.discover(city) { found = it }
        assertEquals(PriceScope.CITY_CATALOG, found!!.scope)
        assertTrue(found!!.shops.isEmpty())
    }
    @Test fun cancelDisconnectsOnlyItsOperation() {
        val work = mutableListOf<Runnable>()
        val transports = mutableListOf<FakeTransport>()
        val source = MagnitCitySource(Executor { work += it }, { FakeTransport().also { transports += it } })
        var callbacks = 0
        val first = source.discover(city) { callbacks++ }
        source.discover(city) { callbacks++ }
        first.cancel()
        work.forEach { it.run() }
        assertTrue(transports[0].cancelled)
        assertFalse(transports[1].cancelled)
        assertEquals(1, callbacks)
    }
    @Test fun foreignStockFailsWholeShop() {
        val source = MagnitCitySource(inline, { FakeTransport("""{"items":[{"storeCode":"999"}],"pagination":{"hasMore":false}}""") })
        var result: CatalogResult? = null
        source.load(city, RemoteShop("502731", city.id, "Московская обл, Балашиха, ул Кудаковского, 11")) { result = it }
        assertTrue(result is CatalogResult.Failed)
    }
    private class FakeTransport(val body: String = "{\"data\":[],\"totalCount\":0}") : CancellableHttpTransport {
        var cancelled = false
        override fun cancel() { cancelled = true }
        override fun execute(request: HttpRequest): HttpResponse = HttpResponse(200, body)
    }
}
