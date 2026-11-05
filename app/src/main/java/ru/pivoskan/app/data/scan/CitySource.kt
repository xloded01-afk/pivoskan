package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.RemoteShop

fun interface Cancellable { fun cancel() }
enum class SourceLane { DIRECT, WEB }
interface CitySource {
    val id: String
    val lane: SourceLane
    fun discover(city: City, done: (DiscoveryResult) -> Unit): Cancellable
    fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable
}
