package com.securescreen.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundAppDetectorTest {

    private lateinit var detector: ForegroundAppDetector

    @Before
    fun setUp() {
        detector = ForegroundAppDetector()
    }

    @Test
    fun testSamsungQuickstepRecentsIsDetected() {
        val isRecents = detector.isRecentsVisible(
            "com.sec.android.app.launcher",
            "com.android.quickstep.RecentsActivity"
        )
        assertTrue("Samsung Quickstep RecentsActivity should be detected as Recents", isRecents)
    }

    @Test
    fun testSamsungHomeLauncherIsNotRecents() {
        val isRecents = detector.isRecentsVisible(
            "com.sec.android.app.launcher",
            "com.sec.android.app.launcher.activities.LauncherActivity"
        )
        assertFalse("Samsung Home launcher activity must NOT be detected as Recents", isRecents)
    }

    @Test
    fun testNovaLauncherHomeIsNotRecents() {
        val isRecents = detector.isRecentsVisible(
            "com.teslacoilsw.launcher",
            "com.teslacoilsw.launcher.NovaLauncher"
        )
        assertFalse("Nova Launcher home screen must NOT be detected as Recents", isRecents)
    }

    @Test
    fun testSystemUiRecentsIsDetected() {
        val isRecents = detector.isRecentsVisible(
            "com.android.systemui",
            "com.android.systemui.recents.RecentsActivity"
        )
        assertTrue("SystemUI recents activity should be detected as Recents", isRecents)
    }

    @Test
    fun testSystemUiNotificationShadeIsNotRecents() {
        val isRecents = detector.isRecentsVisible(
            "com.android.systemui",
            "com.android.systemui.statusbar.phone.NotificationShade"
        )
        assertFalse("SystemUI notification shade must NOT be detected as Recents", isRecents)
    }

    @Test
    fun testSystemUiWithNullClassIsNotRecents() {
        val isRecents = detector.isRecentsVisible("com.android.systemui", null)
        assertFalse("SystemUI with null class must NOT be blanket-detected as Recents", isRecents)
    }

    @Test
    fun testPixelLauncherQuickstepIsDetected() {
        val isRecents = detector.isRecentsVisible(
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.nexuslauncher.QuickstepLauncher"
        )
        assertTrue("Pixel launcher Quickstep should be detected as Recents", isRecents)
    }

    @Test
    fun testPixelLauncherHomeIsNotRecents() {
        val isRecents = detector.isRecentsVisible(
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
        )
        assertFalse("Pixel launcher Home must NOT be detected as Recents", isRecents)
    }

    @Test
    fun testProtectedAppIsNotRecents() {
        val isRecents = detector.isRecentsVisible(
            "money.super.payments",
            "com.supermoney.payments.MainActivity"
        )
        assertFalse("Protected user app must NOT be detected as Recents", isRecents)
    }

    @Test
    fun testNullAndBlankPackagesAreNotRecents() {
        assertFalse(detector.isRecentsVisible(null, null))
        assertFalse(detector.isRecentsVisible("", ""))
        assertFalse(detector.isRecentsVisible("   ", "com.android.quickstep.RecentsActivity"))
    }

    @Test
    fun testIsSystemUiPackage() {
        assertTrue(detector.isSystemUiPackage("com.android.systemui"))
        assertFalse(detector.isSystemUiPackage("com.sec.android.app.launcher"))
        assertFalse(detector.isSystemUiPackage("money.super.payments"))
    }
}
