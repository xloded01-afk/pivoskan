package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.*

internal object ScanFixtures {
    val city = CityCatalog.city("balashikha")!!
    fun offer(shop: String = "502731", price: Int = 10000) = Offer("magnit-$shop-123",
        Beer("beer-1", "Марка", "Пиво Марка 0,5 л", 500, "банка"), CityCatalog.store("magnit")!!,
        city.id, price, address = "Московская обл, Балашиха, ул Ленина, $shop",
        sourceUrl = "https://magnit.ru/product/123-beer?shopCode=$shop&shopType=1", fetchedAt = 90,
        scope = PriceScope.STORE, shopId = shop)
    fun complete(shop: String = "502731", offers: List<Offer> = listOf(offer(shop))) =
        CatalogResult.Complete(CatalogKey(city.id, "magnit", shop), offers, 100)
    class Disk : CityScanDisk {
        val data = mutableMapOf<String, String>()
        var fail = false
        var writes = 0
        override fun read(cityId: String) = data[cityId]
        override fun write(cityId: String, json: String) {
            if (fail) throw java.io.IOException("disk full")
            writes++
            data[cityId] = json
        }
    }
}
