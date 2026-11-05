package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.*

/** Compatibility presentation only, never projects a city back onto one selected shop. */
fun CityScanSnapshot.asLegacySnapshot(): CatalogSnapshot {
    val offers = offers
    return CatalogSnapshot(cityId, null, sources.find { it.sourceId == "magnit" }?.discovery?.shops.orEmpty(),
        offers, sources.map { source -> SourceState(source.sourceId, when (source.phase) {
            SourcePhase.WAITING, SourcePhase.RUNNING -> SourceStatus.LOADING
            SourcePhase.COMPLETE -> if (offers.any { it.store.id == source.sourceId }) SourceStatus.AVAILABLE else SourceStatus.EMPTY
            SourcePhase.NEEDS_ACTION, SourcePhase.NEEDS_LOGIN -> SourceStatus.BLOCKED
            else -> if (offers.any { it.store.id == source.sourceId }) SourceStatus.CACHED else SourceStatus.ERROR
        }, offers.count { it.store.id == source.sourceId }) }, offers.maxOfOrNull { it.fetchedAt } ?: 0,
        offers.any { it.cached })
}
