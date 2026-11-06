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
}
