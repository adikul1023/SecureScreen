package com.securescreen.app.service

object ProtectionSessionEvaluator {

    const val DEFAULT_SESSION_EXIT_CONFIRM_MS = 1_500L
    const val RECENTS_OVERRIDE_MAX_GRACE_MS = 2_000L

    data class EvaluationState(
        val protectedSessionActive: Boolean,
        val sessionExitCandidateSinceElapsedMs: Long,
        val forceRecentsMaskUntilElapsedMs: Long,
        val isRecentsOverrideActive: Boolean = false,
        val lastRecentsActiveElapsedMs: Long = 0L
    )

    data class EvaluationResult(
        val targetMode: SecureOverlayManager.Mode?,
        val shouldTeardownImmediately: Boolean,
        val updatedState: EvaluationState
    )

    fun evaluate(
        nowElapsedMs: Long,
        currentForeground: String?,
        protectedPackages: Set<String>,
        servicePackageName: String,
        fullSystemProtectionEnabled: Boolean,
        shouldBypassSystemWideProtection: Boolean,
        isImeForeground: Boolean,
        isRecentsDetected: Boolean,
        isLauncherPackage: Boolean,
        isSystemUiPackage: Boolean,
        currentState: EvaluationState,
        currentSecureMode: SecureOverlayManager.Mode?,
        sessionExitConfirmMs: Long = DEFAULT_SESSION_EXIT_CONFIRM_MS,
        isLauncherHomeWorkspace: Boolean = false,
        recentsOverrideMaxGraceMs: Long = RECENTS_OVERRIDE_MAX_GRACE_MS
    ): EvaluationResult {
        // 1. System-wide mode handling (stored setting)
        if (fullSystemProtectionEnabled) {
            if (shouldBypassSystemWideProtection) {
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = true,
                    updatedState = EvaluationState(
                        protectedSessionActive = false,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            } else {
                return EvaluationResult(
                    targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                    shouldTeardownImmediately = false,
                    updatedState = EvaluationState(
                        protectedSessionActive = true,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            }
        }

        // 2. App-specific mode: Check if current foreground is the protected app
        val isProtectedAppForeground = !currentForeground.isNullOrBlank() &&
            currentForeground in protectedPackages &&
            currentForeground != servicePackageName

        if (isProtectedAppForeground) {
            return EvaluationResult(
                targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                shouldTeardownImmediately = false,
                updatedState = EvaluationState(
                    protectedSessionActive = true,
                    sessionExitCandidateSinceElapsedMs = 0L,
                    forceRecentsMaskUntilElapsedMs = 0L,
                    isRecentsOverrideActive = false, // returning to protected app ends recents override seamlessly
                    lastRecentsActiveElapsedMs = 0L
                )
            )
        }

        // 3. User is not in the protected app:
        val isLauncherHost = isLauncherPackage || currentForeground?.contains("launcher") == true
        val isSystemUiHost = isSystemUiPackage || currentForeground == "com.android.systemui"

        val isRecentsSignal = nowElapsedMs < currentState.forceRecentsMaskUntilElapsedMs
        val isRecentsTrigger = isRecentsDetected || isRecentsSignal

        val isExplicitUnprotectedApp = !currentForeground.isNullOrBlank() &&
            currentForeground !in protectedPackages &&
            currentForeground != servicePackageName &&
            !isLauncherHost &&
            !isSystemUiHost &&
            !isImeForeground

        // 4. Handle Recents Override State
        if (currentState.isRecentsOverrideActive) {
            if (isExplicitUnprotectedApp) {
                // Exit from Recents into an unprotected app: immediate deactivation
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = true,
                    updatedState = EvaluationState(
                        protectedSessionActive = false,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            }

            if (isLauncherHomeWorkspace) {
                // Exit from Recents to Home workspace: immediate deactivation
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = true,
                    updatedState = EvaluationState(
                        protectedSessionActive = false,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            }

            if (isRecentsDetected) {
                // User is actively browsing cards in Recents
                return EvaluationResult(
                    targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                    shouldTeardownImmediately = false,
                    updatedState = EvaluationState(
                        protectedSessionActive = currentState.protectedSessionActive,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = currentState.forceRecentsMaskUntilElapsedMs,
                        isRecentsOverrideActive = true,
                        lastRecentsActiveElapsedMs = nowElapsedMs
                    )
                )
            }

            // Recents not currently detected: check bounded transition grace period
            val recentsElapsed = if (currentState.lastRecentsActiveElapsedMs > 0L) {
                nowElapsedMs - currentState.lastRecentsActiveElapsedMs
            } else {
                0L
            }
            val isWithinGracePeriod = isRecentsSignal || recentsElapsed < recentsOverrideMaxGraceMs

            if (isWithinGracePeriod) {
                // Retain state for bounded transition grace period (e.g. transient SystemUI, card fling, or null snapshot)
                return EvaluationResult(
                    targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                    shouldTeardownImmediately = false,
                    updatedState = if (currentState.lastRecentsActiveElapsedMs == 0L) {
                        currentState.copy(lastRecentsActiveElapsedMs = nowElapsedMs)
                    } else {
                        currentState
                    }
                )
            } else {
                // Grace period expired! Stale Recents state must not keep override active indefinitely
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = true,
                    updatedState = EvaluationState(
                        protectedSessionActive = false,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            }
        }

        // 5. Home workspace confirmation when Recents is not detected (e.g. landing on home launcher)
        val isConfirmedHomeWorkspace = isLauncherHomeWorkspace ||
            (isLauncherPackage && !isRecentsDetected)

        if (isConfirmedHomeWorkspace) {
            return EvaluationResult(
                targetMode = null,
                shouldTeardownImmediately = true,
                updatedState = EvaluationState(
                    protectedSessionActive = false,
                    sessionExitCandidateSinceElapsedMs = 0L,
                    forceRecentsMaskUntilElapsedMs = 0L,
                    isRecentsOverrideActive = false,
                    lastRecentsActiveElapsedMs = 0L
                )
            )
        }

        // 6. Recents override activation:
        // In app-specific mode, if protected apps are configured, opening Recents activates the temporary
        // capture-protection override regardless of which app was foreground beforehand, protecting thumbnails.
        val hasProtectedApps = protectedPackages.isNotEmpty()
        if (hasProtectedApps && isRecentsTrigger) {
            return EvaluationResult(
                targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                shouldTeardownImmediately = false,
                updatedState = EvaluationState(
                    protectedSessionActive = currentState.protectedSessionActive,
                    sessionExitCandidateSinceElapsedMs = 0L,
                    forceRecentsMaskUntilElapsedMs = currentState.forceRecentsMaskUntilElapsedMs,
                    isRecentsOverrideActive = true,
                    lastRecentsActiveElapsedMs = nowElapsedMs
                )
            )
        }

        // 6. IME during protected session
        if (isImeForeground && currentState.protectedSessionActive) {
            return EvaluationResult(
                targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                shouldTeardownImmediately = false,
                updatedState = currentState
            )
        }

        // 7. Protected session exit evaluation (direct exit from protected app, not via Recents)
        if (currentState.protectedSessionActive) {
            val isTransientOrInternal = currentForeground.isNullOrBlank() ||
                currentForeground == servicePackageName ||
                (isSystemUiPackage && !isRecentsDetected)

            if (isTransientOrInternal) {
                return EvaluationResult(
                    targetMode = SecureOverlayManager.Mode.TRANSPARENT,
                    shouldTeardownImmediately = false,
                    updatedState = currentState.copy(sessionExitCandidateSinceElapsedMs = 0L)
                )
            }

            if (isConfirmedHomeWorkspace) {
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = true,
                    updatedState = EvaluationState(
                        protectedSessionActive = false,
                        sessionExitCandidateSinceElapsedMs = 0L,
                        forceRecentsMaskUntilElapsedMs = 0L,
                        isRecentsOverrideActive = false,
                        lastRecentsActiveElapsedMs = 0L
                    )
                )
            }

            // Non-protected app foreground (e.g. direct switch via notification or shortcut)
            if (currentState.sessionExitCandidateSinceElapsedMs == 0L) {
                return EvaluationResult(
                    targetMode = null,
                    shouldTeardownImmediately = false,
                    updatedState = currentState.copy(sessionExitCandidateSinceElapsedMs = nowElapsedMs)
                )
            } else {
                val elapsed = nowElapsedMs - currentState.sessionExitCandidateSinceElapsedMs
                if (elapsed < sessionExitConfirmMs) {
                    return EvaluationResult(
                        targetMode = null,
                        shouldTeardownImmediately = false,
                        updatedState = currentState
                    )
                } else {
                    return EvaluationResult(
                        targetMode = null,
                        shouldTeardownImmediately = true,
                        updatedState = EvaluationState(
                            protectedSessionActive = false,
                            sessionExitCandidateSinceElapsedMs = 0L,
                            forceRecentsMaskUntilElapsedMs = 0L,
                            isRecentsOverrideActive = false,
                            lastRecentsActiveElapsedMs = 0L
                        )
                    )
                }
            }
        }

        // 8. No session active
        return EvaluationResult(
            targetMode = null,
            shouldTeardownImmediately = isLauncherPackage || isConfirmedHomeWorkspace || !currentState.protectedSessionActive,
            updatedState = EvaluationState(
                protectedSessionActive = false,
                sessionExitCandidateSinceElapsedMs = 0L,
                forceRecentsMaskUntilElapsedMs = 0L,
                isRecentsOverrideActive = false,
                lastRecentsActiveElapsedMs = 0L
            )
        )
    }
}
