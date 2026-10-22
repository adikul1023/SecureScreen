package com.securescreen.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securescreen.app.data.AppRepository
import com.securescreen.app.service.ForegroundService
import com.securescreen.app.service.WatchdogRecoveryManager

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val repository = AppRepository(context)
        if (!repository.isProtectionEnabled()) {
            ForegroundService.cancelWatchdog(context)
            return
        }

        val isStalled = WatchdogRecoveryManager.isMonitoringStalled()
        val isServiceMarkedEnabled = repository.isServiceEnabled()

        if (isStalled || !isServiceMarkedEnabled) {
            val stallSec = WatchdogRecoveryManager.getStallDurationMs() / 1000
            val reason = if (!isServiceMarkedEnabled) {
                "Service was terminated or not running"
            } else {
                "Monitoring loop stalled ($stallSec seconds inactive)"
            }

            WatchdogRecoveryManager.recordRecoveryAttempt(context, reason)
            ForegroundService.start(context)
            ForegroundService.setProtectionEnabled(context, true)
        } else {
            // Service is alive and healthy
            WatchdogRecoveryManager.checkAndRecordStableRun(context)
        }

        // Always schedule next watchdog using exponential backoff interval if applicable
        ForegroundService.scheduleWatchdog(context)
    }
}
