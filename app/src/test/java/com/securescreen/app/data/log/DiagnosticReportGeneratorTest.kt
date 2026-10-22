package com.securescreen.app.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportGeneratorTest {

    @Test
    fun testRedactUserPackageAnonymization() {
        val packageMap = mutableMapOf<String, String>()
        val pkg1 = "com.popular.messaging.app"
        val pkg2 = "com.private.banking"

        val redacted1 = DiagnosticReportGenerator.redactPackage(pkg1, "com.securescreen.app", packageMap)
        val redacted2 = DiagnosticReportGenerator.redactPackage(pkg2, "com.securescreen.app", packageMap)
        val redacted1Again = DiagnosticReportGenerator.redactPackage(pkg1, "com.securescreen.app", packageMap)

        assertEquals("[Protected App #1]", redacted1)
        assertEquals("[Protected App #2]", redacted2)
        assertEquals("[Protected App #1]", redacted1Again)
    }

    @Test
    fun testPreservesSelfAndSystemPackages() {
        val packageMap = mutableMapOf<String, String>()

        val selfRedacted = DiagnosticReportGenerator.redactPackage("com.securescreen.app", "com.securescreen.app", packageMap)
        val systemUiRedacted = DiagnosticReportGenerator.redactPackage("com.android.systemui", "com.securescreen.app", packageMap)
        val androidRedacted = DiagnosticReportGenerator.redactPackage("android", "com.securescreen.app", packageMap)

        assertEquals("SecureScreen", selfRedacted)
        assertEquals("com.android.systemui", systemUiRedacted)
        assertEquals("android", androidRedacted)
    }

    @Test
    fun testRedactsDetailsTextWithEmbeddedPackages() {
        val packageMap = mutableMapOf<String, String>()
        val input = "Active foreground: com.secret.journal (com.secret.journal)"

        val sanitized = DiagnosticReportGenerator.redactDetails(input, "com.securescreen.app", packageMap)

        assertFalse(sanitized.contains("com.secret.journal"))
        assertTrue(sanitized.contains("[Protected App #1]"))
    }

    @Test
    fun testSecurityEventCreation() {
        val event = SecurityEvent(
            id = 1,
            timestamp = 1700000000000L,
            eventType = SecurityEventType.PROTECTION_ENABLED,
            severity = SecurityEventSeverity.INFO,
            title = "Protection Enabled",
            details = "Protection activated manually",
            packageName = null
        )

        assertEquals(1L, event.id)
        assertEquals(SecurityEventType.PROTECTION_ENABLED, event.eventType)
        assertEquals(SecurityEventSeverity.INFO, event.severity)
        assertEquals("Protection Enabled", event.title)
    }
}
