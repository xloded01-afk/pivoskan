package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test

class CrashReportTest {
    @Test fun reportKeepsFailureLocationWithoutExceptionMessages() {
        val error = IllegalStateException("secret-token-and-address")
        error.stackTrace = arrayOf(StackTraceElement("ru.pivoskan.app.MainActivity", "onCreate", "MainActivity.kt", 42))
        val report = CrashReport.format(error)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("MainActivity.onCreate"))
        assertTrue(report.contains("42"))
        assertFalse(report.contains("secret-token-and-address"))
    }
    @Test fun cyclicCausesAndHugeStacksAreBounded() {
        val a = RuntimeException(); val b = RuntimeException()
        a.initCause(b); b.initCause(a)
        a.stackTrace = Array(10000) { StackTraceElement("app.Test", "run", "Test.kt", it) }
        assertTrue(CrashReport.format(a).length < 16000)
    }
}
