package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.CityCatalog
import ru.pivoskan.app.data.Offer
import java.util.UUID

interface CityScanDisk {
    fun read(cityId: String): String?
    fun write(cityId: String, json: String)
}

/** The generation check and atomic disk write share the same monitor as cancellation. */
class CityScanRepository(private val disk: CityScanDisk,
    private val now: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val legacy: (String) -> List<String> = { emptyList() }) {
    private val codec = CityScanCodec()
    private val memory = mutableMapOf<String, CityScanSnapshot>()
    private var current: ScanRun? = null

    @Synchronized fun begin(cityId: String): ScanRun {
        require(CityCatalog.city(cityId) != null)
        current?.let { old -> memory[old.cityId]?.let { memory[old.cityId] = it.copy(runId = null) } }
        val saved = read(cityId)
        val run = ScanRun(nextId(), cityId, now())
        current = run
        memory[cityId] = saved.copy(runId = run.id, sources = emptyList())
        return run
    }
    @Synchronized fun invalidate(run: ScanRun) {
        if (current == run) {
            current = null
            memory[run.cityId]?.let { memory[run.cityId] = it.copy(runId = null) }
        }
    }
    @Synchronized fun read(cityId: String): CityScanSnapshot {
        require(CityCatalog.city(cityId) != null)
        memory[cityId]?.let { return it }
        val decoded = runCatching { disk.read(cityId)?.let { codec.decode(it, cityId) } }.getOrNull()
        val restored = decoded ?: CityScanSnapshot(cityId, null, catalogs = legacy(cityId)
            .flatMap { LegacyCatalogMigration.decode(it, cityId) }.groupBy { it.key }.values.map { entries ->
                entries.maxBy { it.checkedAt }
            })
        memory[cityId] = restored
        return restored
    }
    @Synchronized fun commit(run: ScanRun, result: CatalogResult.Complete): Boolean {
        if (current != run) return false
        require(result.key.cityId == run.cityId)
        val catalog = StoredCatalog(result.key, result.offers, result.checkedAt, run.id)
        require(CityScanCodec.valid(catalog)) { "Invalid shop catalog" }
        val state = read(run.cityId)
        persist(state.copy(catalogs = state.catalogs.filterNot { it.key == result.key } + catalog))
        return true
    }
    @Synchronized fun recordSource(run: ScanRun, progress: SourceProgress): Boolean {
        if (current != run) return false
        require(CityCatalog.store(progress.sourceId) != null)
        require(progress.results.keys.all { it.cityId == run.cityId && it.sourceId == progress.sourceId })
        val state = read(run.cityId)
        val discovered = progress.discovery
        val ids = discovered?.shops?.map { it.id }?.toSet().orEmpty()
        val catalogs = if (discovered?.complete == true && discovered.scope == ru.pivoskan.app.data.PriceScope.STORE)
            state.catalogs.map { if (it.key.sourceId == progress.sourceId) it.copy(active = it.key.shopId in ids) else it }
        else state.catalogs
        persist(state.copy(catalogs = catalogs, sources = state.sources.filterNot { it.sourceId == progress.sourceId } + progress))
        return true
    }
    @Synchronized fun importVerified(catalog: StoredCatalog) {
        val clean = catalog.copy(runId = null)
        require(CityScanCodec.valid(clean) && clean.complete)
        val state = read(clean.key.cityId)
        val existing = state.catalogs.find { it.key == clean.key }
        if (existing != null && existing.checkedAt >= clean.checkedAt) return
        persist(state.copy(catalogs = state.catalogs.filterNot { it.key == clean.key } + clean))
    }
    @Synchronized fun mergeManualOffers(cityId: String, offers: List<Offer>): Boolean {
        require(offers.isNotEmpty())
        val state = read(cityId)
        val keys = offers.map { CatalogKey(it.cityId, it.store.id, it.shopId) }.distinct()
        require(keys.size == 1 && keys.single().cityId == cityId)
        val key = keys.single()
        val existing = state.catalogs.find { it.key == key }
        if (existing?.complete == true) return false
        val merged = (existing?.offers.orEmpty() + offers).groupBy { it.id }.values.map { group -> group.maxBy { it.fetchedAt } }
        val catalog = StoredCatalog(key, merged, merged.maxOf { it.fetchedAt }, null, complete = false)
        require(CityScanCodec.valid(catalog))
        persist(state.copy(catalogs = state.catalogs.filterNot { it.key == key } + catalog))
        return true
    }
    private fun persist(snapshot: CityScanSnapshot) {
        disk.write(snapshot.cityId, codec.encode(snapshot))
        memory[snapshot.cityId] = snapshot
    }
}
