package com.securescreen.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.service.ForegroundService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val shouldHandle = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!shouldHandle) return

        val repository = AppRepository(context)
        if (!PermissionUtils.hasNotificationPermission(context)) return

        if (repository.isProtectionEnabled() && repository.isAutoStartOnBootEnabled()) {
            com.securescreen.app.service.WatchdogRecoveryManager.resetBackoff(context)
            com.securescreen.app.service.WatchdogRecoveryManager.recordRecoveryAttempt(
                context,
                if (action == Intent.ACTION_BOOT_COMPLETED) "Device boot completed" else "App package updated"
            )
            ForegroundService.start(context)
            ForegroundService.setProtectionEnabled(context, true)
            ForegroundService.scheduleWatchdog(context)
        }
    }
}
