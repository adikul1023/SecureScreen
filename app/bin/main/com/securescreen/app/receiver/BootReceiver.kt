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

        if (repository.isServiceEnabled() && repository.isAutoStartOnBootEnabled()) {
            ForegroundService.start(context)
            ForegroundService.setProtectionEnabled(context, repository.isProtectionEnabled())
        }
    }
}
