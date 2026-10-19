package com.securescreen.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.securescreen.app.R
import java.util.concurrent.atomic.AtomicBoolean

object UpdateNotificationManager {

    private const val CHANNEL_ID = "securescreen_updates"
    private const val NOTIFICATION_ID = 4513
    private val checkInProgress = AtomicBoolean(false)

    fun check(context: Context) {
        if (!PermissionUtils.hasNotificationPermission(context) ||
            !checkInProgress.compareAndSet(false, true)
        ) {
            return
        }

        val appContext = context.applicationContext
        Thread {
            val update = runCatching { UpdateChecker.check(appContext) }.getOrNull()
            try {
                showOrClearNotification(appContext, update)
            } finally {
                checkInProgress.set(false)
            }
        }.start()
    }

    private fun showOrClearNotification(context: Context, update: UpdateInfo?) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager, context)

        if (update == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val updateIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(update.sourceUrl) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val updateText = context.getString(R.string.update_notification_text, update.versionName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_security)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(updateText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(updateText))
            .setContentIntent(updateIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun createChannel(manager: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}