package ru.pivoskan.app.data.scan

import org.junit.Assert.*
import org.junit.Test

class AutoScanSessionTest {
    @Test fun returningRotationAndDuplicateCallbacksNeverRestart() {
        val session = AutoScanSession(null) { }
        assertTrue(session.request(10, "balashikha"))
        repeat(5) { assertFalse(session.request(10, "balashikha")) }
    }
    @Test fun processRecreationInSameTaskDoesNotRestart() {
        var saved: String? = null
        assertTrue(AutoScanSession(saved) { saved = it }.request(10, "balashikha"))
        assertFalse(AutoScanSession(saved) { saved = it }.request(10, "balashikha"))
    }
    @Test fun fullyClosedAndNewTaskStartsEvenInSameProcess() {
        val session = AutoScanSession(null) { }
        assertTrue(session.request(10, "balashikha"))
        assertTrue(session.request(11, "balashikha"))
        assertFalse(session.request(11, "balashikha"))
    }
    @Test fun cityChangeAndManualRefreshAreExplicitNewScans() {
        val session = AutoScanSession(null) { }
        session.request(10, "balashikha")
        assertTrue(session.request(10, "moscow"))
        assertTrue(session.request(10, "moscow", true))
        assertFalse(session.request(10, "moscow"))
    }
    @Test fun failedServiceLaunchCanBeRetried() {
        val session = AutoScanSession(null) { }
        session.request(10, "balashikha")
        session.launchFailed()
        assertTrue(session.request(10, "balashikha"))
    }
}
