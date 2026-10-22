package com.securescreen.app.service

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.securescreen.app.data.log.SecurityEventSeverity
import com.securescreen.app.data.log.SecurityLogger
import java.util.concurrent.atomic.AtomicLong

object WatchdogRecoveryManager {

    private const val PREFS_NAME = "watchdog_recovery_prefs"
    private const val KEY_CONSECUTIVE_RESTARTS = "consecutive_restarts"
    private const val KEY_LAST_RECOVERY_TIME_MS = "last_recovery_time_ms"

    const val NORMAL_WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    const val STALL_THRESHOLD_MS = 15_000L // 15 seconds without tick
    const val RAPID_RESTART_WINDOW_MS = 60_000L // 1 minute window to detect restart loops
    const val STABLE_RUN_THRESHOLD_MS = 90_000L // 90 seconds of stable run to reset backoff

    // Exponential backoff values
    val BACKOFF_DELAYS_MS = longArrayOf(
        0L,             // Attempt 1: immediate / standard
        15_000L,        // Attempt 2: 15s
        30_000L,        // Attempt 3: 30s
        60_000L,        // Attempt 4: 60s
        120_000L,       // Attempt 5: 2 minutes
        300_000L        // Attempt 6+: 5 minutes cap
    )

    var timeProvider: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
    }

    private val lastHeartbeatElapsedMs = AtomicLong(0L)
    private val serviceStartElapsedMs = AtomicLong(0L)
    private var isStableRunLogged = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun markHeartbeat() {
        lastHeartbeatElapsedMs.set(timeProvider())
    }

    fun markServiceStarted() {
        val now = timeProvider()
        serviceStartElapsedMs.set(now)
        lastHeartbeatElapsedMs.set(now)
        isStableRunLogged = false
    }

    fun isMonitoringStalled(stallThresholdMs: Long = STALL_THRESHOLD_MS): Boolean {
        val now = timeProvider()
        val elapsedSinceHeartbeat = now - lastHeartbeatElapsedMs.get()
        return elapsedSinceHeartbeat > stallThresholdMs
    }

    fun getStallDurationMs(): Long {
        return (timeProvider() - lastHeartbeatElapsedMs.get()).coerceAtLeast(0L)
    }

    /**
     * Checks if the service has run healthily for long enough to be considered stable,
     * and resets the restart loop backoff counter if so.
     */
    fun checkAndRecordStableRun(context: Context) {
        val runningDuration = timeProvider() - serviceStartElapsedMs.get()
        if (runningDuration >= STABLE_RUN_THRESHOLD_MS && !isStableRunLogged) {
            val prefs = getPrefs(context)
            val currentRestarts = prefs.getInt(KEY_CONSECUTIVE_RESTARTS, 0)
            if (currentRestarts > 0) {
                prefs.edit().putInt(KEY_CONSECUTIVE_RESTARTS, 0).apply()
                SecurityLogger.logServiceRestarted(
                    context,
                    "Service stabilized after recovery (running continuously for ${runningDuration / 1000}s). Restart backoff reset to 0.",
                    SecurityEventSeverity.INFO
                )
            }
            isStableRunLogged = true
        }
    }

    /**
     * Records a restart or recovery event, increments the consecutive restart counter
     * if occurring within the rapid restart window, and returns the computed backoff delay in ms.
     */
    fun recordRecoveryAttempt(context: Context, reason: String): Long {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val lastRecoveryTime = prefs.getLong(KEY_LAST_RECOVERY_TIME_MS, 0L)
        val prevRestarts = prefs.getInt(KEY_CONSECUTIVE_RESTARTS, 0)

        val isRapid = (now - lastRecoveryTime) < RAPID_RESTART_WINDOW_MS
        val newRestarts = if (isRapid) prevRestarts + 1 else 1

        prefs.edit()
            .putInt(KEY_CONSECUTIVE_RESTARTS, newRestarts)
            .putLong(KEY_LAST_RECOVERY_TIME_MS, now)
            .apply()

        val backoffIndex = (newRestarts - 1).coerceIn(0, BACKOFF_DELAYS_MS.size - 1)
        val backoffDelayMs = BACKOFF_DELAYS_MS[backoffIndex]

        val severity = when {
            newRestarts >= 4 -> SecurityEventSeverity.ERROR
            newRestarts >= 2 -> SecurityEventSeverity.WARNING
            else -> SecurityEventSeverity.INFO
        }

        val backoffText = if (backoffDelayMs > 0) " (Backoff: ${backoffDelayMs / 1000}s applied)" else ""
        SecurityLogger.logServiceRestarted(
            context,
            "Recovery triggered: $reason | Attempt #$newRestarts$backoffText",
            severity
        )

        return backoffDelayMs
    }

    fun getNextWatchdogInterval(context: Context): Long {
        val prefs = getPrefs(context)
        val restarts = prefs.getInt(KEY_CONSECUTIVE_RESTARTS, 0)
        if (restarts <= 1) return NORMAL_WATCHDOG_INTERVAL_MS

        val backoffIndex = (restarts - 1).coerceIn(0, BACKOFF_DELAYS_MS.size - 1)
        val backoffMs = BACKOFF_DELAYS_MS[backoffIndex]
        return if (backoffMs > 0) backoffMs else NORMAL_WATCHDOG_INTERVAL_MS
    }

    fun getConsecutiveRestarts(context: Context): Int {
        return getPrefs(context).getInt(KEY_CONSECUTIVE_RESTARTS, 0)
    }

    fun resetBackoff(context: Context) {
        getPrefs(context).edit().putInt(KEY_CONSECUTIVE_RESTARTS, 0).apply()
        isStableRunLogged = true
    }
}
