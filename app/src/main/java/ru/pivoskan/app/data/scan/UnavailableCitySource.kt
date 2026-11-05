package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.*

class UnavailableCitySource(override val id: String) : CitySource {
    override val lane = SourceLane.WEB
    override fun discover(city: City, done: (DiscoveryResult) -> Unit): Cancellable {
        done(DiscoveryResult(emptyList(), PriceScope.STORE, false,
            problem = SourceProblem(ProblemKind.UNSUPPORTED,"discover","UNSUPPORTED")))
        return Cancellable {}
    }
    override fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable {
        done(CatalogResult.Failed(CatalogKey(city.id,id,shop?.id), SourceProblem(ProblemKind.UNSUPPORTED,"load","UNSUPPORTED")))
        return Cancellable {}
    }
}
