package ru.pivoskan.app.data.scan

import org.junit.Assert.*
import org.junit.Test

class BackgroundScanPolicyTest {
    @Test fun oldCancellationCannotFinishReplacementAndCityOwnershipMoves() {
        val owner = ServiceRunOwnership()
        owner.expect("balashikha", "old")
        assertFalse(owner.accepts(CityScanSnapshot("balashikha", null)))
        assertFalse(owner.accepts(CityScanSnapshot("balashikha", "old")))
        assertTrue(owner.accepts(CityScanSnapshot("balashikha", "new")))
        assertFalse(owner.accepts(CityScanSnapshot("balashikha", "other")))
        owner.expect("moscow", "new")
        assertFalse(owner.accepts(CityScanSnapshot("balashikha", "new")))
        assertTrue(owner.accepts(CityScanSnapshot("moscow", "moscow-run")))
    }
    @Test fun foregroundServiceAllowsScreenOffRequests() {
        assertTrue(BackgroundScanPolicy.canRequest(false, false, true))
        assertFalse(BackgroundScanPolicy.canRequest(false, false, false))
        assertTrue(BackgroundScanPolicy.canRequest(true, true, false))
        assertFalse(BackgroundScanPolicy.canRequest(true, false, false))
    }
    @Test fun allTerminalStatesStopServiceIncludingLoginWait() {
        assertFalse(BackgroundScanPolicy.finished(emptyList()))
        assertFalse(BackgroundScanPolicy.finished(listOf(SourceProgress("magnit", phase = SourcePhase.RUNNING))))
        assertFalse(BackgroundScanPolicy.finished(listOf(SourceProgress("magnit"))))
        assertTrue(BackgroundScanPolicy.finished(listOf(SourceProgress("magnit", phase = SourcePhase.COMPLETE),
            SourceProgress("dixy", phase = SourcePhase.NEEDS_LOGIN))))
        assertTrue(BackgroundScanPolicy.finished(listOf(SourceProgress("magnit", phase = SourcePhase.PARTIAL))))
    }
}
