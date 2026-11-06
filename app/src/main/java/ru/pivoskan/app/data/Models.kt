package ru.pivoskan.app.data

data class City(
    val id: String,
    val name: String,
    val region: String = "",
    val streets: List<String> = emptyList()
)

data class Store(
    val id: String,
    val name: String,
    val shortName: String,
    val color: Int
)

data class Beer(
    val id: String,
    val brand: String,
    val title: String,
    val volumeMl: Int = 0,
    val packageName: String = ""
)

data class Offer(
    val id: String,
    val beer: Beer,
    val store: Store,
    val cityId: String,
    val priceKopecks: Int,
    val oldPriceKopecks: Int? = null,
    val address: String,
    val hours: String = "",
    val sourceUrl: String,
    val fetchedAt: Long,
    val scope: PriceScope,
    val shopId: String? = null,
    val cardPriceKopecks: Int? = null,
    val validUntil: String? = null,
    val maxPriceKopecks: Int? = null,
    val cached: Boolean = false,
    val product: ProductDescriptor? = null,
    val priceEvidence: OrdinaryPriceEvidence = OrdinaryPriceEvidence.UNKNOWN
) {
    val discountPercent: Int
        get() = oldPriceKopecks?.takeIf { it > priceKopecks }
            ?.let { ((it - priceKopecks).toLong() * 100 / it).toInt() } ?: 0
}

enum class PriceScope { STORE, CITY_CATALOG }

data class RemoteShop(
    val id: String,
    val cityId: String,
    val address: String,
    val hours: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val providerType: String = "1"
)

enum class SourceStatus { LOADING, AVAILABLE, EMPTY, BLOCKED, ERROR, CACHED, CATALOG }

data class SourceState(val storeId: String, val status: SourceStatus, val offerCount: Int = 0)

data class CatalogSnapshot(
    val cityId: String,
    val selectedShop: RemoteShop?,
    val shops: List<RemoteShop>,
    val offers: List<Offer>,
    val sources: List<SourceState>,
    val updatedAt: Long,
    val fromCache: Boolean = false
)

enum class SortMode {
    CHEAPEST,
    DISCOUNT
}

data class BeerRow(
    val offer: Offer,
    val isBest: Boolean
)
