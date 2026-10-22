package com.securescreen.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSessionEvaluatorTest {

    private val protectedPackages = setOf("com.protected.bank", "com.protected.chat")
    private val servicePackage = "com.securescreen.app"

    @Test
    fun testEnteringProtectedAppActivatesSessionAndTransparentMode() {
        val initialState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 10_000L,
            currentForeground = "com.protected.bank",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = initialState,
            currentSecureMode = null
        )

        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertFalse(result.shouldTeardownImmediately)
        assertTrue(result.updatedState.protectedSessionActive)
        assertEquals(0L, result.updatedState.sessionExitCandidateSinceElapsedMs)
    }

    @Test
    fun testTransientNullSnapshotDoesNotDropProtectionOrExitSession() {
        // Simulates reading a protected app without new UsageEvents (flicker regression test)
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 15_000L,
            currentForeground = null, // Transient null snapshot
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(
            "Transient null foreground snapshot must NOT drop transparent protection",
            SecureOverlayManager.Mode.TRANSPARENT,
            result.targetMode
        )
        assertTrue(result.updatedState.protectedSessionActive)
        assertEquals(0L, result.updatedState.sessionExitCandidateSinceElapsedMs)
    }

    @Test
    fun testImeForegroundMaintainsSessionAndTransparentMode() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 20_000L,
            currentForeground = "com.google.android.inputmethod.latin",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = true,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertTrue(result.updatedState.protectedSessionActive)
    }

    @Test
    fun testSystemUiNotificationShadeDoesNotExitSession() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 25_000L,
            currentForeground = "com.android.systemui",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false, // Not recents (e.g. notifications shade)
            isLauncherPackage = false,
            isSystemUiPackage = true,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertTrue(result.updatedState.protectedSessionActive)
    }

    @Test
    fun testOpeningRecentsFromProtectedAppImmediatelyShowsTransparentOverlay() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 30_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isSystemUiPackage = false,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(
            "Recents overview during active protected session must select TRANSPARENT overlay to keep Recents visible to user",
            SecureOverlayManager.Mode.TRANSPARENT,
            result.targetMode
        )
        assertFalse(result.shouldTeardownImmediately)
        assertTrue(result.updatedState.protectedSessionActive)
    }

    @Test
    fun testOpeningRecentsViaSignalImmediatelyShowsTransparentOverlay() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 35_000L // Active recents intent signal
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 34_000L,
            currentForeground = "com.android.systemui",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false, // Class not yet updated, but signal is active
            isLauncherPackage = false,
            isSystemUiPackage = true,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(
            "Recents via signal must select TRANSPARENT overlay, not OPAQUE_MASK",
            SecureOverlayManager.Mode.TRANSPARENT,
            result.targetMode
        )
        assertTrue(result.updatedState.protectedSessionActive)
    }

    @Test
    fun testReturningFromRecentsToProtectedAppMaintainsTransparentModeWithoutBlackFlash() {
        val recentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 40_000L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 38_000L,
            currentForeground = "com.protected.bank",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = recentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals(
            "Returning to protected app must seamlessly maintain TRANSPARENT mode with zero mode switch or black flash",
            SecureOverlayManager.Mode.TRANSPARENT,
            result.targetMode
        )
        assertEquals(
            "Forced recents signal must be cleared when protected app resumes",
            0L,
            result.updatedState.forceRecentsMaskUntilElapsedMs
        )
    }

    @Test
    fun testLeavingRecentsToHomeLauncherTearsDownImmediatelyWithZeroBlackLingering() {
        val recentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 50_000L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 45_000L,
            currentForeground = "com.teslacoilsw.launcher", // Nova launcher home
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = true,
            isSystemUiPackage = false,
            currentState = recentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull("Leaving recents to launcher must NOT show overlay", result.targetMode)
        assertTrue(
            "Leaving recents to launcher must tear down immediately",
            result.shouldTeardownImmediately
        )
        assertEquals(
            "Recents signal must be cleared when landing on home launcher",
            0L,
            result.updatedState.forceRecentsMaskUntilElapsedMs
        )
    }

    @Test
    fun testLeavingRecentsToUnprotectedAppTearsDownCleanly() {
        val recentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 45_000L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = recentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull("Target mode must be null when leaving to an unprotected app", result.targetMode)
        assertFalse(
            "Delayed teardown is scheduled rather than immediate hard teardown during exit candidate window",
            result.shouldTeardownImmediately
        )
        assertTrue(result.updatedState.protectedSessionActive)
        assertEquals(45_000L, result.updatedState.sessionExitCandidateSinceElapsedMs)

        // After sustained exit confirmation window expires:
        val expiredResult = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 47_000L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = result.updatedState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull(expiredResult.targetMode)
        assertFalse(expiredResult.updatedState.protectedSessionActive)
        assertTrue(
            "Session expiration must tear down immediately",
            expiredResult.shouldTeardownImmediately
        )
    }

    @Test
    fun testRecentsWithoutProtectedAppsConfiguredNeverMasks() {
        // Opening recents when NO protected apps are configured
        val inactiveState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 60_000L
        )

        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 55_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = emptySet(), // No protected apps configured
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isSystemUiPackage = false,
            currentState = inactiveState,
            currentSecureMode = null
        )

        assertNull("Recents must NEVER be masked when no protected apps are configured", result.targetMode)
        assertFalse(result.updatedState.protectedSessionActive)
    }

    @Test
    fun testSwitchingToUnprotectedAppRequiresSustainedConfirmationToExit() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L
        )

        // Tick 1: User switches to Calculator at T=100_000L. Confirmation window begins.
        val tick1 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 100_000L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT,
            sessionExitConfirmMs = 1_500L
        )

        assertTrue(tick1.updatedState.protectedSessionActive)
        assertEquals(100_000L, tick1.updatedState.sessionExitCandidateSinceElapsedMs)

        // Tick 2: 500ms later (T=100_500L). Still in grace period.
        val tick2 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 100_500L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = tick1.updatedState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT,
            sessionExitConfirmMs = 1_500L
        )

        assertTrue(tick2.updatedState.protectedSessionActive)

        // Tick 3: 1600ms later (T=101_600L). Confirmation window expired -> session confirmed ended.
        val tick3 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 101_600L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isSystemUiPackage = false,
            currentState = tick2.updatedState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT,
            sessionExitConfirmMs = 1_500L
        )

        assertFalse("Protected session must end after sustained exit confirmation", tick3.updatedState.protectedSessionActive)
        assertNull(tick3.targetMode)
    }

    @Test
    fun testRecentsNeverSelectsOpaqueMask() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 50_000L
        )

        val combinations = listOf(
            // (isRecentsDetected, isLauncherPackage, hasSignal)
            Triple(true, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(false, false, true)
        )

        for ((isRecents, isLauncher, hasSignal) in combinations) {
            val state = if (hasSignal) activeState else activeState.copy(forceRecentsMaskUntilElapsedMs = 0L)
            val result = ProtectionSessionEvaluator.evaluate(
                nowElapsedMs = 40_000L,
                currentForeground = "com.sec.android.app.launcher",
                protectedPackages = protectedPackages,
                servicePackageName = servicePackage,
                fullSystemProtectionEnabled = false,
                shouldBypassSystemWideProtection = false,
                isImeForeground = false,
                isRecentsDetected = isRecents,
                isLauncherPackage = isLauncher,
                isSystemUiPackage = false,
                currentState = state,
                currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
            )

            assertEquals(
                "Recents must always select TRANSPARENT overlay to keep Recents visible to user",
                SecureOverlayManager.Mode.TRANSPARENT,
                result.targetMode
            )
            org.junit.Assert.assertNotEquals(
                "Recents must NEVER select OPAQUE_MASK",
                SecureOverlayManager.Mode.OPAQUE_MASK,
                result.targetMode
            )
        }
    }

    @Test
    fun testRecentsOverrideActivatesFromProtectedAppAndMaintainsSystemWideTransparentPath() {
        val activeState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )

        // User swipes up from Brave to Recents on One UI Launcher
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 10_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = activeState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals("Recents override must select TRANSPARENT overlay", SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertFalse("Must not tear down overlay during Recents", result.shouldTeardownImmediately)
        assertTrue("Recents override state must be active", result.updatedState.isRecentsOverrideActive)
        assertTrue("Protected session must remain active", result.updatedState.protectedSessionActive)
    }

    @Test
    fun testRemainingInRecentsMaintainsOverrideAcrossMultipleTicksWithTransientSnapshots() {
        var state = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = true
        )

        // Tick 1: User is browsing cards on One UI launcher with null class name
        val tick1 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 15_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false, // Class name was delayed/null
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, tick1.targetMode)
        assertFalse(tick1.shouldTeardownImmediately)
        assertTrue(tick1.updatedState.isRecentsOverrideActive)
        state = tick1.updatedState

        // Tick 2: Transient null package snapshot during card fling
        val tick2 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 15_100L,
            currentForeground = null,
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertEquals("Transient null snapshot must not drop overlay", SecureOverlayManager.Mode.TRANSPARENT, tick2.targetMode)
        assertFalse(tick2.shouldTeardownImmediately)
        assertTrue(tick2.updatedState.isRecentsOverrideActive)
        state = tick2.updatedState

        // Tick 3: SystemUI navigation bar interaction during Recents
        val tick3 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 15_200L,
            currentForeground = "com.android.systemui",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = true,
            currentState = state,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertEquals("SystemUI transition must maintain Recents override", SecureOverlayManager.Mode.TRANSPARENT, tick3.targetMode)
        assertFalse(tick3.shouldTeardownImmediately)
        assertTrue(tick3.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testReturningFromRecentsToProtectedAppSeamlesslyTransitionsWithoutDroppingOverlay() {
        val inRecentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = true
        )

        // User taps the protected bank app card in Recents
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 20_000L,
            currentForeground = "com.protected.bank",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals("Returning to protected app must retain TRANSPARENT overlay", SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertFalse(result.shouldTeardownImmediately)
        assertTrue(result.updatedState.protectedSessionActive)
        assertFalse("Recents override ends as regular protected app session resumes", result.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testLeavingRecentsToHomeWorkspaceImmediatelyDeactivatesOverrideAndTearsDown() {
        val inRecentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 25_000L,
            isRecentsOverrideActive = true
        )

        // User taps Home button or swipes home to exit Recents to desktop workspace
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 21_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = true,
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull("Exiting Recents to Home workspace must remove overlay", result.targetMode)
        assertTrue("Must tear down immediately when landing on Home workspace", result.shouldTeardownImmediately)
        assertFalse("Recents override must be deactivated", result.updatedState.isRecentsOverrideActive)
        assertFalse("Protected session must end on home workspace", result.updatedState.protectedSessionActive)
        assertEquals("Recents signal must be cleared", 0L, result.updatedState.forceRecentsMaskUntilElapsedMs)
    }

    @Test
    fun testLeavingRecentsToUnprotectedAppImmediatelyDeactivatesOverrideAndTearsDown() {
        val inRecentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 30_000L,
            isRecentsOverrideActive = true
        )

        // User selects Calculator from Recents
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 22_000L,
            currentForeground = "com.google.android.calculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull("Exiting Recents to unprotected app must remove overlay", result.targetMode)
        assertTrue("Must tear down immediately when choosing an unprotected app from Recents", result.shouldTeardownImmediately)
        assertFalse("Recents override must be deactivated", result.updatedState.isRecentsOverrideActive)
        assertFalse("Protected session ends", result.updatedState.protectedSessionActive)
        assertEquals("Recents signal must be cleared", 0L, result.updatedState.forceRecentsMaskUntilElapsedMs)
    }

    @Test
    fun testRecentsOverrideActivatesWhenOpenedFromUnprotectedApp() {
        val unprotectedAppState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )

        // Opening Recents from an unprotected app (or home) while protected apps are configured
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 30_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = unprotectedAppState,
            currentSecureMode = null
        )

        assertEquals("Opening Recents from unprotected app must activate TRANSPARENT override to hide thumbnails", SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertFalse("Must not tear down overlay during Recents", result.shouldTeardownImmediately)
        assertTrue("Recents override must activate", result.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testRecentsOverrideDoesNotActivateWhenNoProtectedAppsConfigured() {
        val unprotectedAppState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )

        // Opening Recents when NO protected apps are configured
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 30_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = emptySet(), // No protected apps configured
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = unprotectedAppState,
            currentSecureMode = null
        )

        assertNull("Recents must NOT activate overlay when no protected apps are configured", result.targetMode)
        assertFalse("Recents override must not activate without protected apps", result.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testTransitionBraveToRecentsToUnprotectedApp() {
        // Step 1: User is in Brave (protected)
        var state = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )
        val step1 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 10_000L,
            currentForeground = "com.protected.bank",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state,
            currentSecureMode = null
        )
        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, step1.targetMode)
        assertTrue(step1.updatedState.protectedSessionActive)
        state = step1.updatedState

        // Step 2: User opens Recents from Brave
        val step2 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 12_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertEquals("Recents must be protected", SecureOverlayManager.Mode.TRANSPARENT, step2.targetMode)
        assertTrue(step2.updatedState.isRecentsOverrideActive)
        state = step2.updatedState

        // Step 3: User switches to Calculator (unprotected app) from Recents
        val step3 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 14_000L,
            currentForeground = "com.sec.android.app.popupcalculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertNull("Unprotected app must NOT be protected", step3.targetMode)
        assertTrue("Must tear down immediately on unprotected app", step3.shouldTeardownImmediately)
        assertFalse("Override must be deactivated", step3.updatedState.isRecentsOverrideActive)
        assertFalse("Protected session must end", step3.updatedState.protectedSessionActive)
    }

    @Test
    fun testTransitionUnprotectedAppToRecentsToHome() {
        // Step 1: User is in Calculator (unprotected)
        val state1 = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )
        val step1 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 20_000L,
            currentForeground = "com.sec.android.app.popupcalculator",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state1,
            currentSecureMode = null
        )
        assertNull("Calculator must be unprotected", step1.targetMode)

        // Step 2: User opens Recents from Calculator
        val step2 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 22_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = step1.updatedState,
            currentSecureMode = null
        )
        assertEquals("Recents opened from unprotected app must be protected", SecureOverlayManager.Mode.TRANSPARENT, step2.targetMode)
        assertTrue("Recents override active", step2.updatedState.isRecentsOverrideActive)

        // Step 3: User exits Recents to Home desktop
        val step3 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 24_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = true, // Home desktop confirmed
            isSystemUiPackage = false,
            currentState = step2.updatedState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertNull("Home desktop must NOT be protected", step3.targetMode)
        assertTrue("Must tear down immediately on Home", step3.shouldTeardownImmediately)
        assertFalse("Override deactivated", step3.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testTransitionUnprotectedAppToRecentsToBrave() {
        // Step 1: User is in Calculator and opens Recents
        val state1 = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = false,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )
        val step1 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 30_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = state1,
            currentSecureMode = null
        )
        assertEquals(SecureOverlayManager.Mode.TRANSPARENT, step1.targetMode)
        assertTrue(step1.updatedState.isRecentsOverrideActive)

        // Step 2: User taps Brave from Recents
        val step2 = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 32_000L,
            currentForeground = "com.protected.bank",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = step1.updatedState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )
        assertEquals("Brave must be protected seamlessly", SecureOverlayManager.Mode.TRANSPARENT, step2.targetMode)
        assertFalse("Overlay must NOT tear down", step2.shouldTeardownImmediately)
        assertTrue("Protected session is now active", step2.updatedState.protectedSessionActive)
        assertFalse("Recents override ends as normal app protection resumes", step2.updatedState.isRecentsOverrideActive)
    }

    @Test
    fun testLeavingRecentsToHomeLauncherWithoutExplicitWorkspaceClassTearsDownImmediately() {
        val inRecentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = true,
            lastRecentsActiveElapsedMs = 20_000L
        )

        // User arrives on Samsung home launcher desktop, but class name was null/blank (isLauncherHomeWorkspace=false)
        // After grace period expires (3s after recents ended, exceeding 2s grace), it must tear down promptly
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 23_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false, // class name was omitted or generic
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertNull("Exiting Recents to Home launcher even without explicit workspace class must remove overlay", result.targetMode)
        assertTrue("Must tear down immediately when landing on Home launcher", result.shouldTeardownImmediately)
        assertFalse("Recents override must be deactivated", result.updatedState.isRecentsOverrideActive)
        assertFalse("Protected session must end on home launcher", result.updatedState.protectedSessionActive)
    }

    @Test
    fun testRecentsOverrideExpiresAfterGracePeriodWhenRecentsClosesIntoTransientState() {
        val inRecentsState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = true,
            lastRecentsActiveElapsedMs = 10_000L // Recents was last active at 10s
        )

        // At 11s (1s after Recents ended), transient state is within 2s grace period
        val withinGrace = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 11_000L,
            currentForeground = null, // Transient / null snapshot
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT,
            recentsOverrideMaxGraceMs = 2_000L
        )
        assertEquals("Within grace period, overlay should temporarily remain", SecureOverlayManager.Mode.TRANSPARENT, withinGrace.targetMode)
        assertFalse(withinGrace.shouldTeardownImmediately)
        assertTrue(withinGrace.updatedState.isRecentsOverrideActive)

        // At 13s (3s after Recents ended), grace period has expired! Must tear down immediately!
        val afterGrace = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 13_000L,
            currentForeground = null, // Still transient / stale
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = false,
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = false,
            isLauncherPackage = false,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = inRecentsState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT,
            recentsOverrideMaxGraceMs = 2_000L
        )
        assertNull("After grace period expires, stale Recents state must NOT keep overlay active", afterGrace.targetMode)
        assertTrue("Must tear down immediately when Recents override grace period expires", afterGrace.shouldTeardownImmediately)
        assertFalse("Override must be deactivated", afterGrace.updatedState.isRecentsOverrideActive)
        assertFalse("Protected session must end", afterGrace.updatedState.protectedSessionActive)
    }

    @Test
    fun testSystemWideModeUnchangedByRecentsLogic() {
        val systemWideState = ProtectionSessionEvaluator.EvaluationState(
            protectedSessionActive = true,
            sessionExitCandidateSinceElapsedMs = 0L,
            forceRecentsMaskUntilElapsedMs = 0L,
            isRecentsOverrideActive = false
        )

        // System-wide mode always holds TRANSPARENT across any foreground package
        val result = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = 40_000L,
            currentForeground = "com.sec.android.app.launcher",
            protectedPackages = protectedPackages,
            servicePackageName = servicePackage,
            fullSystemProtectionEnabled = true, // System-wide active
            shouldBypassSystemWideProtection = false,
            isImeForeground = false,
            isRecentsDetected = true,
            isLauncherPackage = true,
            isLauncherHomeWorkspace = false,
            isSystemUiPackage = false,
            currentState = systemWideState,
            currentSecureMode = SecureOverlayManager.Mode.TRANSPARENT
        )

        assertEquals("System-wide mode must continue returning TRANSPARENT", SecureOverlayManager.Mode.TRANSPARENT, result.targetMode)
        assertFalse(result.shouldTeardownImmediately)
        assertTrue(result.updatedState.protectedSessionActive)
    }
}
