package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test
import ru.pivoskan.app.data.scan.*

class ProductMetadataTest {
    private val codec = CityScanCodec()
    private fun snapshot(image: String? = null) = CityScanSnapshot("balashikha", "run", listOf(
        StoredCatalog(CatalogKey("balashikha", "magnit", "502731"), listOf(ScanFixtures.offer().copy(
            product = ProductDescriptor("123", volumeMl = 500, imageUrl = image),
            priceEvidence = OrdinaryPriceEvidence.CONFIRMED)), 100, "run")))
    @Test fun metadataRoundTripsAndLosesFreshness() {
        val value = codec.decode(codec.encode(snapshot()), "balashikha")!!
        assertEquals(500, value.offers.single().product!!.volumeMl)
        assertEquals(OrdinaryPriceEvidence.CONFIRMED, value.offers.single().priceEvidence)
        assertTrue(value.offers.single().cached)
    }
    @Test fun unsafeImageCannotBeStored() {
        for (url in listOf("http://img.fix-price.com/a.jpg", "https://evil.example/a", "file:///etc/passwd",
            "https://img.fix-price.com@evil.example/a", "https://img.fix-price.com:8443/a")) {
            assertFalse(CityScanCodec.valid(snapshot(url).catalogs.single()))
        }
    }
    @Test fun legacyEvidenceDefaultsToUnknown() {
        val json = codec.encode(snapshot()).replace("\"priceEvidence\":\"CONFIRMED\",", "")
            .replace(",\"priceEvidence\":\"CONFIRMED\"", "")
        assertEquals(OrdinaryPriceEvidence.UNKNOWN, codec.decode(json, "balashikha")!!.offers.single().priceEvidence)
    }
    @Test fun cachedNullHoursAndUnknownEvidenceCannotCrashOfferCopy() {
        val tree = com.google.gson.JsonParser.parseString(codec.encode(snapshot())).asJsonObject
        val offer = tree.getAsJsonObject("snapshot").getAsJsonArray("catalogs")[0].asJsonObject
            .getAsJsonArray("offers")[0].asJsonObject
        offer.add("hours", com.google.gson.JsonNull.INSTANCE)
        offer.addProperty("priceEvidence", "future-value")
        val restored = codec.decode(tree.toString(), "balashikha")!!.asLegacySnapshot().offers.single()
        assertEquals("", restored.hours)
        assertEquals(OrdinaryPriceEvidence.UNKNOWN, restored.priceEvidence)
        assertTrue(restored.cached)
    }
    @Test fun invalidNullEvidenceCannotEnterRepositoryUnvalidated() {
        val record = snapshot().catalogs.single()
        val unsafe = com.google.gson.Gson().fromJson(
            com.google.gson.Gson().toJson(record).replace("\"priceEvidence\":\"CONFIRMED\"", "\"priceEvidence\":null"),
            StoredCatalog::class.java)
        assertFalse(CityScanCodec.valid(unsafe))
    }
}
