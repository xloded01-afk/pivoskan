package ru.pivoskan.app.data.scan

import com.google.gson.Gson
import com.google.gson.JsonNull
import ru.pivoskan.app.data.*
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.scan.ScanFixtures.city
import ru.pivoskan.app.data.scan.ScanFixtures.offer

class LegacyCatalogMigrationTest {
    @Test fun knownPriceEvidenceIsNotChangedByMigration() {
        for (evidence in OrdinaryPriceEvidence.entries) {
            val saved = offer().copy(priceEvidence = evidence)
            val json = Gson().toJson(mapOf("schema" to 1, "snapshot" to mapOf(
                "cityId" to city.id, "offers" to listOf(saved))))
            val migrated = CityScanSnapshot(city.id, null, LegacyCatalogMigration.decode(json, city.id))
            assertEquals(evidence, migrated.offers.single().priceEvidence)
        }
    }
    @Test fun preMetadataCacheCanRenderImmediatelyAfterAppLaunch() {
        val oldOffer = Gson().toJsonTree(offer()).asJsonObject.apply {
            remove("priceEvidence")
            remove("product")
        }
        val json = Gson().toJson(mapOf("schema" to 1, "snapshot" to mapOf(
            "cityId" to city.id, "offers" to listOf(oldOffer))))
        val repository = CityScanRepository(ScanFixtures.Disk(), legacy = { listOf(json) })
        val restored = repository.read(city.id)
        val displayed = restored.asLegacySnapshot().offers.single()
        assertEquals(10000, displayed.priceKopecks)
        assertEquals(OrdinaryPriceEvidence.UNKNOWN, displayed.priceEvidence)
        assertTrue(displayed.cached)
        assertNull(PriceEngine.products(restored, emptySet(), "", SortMode.CHEAPEST).single().minimum)
        val run = repository.begin(city.id)
        repository.commit(run, ScanFixtures.complete("388020"))
        assertEquals(2, repository.read(city.id).offers.size)
    }
    @Test fun absentOptionalTextAndUnknownEvidenceAreSafeToDisplay() {
        for (evidence in listOf(JsonNull.INSTANCE, com.google.gson.JsonPrimitive("future-value"))) {
            val old = Gson().toJsonTree(offer()).asJsonObject.apply {
                add("priceEvidence", evidence)
                add("hours", JsonNull.INSTANCE)
                getAsJsonObject("beer").remove("packageName")
            }
            val json = Gson().toJson(mapOf("schema" to 1, "snapshot" to mapOf(
                "cityId" to city.id, "offers" to listOf(old))))
            val migrated = CityScanSnapshot(city.id, null, LegacyCatalogMigration.decode(json, city.id))
            val displayed = migrated.asLegacySnapshot().offers.single()
            assertEquals("", displayed.hours)
            assertEquals("", displayed.beer.packageName)
            assertEquals(OrdinaryPriceEvidence.UNKNOWN, displayed.priceEvidence)
        }
    }
    @Test fun migrationKeepsValidRowsWhenOneIsInvalid() {
        val valid = offer()
        val json = Gson().toJson(mapOf("schema" to 1, "snapshot" to mapOf("cityId" to city.id,
            "offers" to listOf(valid, valid.copy(id = "bad", priceKopecks = -1), offer("388020")))))
        val records = LegacyCatalogMigration.decode(json, city.id)
        assertEquals(2, records.size)
        assertTrue(records.all { it.runId == null })
        assertEquals(setOf("502731", "388020"), records.map { it.key.shopId }.toSet())
    }
    @Test fun foreignCityIsNotMigrated() {
        val json = Gson().toJson(mapOf("schema" to 1, "snapshot" to mapOf("cityId" to "msk", "offers" to listOf(offer()))))
        assertTrue(LegacyCatalogMigration.decode(json, city.id).isEmpty())
    }
}
