package ru.pivoskan.app.data.scan

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.scan.ScanFixtures.city
import ru.pivoskan.app.data.scan.ScanFixtures.offer

class LegacyCatalogMigrationTest {
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
