package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.Offer
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.data.RemoteShop

data class ScanRun(val id: String, val cityId: String, val startedAt: Long)
data class CatalogKey(val cityId: String, val sourceId: String, val shopId: String?)
enum class ProblemKind { NETWORK, HTTP, NEEDS_LOGIN, NEEDS_ACTION, UNSUPPORTED, INVALID_DATA, LIMIT, STORAGE, CANCELLED }
data class SourceProblem(val kind: ProblemKind, val stage: String, val code: String)
data class DiscoveryResult(val shops: List<RemoteShop>, val scope: PriceScope,
    val complete: Boolean, val excludedCount: Int = 0, val problem: SourceProblem? = null)
sealed interface CatalogResult {
    data class Complete(val key: CatalogKey, val offers: List<Offer>, val checkedAt: Long) : CatalogResult
    data class Failed(val key: CatalogKey, val problem: SourceProblem) : CatalogResult
}
data class StoredCatalog(val key: CatalogKey, val offers: List<Offer>, val checkedAt: Long,
    val runId: String?, val complete: Boolean = true, val active: Boolean = true)
enum class SourcePhase { WAITING, RUNNING, NEEDS_LOGIN, NEEDS_ACTION, COMPLETE, PARTIAL, UNAVAILABLE, CANCELLED }
data class SourceProgress(val sourceId: String, val discovery: DiscoveryResult? = null,
    val phase: SourcePhase = SourcePhase.WAITING, val results: Map<CatalogKey, SourceProblem?> = emptyMap(),
    val current: CatalogKey? = null) {
    val succeeded: Int get() = results.count { it.value == null }
    val failed: Int get() = results.count { it.value != null }
}
data class CityScanSnapshot(val cityId: String, val runId: String?,
    val catalogs: List<StoredCatalog> = emptyList(), val sources: List<SourceProgress> = emptyList()) {
    val offers: List<Offer> get() = catalogs.filter { it.active }.flatMap { catalog ->
        catalog.offers.map { it.copy(cached = runId == null || catalog.runId != runId || !catalog.complete) }
    }
}
