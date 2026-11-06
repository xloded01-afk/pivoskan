package ru.pivoskan.app.data.scan

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.scan.ScanFixtures.city
import ru.pivoskan.app.data.scan.ScanFixtures.complete
import ru.pivoskan.app.data.scan.ScanFixtures.offer
import ru.pivoskan.app.data.scan.ScanFixtures.Disk

class CityScanRepositoryTest {
    private val cityId = city.id
    @Test fun twoMagnitShopsSurviveRestart() {
        val disk = Disk()
        val repo = CityScanRepository(disk)
        val run = repo.begin(cityId)
        assertTrue(repo.commit(run, complete()))
        assertTrue(repo.commit(run, complete("388020")))
        val restored = CityScanRepository(disk).read(cityId)
        assertEquals(setOf("502731", "388020"), restored.offers.map { it.shopId }.toSet())
        assertTrue(restored.offers.all { it.cached })
    }
    @Test fun cancelledRunCannotCommit() {
        val repo = CityScanRepository(Disk())
        val old = repo.begin(cityId)
        repo.invalidate(old)
        assertFalse(repo.commit(old, complete()))
        assertTrue(repo.read(cityId).offers.isEmpty())
    }
    @Test fun newCityRejectsLateOldReply() {
        val repo = CityScanRepository(Disk())
        val old = repo.begin(cityId)
        val next = repo.begin("msk")
        assertFalse(repo.commit(old, complete()))
        assertTrue(repo.read(next.cityId).offers.isEmpty())
    }
    @Test fun confirmedEmptyReplacesOnlyItsKey() {
        val repo = CityScanRepository(Disk())
        val run = repo.begin(cityId)
        repo.commit(run, complete()); repo.commit(run, complete("388020"))
        repo.commit(run, complete(offers = emptyList()))
        assertEquals(listOf("388020"), repo.read(cityId).offers.map { it.shopId })
        assertEquals(2, repo.read(cityId).catalogs.size)
    }
    @Test fun partialFailureKeepsOld() {
        val repo = CityScanRepository(Disk())
        val run = repo.begin(cityId)
        repo.commit(run, complete())
        repo.recordSource(run, SourceProgress("magnit", phase = SourcePhase.PARTIAL,
            results = mapOf(complete().key to SourceProblem(ProblemKind.NETWORK, "load", "NETWORK"))))
        assertEquals(1, repo.read(cityId).offers.size)
    }
    @Test fun diskFailureDoesNotPublishFreshMemory() {
        val disk = Disk(); val repo = CityScanRepository(disk)
        val run = repo.begin(cityId); repo.commit(run, complete())
        val saved = disk.data[cityId]
        disk.fail = true
        try { repo.commit(run, complete("388020")); fail("expected disk failure") } catch (_: java.io.IOException) { }
        assertEquals(saved, disk.data[cityId])
        assertEquals(listOf("502731"), repo.read(cityId).offers.map { it.shopId })
    }
    @Test fun staleManualImportCannotReplaceNewerOrCompleteCatalog() {
        val repo = CityScanRepository(Disk())
        val run = repo.begin(cityId); repo.commit(run, complete())
        repo.importVerified(StoredCatalog(complete().key, listOf(offer(price = 10).copy(fetchedAt = 40)), 50, null))
        assertEquals(10000, repo.read(cityId).offers.single().priceKopecks)
        assertFalse(repo.mergeManualOffers(cityId, listOf(offer(price = 5))))
    }
    @Test fun sourceResultNullSurvivesCodec() {
        val codec = CityScanCodec()
        val source = SourceProgress("magnit", phase = SourcePhase.COMPLETE, results = mapOf(complete().key to null))
        val restored = codec.decode(codec.encode(CityScanSnapshot(cityId, "r", sources = listOf(source))), cityId)!!
        assertTrue(restored.sources.single().results.containsKey(complete().key))
        assertEquals(1, restored.sources.single().succeeded)
    }
    @Test fun foreignOfferCannotBeStoredAtAnotherAddress() {
        val repo = CityScanRepository(Disk())
        val run = repo.begin(cityId)
        try { repo.commit(run, complete("388020", listOf(offer()))); fail("wrong shop accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(repo.read(cityId).offers.isEmpty())
    }
    @Test fun repeatedProgressDoesNotRewriteWholeCity() {
        val disk = Disk(); val repo = CityScanRepository(disk)
        val run = repo.begin(cityId); repo.commit(run, complete())
        val before = disk.writes
        repeat(20) { repo.recordSource(run, SourceProgress("magnit", phase = SourcePhase.RUNNING)) }
        assertEquals(before, disk.writes)
        val done = SourceProgress("magnit", phase = SourcePhase.COMPLETE)
        repo.recordSource(run, done); repo.recordSource(run, done)
        assertEquals(before + 1, disk.writes)
    }
}
