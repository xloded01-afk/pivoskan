package ru.pivoskan.app.data.scan

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.*

class CityScanCoordinatorTest {
    @Test fun cancellationIsStoredForReturnToApplication() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("502731")), PriceScope.STORE, true))
        var last: CityScanSnapshot? = null
        val coordinator = CityScanCoordinator(repo, listOf(source), scheduler, { scheduler.now }, { last = it })
        val run = coordinator.start(city)
        coordinator.cancel()
        assertEquals(SourcePhase.CANCELLED, repo.read(city.id).sources.single().phase)
        assertEquals(run.id, last!!.runId)
        assertNull(repo.read(city.id).runId)
    }
    private val city = ScanFixtures.city
    private fun shop(id: String) = RemoteShop(id, city.id, ScanFixtures.offer(id).address)
    private class Scheduler : ScanScheduler {
        var now = 1000L
        private data class Job(val at: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override fun post(delayMs: Long, task: () -> Unit): Cancellable {
            val job = Job(now + delayMs, task); jobs += job
            return Cancellable { job.cancelled = true }
        }
        fun drain() {
            repeat(1000) {
                val next = jobs.filter { it.at <= now }.minByOrNull { it.at } ?: return
                jobs.remove(next); if (!next.cancelled) next.block()
            }
            error("scheduler did not yield")
        }
        fun settle() { repeat(12) { now += 500; drain() } }
    }
    private class Source(override val id: String = "magnit", override val lane: SourceLane = SourceLane.DIRECT,
        val discovery: DiscoveryResult, val failId: String? = null) : CitySource {
        val loaded = mutableListOf<String?>()
        override fun discover(city: City, done: (DiscoveryResult) -> Unit): Cancellable { done(discovery); return Cancellable {} }
        override fun load(city: City, shop: RemoteShop?, done: (CatalogResult) -> Unit): Cancellable {
            loaded += shop?.id
            val key = CatalogKey(city.id, id, shop?.id)
            done(if (shop?.id == failId) CatalogResult.Failed(key, SourceProblem(ProblemKind.NETWORK,"load","NETWORK"))
                else CatalogResult.Complete(key, if (id == "magnit") listOf(ScanFixtures.offer(shop!!.id)) else emptyList(), 100))
            return Cancellable {}
        }
    }
    @Test fun savedKudakovskogoPreferenceDoesNotRestrictQueue() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("502731"), shop("388020")), PriceScope.STORE, true))
        CityScanCoordinator(repo, listOf(source), scheduler, { scheduler.now }).start(city)
        scheduler.settle()
        assertEquals(listOf("502731", "388020"), source.loaded)
        assertEquals(2, repo.read(city.id).offers.size)
        assertEquals(SourcePhase.COMPLETE, repo.read(city.id).sources.single().phase)
    }
    @Test fun dixyNeedsLoginDoesNotBlockMagnit() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val magnit = Source(discovery = DiscoveryResult(listOf(shop("502731"),shop("388020")),PriceScope.STORE,true))
        val dixy = Source("dixy", SourceLane.WEB, DiscoveryResult(emptyList(), PriceScope.STORE, false,
            problem = SourceProblem(ProblemKind.NEEDS_LOGIN, "login", "NEEDS_LOGIN")))
        CityScanCoordinator(repo, listOf(dixy,magnit), scheduler, { scheduler.now }).start(city); scheduler.settle()
        assertEquals(2, magnit.loaded.size)
        assertEquals(SourcePhase.NEEDS_LOGIN, repo.read(city.id).sources.first { it.sourceId == "dixy" }.phase)
    }
    @Test fun oneBadShopKeepsOtherShops() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("1"),shop("2"),shop("3")),PriceScope.STORE,true), failId = "2")
        CityScanCoordinator(repo,listOf(source),scheduler,{scheduler.now}).start(city); scheduler.settle()
        assertEquals(setOf("1","3"), repo.read(city.id).offers.map { it.shopId }.toSet())
        assertEquals(1, repo.read(city.id).sources.single().failed)
        assertEquals(SourcePhase.PARTIAL, repo.read(city.id).sources.single().phase)
    }
    @Test fun incompleteDiscoveryCannotReportComplete() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("1")),PriceScope.STORE,false))
        CityScanCoordinator(repo,listOf(source),scheduler,{scheduler.now}).start(city); scheduler.settle()
        assertEquals(SourcePhase.PARTIAL, repo.read(city.id).sources.single().phase)
    }
    @Test fun confirmedRemovedShopExcludedOnlyAfterCompleteDiscovery() {
        for (complete in listOf(false,true)) {
            val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
            repo.commit(repo.begin(city.id), ScanFixtures.complete("388020"))
            val source = Source(discovery = DiscoveryResult(listOf(shop("502731")),PriceScope.STORE,complete))
            CityScanCoordinator(repo,listOf(source),scheduler,{scheduler.now}).start(city); scheduler.settle()
            assertEquals(!complete, repo.read(city.id).catalogs.first { it.key.shopId == "388020" }.active)
        }
    }
    @Test fun runLimitMarksRemaining() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("1"),shop("2")),PriceScope.STORE,true))
        CityScanCoordinator(repo,listOf(source),scheduler,{scheduler.now}).start(city)
        scheduler.now += ScanPolicy.activeRunMs + 1; scheduler.drain()
        assertTrue(source.loaded.isEmpty())
        assertNotEquals(SourcePhase.COMPLETE, repo.read(city.id).sources.single().phase)
    }
    @Test fun configurationGapDefersRequestsWithoutReplacingRun() {
        val scheduler = Scheduler(); val repo = CityScanRepository(ScanFixtures.Disk())
        val source = Source(discovery = DiscoveryResult(listOf(shop("1"),shop("2")),PriceScope.STORE,true))
        val coordinator = CityScanCoordinator(repo,listOf(source),scheduler,{scheduler.now})
        val run = coordinator.start(city)
        coordinator.setRequestAllowed(false); scheduler.settle()
        assertTrue(source.loaded.isEmpty())
        coordinator.setRequestAllowed(true); scheduler.settle()
        assertEquals(run.id, repo.read(city.id).runId)
        assertEquals(2, source.loaded.size)
    }
}
