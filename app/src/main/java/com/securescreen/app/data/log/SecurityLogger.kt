package com.securescreen.app.data.log

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

object SecurityLogger {

    private const val TAG = "SecurityLogger"
    const val ACTION_SECURITY_LOGS_UPDATED = "com.securescreen.app.action.SECURITY_LOGS_UPDATED"

    private val executor = Executors.newSingleThreadExecutor()

    fun log(context: Context, event: SecurityEvent) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val dbHelper = SecurityLogDatabaseHelper(appContext)
                dbHelper.insertEvent(event)

                val intent = Intent(ACTION_SECURITY_LOGS_UPDATED).apply {
                    setPackage(appContext.packageName)
                }
                appContext.sendBroadcast(intent)
            }.onFailure {
                Log.e(TAG, "Failed to record security event: ${event.title}", it)
            }
        }
    }

    fun logProtectionEnabled(context: Context, source: String) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PROTECTION_ENABLED,
                severity = SecurityEventSeverity.INFO,
                title = "Protection Enabled",
                details = "Protection activated ($source)"
            )
        )
    }

    fun logProtectionDisabled(context: Context, source: String) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PROTECTION_DISABLED,
                severity = SecurityEventSeverity.INFO,
                title = "Protection Disabled",
                details = "Protection deactivated ($source)"
            )
        )
    }

    fun logAppEntered(context: Context, packageName: String, appName: String? = null) {
        val displayName = if (!appName.isNullOrBlank()) "$appName ($packageName)" else packageName
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.APP_ENTERED,
                severity = SecurityEventSeverity.INFO,
                title = "Protected App Entered",
                details = "Active foreground: $displayName",
                packageName = packageName
            )
        )
    }

    fun logAppExited(context: Context, packageName: String, appName: String? = null) {
        val displayName = if (!appName.isNullOrBlank()) "$appName ($packageName)" else packageName
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.APP_EXITED,
                severity = SecurityEventSeverity.INFO,
                title = "Protected App Exited",
                details = "Left foreground: $displayName",
                packageName = packageName
            )
        )
    }

    fun logPermissionRevoked(context: Context, permissionName: String, details: String? = null) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PERMISSION_REVOKED,
                severity = SecurityEventSeverity.ERROR,
                title = "Permission Revoked",
                details = details ?: "Required permission revoked: $permissionName"
            )
        )
    }

    fun logPermissionRestored(context: Context, permissionName: String) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PERMISSION_RESTORED,
                severity = SecurityEventSeverity.INFO,
                title = "Permission Restored",
                details = "Permission re-granted: $permissionName"
            )
        )
    }

    fun logServiceRestarted(
        context: Context,
        trigger: String,
        severity: SecurityEventSeverity = SecurityEventSeverity.INFO
    ) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.SERVICE_RESTARTED,
                severity = severity,
                title = "Service Restarted / Recovered",
                details = "Trigger: $trigger"
            )
        )
    }

    fun logProtectionUnavailable(context: Context, reason: String) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PROTECTION_UNAVAILABLE,
                severity = SecurityEventSeverity.ERROR,
                title = "Protection Unavailable",
                details = reason
            )
        )
    }

    fun logProtectionRestored(context: Context, reason: String) {
        log(
            context,
            SecurityEvent(
                eventType = SecurityEventType.PROTECTION_RESTORED,
                severity = SecurityEventSeverity.INFO,
                title = "Protection Restored",
                details = reason
            )
        )
    }
}
