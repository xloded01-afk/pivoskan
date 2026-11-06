package ru.pivoskan.app.data.scan

import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.PriceScope
import ru.pivoskan.app.data.RemoteShop
import java.util.ArrayDeque

fun interface ScanScheduler { fun post(delayMs: Long, task: () -> Unit): Cancellable }

/** Serialized transitions; callbacks re-enter through the scheduler and carry the run identity. */
class CityScanCoordinator(private val repository: CityScanRepository, private val sources: List<CitySource>,
    private val scheduler: ScanScheduler, private val now: () -> Long = System::currentTimeMillis,
    var onSnapshot: (CityScanSnapshot) -> Unit = {}) {
    private data class Work(val source: CitySource, val discover: Boolean, val shop: RemoteShop? = null)
    private class Active(val work: Work, var cancel: Cancellable = Cancellable {})
    private var run: ScanRun? = null
    private var city: City? = null
    private val queues = SourceLane.entries.associateWith { ArrayDeque<Work>() }
    private val active = mutableMapOf<SourceLane, Active>()
    private val states = linkedMapOf<String, SourceProgress>()
    private var elapsed = 0L
    private var tickAt = 0L
    private var watchdog: Cancellable? = null
    private var requestsAllowed = true

    @Synchronized fun setRequestAllowed(allowed: Boolean) {
        if (allowed == requestsAllowed) return
        requestsAllowed = allowed
        val token = run ?: return
        if (!allowed) {
            val cancelled = active.toMap()
            active.clear()
            cancelled.forEach { (lane, operation) ->
                queues.getValue(lane).addFirst(operation.work)
                operation.cancel.cancel()
            }
        } else {
            tickAt = now()
            SourceLane.entries.forEach { schedulePump(it,token,0) }; watch(token)
        }
    }

    @Synchronized fun start(city: City): ScanRun {
        cancel(); this.city = city
        val token = repository.begin(city.id); run = token
        elapsed = 0; tickAt = now(); states.clear()
        sources.sortedBy { if (it.id == "fixprice") 0 else 1 }.forEach { source ->
            states[source.id] = SourceProgress(source.id)
            queues.getValue(source.lane).add(Work(source, true))
        }
        publish()
        SourceLane.entries.forEach { schedulePump(it, token, 0) }; watch(token)
        return token
    }
    @Synchronized fun cancel() { stop(false) }
    private fun stop(limit: Boolean) {
        val token = run ?: return
        repository.invalidate(token); run = null
        watchdog?.cancel(); watchdog = null
        active.values.forEach { it.cancel.cancel() }; active.clear()
        queues.values.forEach { it.clear() }
        states.replaceAll { _, state ->
            if (state.phase in setOf(SourcePhase.COMPLETE, SourcePhase.UNAVAILABLE, SourcePhase.PARTIAL)) state
            else state.copy(phase = if (limit) SourcePhase.PARTIAL else SourcePhase.CANCELLED, current = null,
                discovery = (state.discovery ?: DiscoveryResult(emptyList(), PriceScope.STORE, false)).let {
                    if (limit) it.copy(problem = SourceProblem(ProblemKind.LIMIT,"load","LIMIT")) else it
                })
        }
        onSnapshot(repository.read(token.cityId).copy(sources = states.values.toList()))
    }
    @Synchronized fun resumeSource(sourceId: String) {
        val source = sources.find { it.id == sourceId } ?: return
        val token = run ?: return
        val state = states[sourceId] ?: return
        if (state.phase !in setOf(SourcePhase.NEEDS_LOGIN, SourcePhase.NEEDS_ACTION, SourcePhase.UNAVAILABLE)) return
        states[sourceId] = state.copy(phase = SourcePhase.WAITING, current = null)
        queues.getValue(source.lane).add(Work(source, true))
        tickAt = now(); schedulePump(source.lane, token, 0); watch(token); publish()
    }
    private fun hasWork() = requestsAllowed && (active.isNotEmpty() || queues.values.any { it.isNotEmpty() })
    private fun expired(): Boolean {
        val at = now()
        if (hasWork()) elapsed += (at - tickAt).coerceAtLeast(0)
        tickAt = at
        if (elapsed >= ScanPolicy.activeRunMs) { stop(true); return true }
        return false
    }
    private fun watch(token: ScanRun) {
        watchdog?.cancel()
        watchdog = scheduler.post(1000) { synchronized(this) {
            if (run == token && !expired() && hasWork()) watch(token)
        } }
    }
    private fun schedulePump(lane: SourceLane, token: ScanRun, delay: Long = ScanPolicy.minimumRequestGapMs) {
        scheduler.post(delay) { synchronized(this) { if (run == token) pump(lane, token) } }
    }
    private fun pump(lane: SourceLane, token: ScanRun) {
        if (run != token || !requestsAllowed || expired() || active.containsKey(lane)) return
        val work = queues.getValue(lane).pollFirst() ?: return
        val operation = Active(work); active[lane] = operation
        val requestedCity = requireNotNull(city)
        val key = CatalogKey(requestedCity.id, work.source.id, work.shop?.id)
        states[work.source.id] = states.getValue(work.source.id).copy(phase = SourcePhase.RUNNING,
            current = if (work.discover) null else key)
        publish()
        fun dispatch(action: () -> Unit) {
            scheduler.post(0) { synchronized(this) {
                if (run != token || active[lane] !== operation || expired()) return@synchronized
                active.remove(lane); action(); publish(); schedulePump(lane, token)
            } }
        }
        operation.cancel = try {
            if (work.discover) work.source.discover(requestedCity) { result -> dispatch { discovered(work.source, result) } }
            else work.source.load(requestedCity, work.shop) { result -> dispatch { loaded(work.source, key, result, token) } }
        } catch (_: Exception) {
            dispatch {
                val problem = SourceProblem(ProblemKind.INVALID_DATA, if (work.discover) "discover" else "load", "INVALID_DATA")
                if (work.discover) discovered(work.source, DiscoveryResult(emptyList(), PriceScope.STORE, false, problem = problem))
                else loaded(work.source, key, CatalogResult.Failed(key, problem), token)
            }
            Cancellable {}
        }
    }
    private fun discovered(source: CitySource, raw: DiscoveryResult) {
        val selected = requireNotNull(city)
        val result = if (raw.shops.size > ScanPolicy.maxShopsPerSource || raw.shops.any { it.cityId != selected.id } ||
            raw.shops.map { it.id }.distinct().size != raw.shops.size)
            DiscoveryResult(emptyList(), raw.scope, false, problem = SourceProblem(ProblemKind.INVALID_DATA,"discover","INVALID_DATA")) else raw
        val old = states.getValue(source.id)
        val suspension = suspendPhase(result.problem)
        if (suspension != null) { states[source.id] = old.copy(discovery = result, phase = suspension, current = null); return }
        val queue = queues.getValue(source.lane)
        if (result.scope == PriceScope.CITY_CATALOG && result.complete) queue.add(Work(source, false))
        else result.shops.filter { old.results[CatalogKey(selected.id,source.id,it.id)] != null ||
            !old.results.containsKey(CatalogKey(selected.id,source.id,it.id)) }.forEach { queue.add(Work(source,false,it)) }
        states[source.id] = old.copy(discovery = result, current = null,
            phase = if (queue.any { it.source.id == source.id }) SourcePhase.RUNNING
            else if (result.complete && result.problem == null) SourcePhase.COMPLETE
            else if (result.shops.isEmpty()) SourcePhase.UNAVAILABLE else SourcePhase.PARTIAL)
    }
    private fun loaded(source: CitySource, expected: CatalogKey, result: CatalogResult, token: ScanRun) {
        var problem: SourceProblem? = null
        when (result) {
            is CatalogResult.Failed -> problem = if (result.key == expected) result.problem
                else SourceProblem(ProblemKind.INVALID_DATA,"load","INVALID_DATA")
            is CatalogResult.Complete -> try {
                require(result.key == expected)
                if (!repository.commit(token, result)) return
            } catch (e: Exception) {
                problem = SourceProblem(if (e is java.io.IOException) ProblemKind.STORAGE else ProblemKind.INVALID_DATA,
                    "save", if (e is java.io.IOException) "STORAGE" else "INVALID_DATA")
            }
        }
        val previous = states.getValue(source.id)
        val results = previous.results + (expected to problem)
        val suspension = suspendPhase(problem)
        val queue = queues.getValue(source.lane)
        if (suspension != null) queue.removeAll { it.source.id == source.id }
        val phase = suspension ?: if (queue.any { it.source.id == source.id }) SourcePhase.RUNNING
            else if (previous.discovery?.complete == true && previous.discovery.problem == null && results.values.none { it != null }) SourcePhase.COMPLETE
            else SourcePhase.PARTIAL
        states[source.id] = previous.copy(results = results, phase = phase, current = null)
    }
    private fun suspendPhase(problem: SourceProblem?) = when (problem?.kind) {
        ProblemKind.NEEDS_LOGIN -> SourcePhase.NEEDS_LOGIN
        ProblemKind.NEEDS_ACTION -> SourcePhase.NEEDS_ACTION
        ProblemKind.UNSUPPORTED -> SourcePhase.UNAVAILABLE
        else -> null
    }
    private fun publish() {
        val token = run ?: return
        states.values.toList().forEach { state ->
            try { repository.recordSource(token, state) } catch (_: Exception) {
                states[state.sourceId] = state.copy(phase = SourcePhase.PARTIAL,
                    discovery = (state.discovery ?: DiscoveryResult(emptyList(), PriceScope.STORE,false))
                        .copy(problem = SourceProblem(ProblemKind.STORAGE,"save","STORAGE")))
            }
        }
        onSnapshot(repository.read(token.cityId).copy(sources = states.values.toList()))
    }
}
