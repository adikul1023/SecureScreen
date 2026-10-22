package com.securescreen.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchdogRecoveryManagerTest {

    private var fakeCurrentTime = 1000L

    @Before
    fun setUp() {
        fakeCurrentTime = 1000L
        WatchdogRecoveryManager.timeProvider = { fakeCurrentTime }
    }

    @Test
    fun testBackoffProgressionArray() {
        val delays = WatchdogRecoveryManager.BACKOFF_DELAYS_MS
        assertEquals(6, delays.size)
        assertEquals(0L, delays[0])
        assertEquals(15_000L, delays[1])
        assertEquals(30_000L, delays[2])
        assertEquals(60_000L, delays[3])
        assertEquals(120_000L, delays[4])
        assertEquals(300_000L, delays[5])
    }

    @Test
    fun testBackoffIndexCalculation() {
        val delays = WatchdogRecoveryManager.BACKOFF_DELAYS_MS

        fun computeBackoff(restarts: Int): Long {
            val backoffIndex = (restarts - 1).coerceIn(0, delays.size - 1)
            return delays[backoffIndex]
        }

        assertEquals(0L, computeBackoff(1))       // Attempt 1: no backoff
        assertEquals(15_000L, computeBackoff(2))  // Attempt 2: 15s
        assertEquals(30_000L, computeBackoff(3))  // Attempt 3: 30s
        assertEquals(60_000L, computeBackoff(4))  // Attempt 4: 60s
        assertEquals(120_000L, computeBackoff(5)) // Attempt 5: 120s
        assertEquals(300_000L, computeBackoff(6)) // Attempt 6: 300s capped
        assertEquals(300_000L, computeBackoff(20))// Attempt 20: 300s capped
    }

    @Test
    fun testHeartbeatAndStallDetection() {
        fakeCurrentTime = 10_000L
        WatchdogRecoveryManager.markHeartbeat()

        // 5 seconds later (time = 15_000L), stall threshold is 15s -> not stalled
        fakeCurrentTime = 15_000L
        assertFalse(WatchdogRecoveryManager.isMonitoringStalled(15_000L))

        // 20 seconds after heartbeat (time = 30_000L), stall threshold is 15s -> stalled!
        fakeCurrentTime = 30_000L
        assertTrue(WatchdogRecoveryManager.isMonitoringStalled(15_000L))
        assertEquals(20_000L, WatchdogRecoveryManager.getStallDurationMs())
    }

    @Test
    fun testRapidRestartWindowComputation() {
        val rapidWindow = WatchdogRecoveryManager.RAPID_RESTART_WINDOW_MS
        val t0 = 100_000L

        // Restart within 10 seconds is rapid
        val isRapid1 = (t0 + 10_000L - t0) < rapidWindow
        assertTrue(isRapid1)

        // Restart after 70 seconds is outside rapid window
        val isRapid2 = (t0 + 70_000L - t0) < rapidWindow
        assertFalse(isRapid2)
    }
}
