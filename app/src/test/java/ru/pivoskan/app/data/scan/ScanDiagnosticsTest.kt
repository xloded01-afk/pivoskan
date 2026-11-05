package ru.pivoskan.app.data.scan

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ScanDiagnosticsTest {
    @Test fun exportContainsOnlyAllowedFields() {
        val event = ScanDiagnosticEvent("run-1", "magnit", "502731", "load", "HTTP_403", 123L, 0)
        val json = ScanDiagnostics.export(listOf(event), "1.4", "14", "130")
        val root = JsonParser.parseString(json).asJsonObject
        val row = root.getAsJsonArray("events").single().asJsonObject
        assertEquals(setOf("runId", "sourceId", "shopId", "stage", "code", "at", "count"), row.keySet())
        assertEquals("502731", row.get("shopId").asString)
        assertFalse(json.contains("cookie", true))
        assertFalse(json.contains("password", true))
    }

    @Test fun limitsAreFinite() {
        assertEquals(1, ScanPolicy.maxTransientRetries)
        assertEquals(2_700_000L, ScanPolicy.activeRunMs)
        assertEquals(100, ScanPolicy.maxDiscoveryPages)
        assertEquals(5_000, ScanPolicy.maxOffersPerShop)
    }

    @Test fun diagnosticRejectsUnstructuredSecrets() {
        val event = ScanDiagnosticEvent("r", "magnit", "502731", "load", "password=secret", 1, 0)
        val json = ScanDiagnostics.export(listOf(event), "1.4", "14", "130")
        assertFalse(json.contains("secret"))
        assertTrue(json.contains("UNKNOWN"))
    }
}
