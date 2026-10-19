package com.securescreen.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.securescreen.app.data.AppRepository
import com.securescreen.app.service.ForegroundService

class NotificationDismissedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val repository = AppRepository(context)
        if (!repository.isProtectionEnabled()) return

        ForegroundService.start(context)
        ForegroundService.setProtectionEnabled(context, true)
    }
}