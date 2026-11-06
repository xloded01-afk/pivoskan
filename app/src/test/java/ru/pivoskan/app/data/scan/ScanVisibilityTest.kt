package ru.pivoskan.app.data.scan

import org.junit.Assert.*
import org.junit.Test

class ScanVisibilityTest {
    @Test fun rotationKeepsSameRun() {
        val events = mutableListOf<VisibilityTransition>()
        val visibility = ScanVisibility({ events += it })
        visibility.activityStarted("main1")
        visibility.activityStopped("main1", true)
        assertFalse(visibility.canRequest)
        visibility.activityStarted("main2")
        assertEquals(listOf(VisibilityTransition.VISIBLE),events)
        assertTrue(visibility.canRequest)
    }
    @Test fun internalLoginReturnKeepsSameRun() {
        val events = mutableListOf<VisibilityTransition>()
        val v = ScanVisibility({ events += it })
        v.activityStarted("main"); v.activityStarted("login"); v.activityStopped("main",false)
        v.activityStarted("main"); v.activityStopped("login",false)
        assertEquals(listOf(VisibilityTransition.VISIBLE),events)
    }
    @Test fun screenOffCancelsAndScreenOnVisibleRestarts() {
        val events = mutableListOf<VisibilityTransition>()
        val v = ScanVisibility({ events += it })
        v.activityStarted("main"); v.screenInteractive(false)
        assertFalse(v.canRequest)
        v.screenInteractive(true)
        assertEquals(listOf(VisibilityTransition.VISIBLE,VisibilityTransition.HIDDEN,VisibilityTransition.VISIBLE),events)
    }
    @Test fun backgroundReturnStartsExactlyOnce() {
        val events = mutableListOf<VisibilityTransition>()
        val v = ScanVisibility({ events += it })
        v.activityStarted("main"); v.activityStopped("main",false)
        v.screenInteractive(true); v.activityStarted("main"); v.activityStarted("main")
        assertEquals(3,events.size)
    }
    @Test fun cancelDoesNotImmediatelyRestart() {
        val events = mutableListOf<VisibilityTransition>()
        val v = ScanVisibility({ events += it })
        v.activityStarted("main")
        // User cancel is independent: unchanged lifecycle must not emit another VISIBLE.
        v.screenInteractive(true); v.activityStarted("main")
        assertEquals(1,events.size)
    }
    @Test fun cityPickerStartsOnlyChosenCity() {
        val events = mutableListOf<VisibilityTransition>()
        val v = ScanVisibility({ events += it })
        v.activityStarted("picker"); v.activityStarted("main"); v.activityStopped("picker",false)
        assertEquals(1,events.size)
        // City selection is a separate explicit action; changing activities must not restart it.
        assertTrue(v.canRequest)
    }
}
