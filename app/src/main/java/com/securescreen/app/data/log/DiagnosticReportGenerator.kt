package com.securescreen.app.data.log

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.service.SecureAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticReportGenerator {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher"
    )

    fun redactPackage(
        rawPkg: String?,
        selfPackage: String = "com.securescreen.app",
        packageMap: MutableMap<String, String> = mutableMapOf()
    ): String {
        if (rawPkg.isNullOrBlank()) return ""
        if (rawPkg == selfPackage) return "SecureScreen"
        if (rawPkg in SYSTEM_PACKAGES) return rawPkg

        return packageMap.getOrPut(rawPkg) {
            val count = packageMap.size + 1
            "[Protected App #$count]"
        }
    }

    fun redactDetails(
        details: String?,
        selfPackage: String = "com.securescreen.app",
        packageMap: MutableMap<String, String> = mutableMapOf()
    ): String {
        if (details.isNullOrBlank()) return "N/A"
        var sanitized: String = details

        for ((rawPkg, anon) in packageMap) {
            sanitized = sanitized.replace(rawPkg, anon)
        }

        val packageRegex = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+""")
        sanitized = packageRegex.replace(sanitized) { matchResult ->
            val pkg = matchResult.value
            if (pkg == selfPackage || pkg in SYSTEM_PACKAGES) {
                pkg
            } else {
                redactPackage(pkg, selfPackage, packageMap)
            }
        }

        return sanitized
    }

    fun generateRedactedReport(
        context: Context,
        events: List<SecurityEvent>
    ): String {
        val appContext = context.applicationContext
        val repository = runCatching { AppRepository(appContext) }.getOrNull()
        val packageMap = mutableMapOf<String, String>()
        val selfPkg = appContext.packageName ?: "com.securescreen.app"

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }
        }.getOrNull()

        val versionName = packageInfo?.versionName ?: "Unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: -1L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: -1L
        }

        val usageGranted = runCatching { PermissionUtils.hasUsageStatsPermission(appContext) }.getOrDefault(false)
        val overlayGranted = runCatching { PermissionUtils.canDrawOverlays(appContext) }.getOrDefault(false)
        val accessibilityGranted = runCatching {
            PermissionUtils.isAccessibilityServiceEnabled(appContext, SecureAccessibilityService::class.java)
        }.getOrDefault(false)
        val notificationGranted = runCatching { PermissionUtils.hasNotificationPermission(appContext) }.getOrDefault(false)
        val batteryIgnored = runCatching { PermissionUtils.isIgnoringBatteryOptimizations(appContext) }.getOrDefault(false)
        val exactAlarmAllowed = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                true
            } else {
                appContext.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
            }
        }.getOrDefault(false)

        val serviceEnabled = repository?.isServiceEnabled() ?: false
        val protectionEnabled = repository?.isProtectionEnabled() ?: false
        val aggressiveMode = repository?.isAggressiveModeEnabled() ?: false
        val watermarkEnabled = repository?.isWatermarkEnabled() ?: false
        val watermarkOpacity = repository?.getWatermarkOpacityPercent() ?: 45
        val autoStartBoot = repository?.isAutoStartOnBootEnabled() ?: false
        val protectedCount = repository?.getProtectedPackages()?.size ?: 0

        val errorCount = events.count { it.severity == SecurityEventSeverity.ERROR }
        val warningCount = events.count { it.severity == SecurityEventSeverity.WARNING }
        val infoCount = events.count { it.severity == SecurityEventSeverity.INFO }

        val sb = StringBuilder()
        sb.appendLine("================================================================")
        sb.appendLine("       SECURESCREEN DIAGNOSTIC REPORT (REDACTED LOCAL LOG)       ")
        sb.appendLine("================================================================")
        sb.appendLine("Generated: ${DATE_FORMAT.format(Date())} (${TimeZone.getDefault().id})")
        sb.appendLine("App Version: $versionName ($versionCode)")
        sb.appendLine("OS Release: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device: ${Build.MANUFACTURER} / ${Build.MODEL}")
        sb.appendLine("Architecture: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        sb.appendLine()
        sb.appendLine("------------------- PRIVACY & TELEMETRY ------------------------")
        sb.appendLine("Local-Only: YES (No data is transmitted to external servers)")
        sb.appendLine("Redaction: Sensitive user app packages are anonymized as [Protected App #N]")
        sb.appendLine()
        sb.appendLine("------------------- CURRENT APP CONFIGURATION -------------------")
        sb.appendLine("Service Enabled: $serviceEnabled")
        sb.appendLine("Protection Enabled: $protectionEnabled")
        sb.appendLine("Protection Mode: ${if (aggressiveMode) "System-Wide" else "App-Specific ($protectedCount apps protected)"}")
        sb.appendLine("Watermark Overlay: ${if (watermarkEnabled) "Enabled ($watermarkOpacity%)" else "Disabled"}")
        sb.appendLine("Auto-Start on Boot: $autoStartBoot")
        sb.appendLine()
        sb.appendLine("------------------- PERMISSIONS STATUS -------------------------")
        sb.appendLine("Usage Access:              ${if (usageGranted) "GRANTED" else "REVOKED / MISSING"}")
        sb.appendLine("Overlay Permission:        ${if (overlayGranted) "GRANTED" else "NOT GRANTED"}")
        sb.appendLine("Accessibility Service:     ${if (accessibilityGranted) "ENABLED" else "DISABLED"}")
        sb.appendLine("Notification Permission:   ${if (notificationGranted) "GRANTED" else "NOT GRANTED"}")
        sb.appendLine("Battery Optimization:      ${if (batteryIgnored) "EXEMPTED (Ignored)" else "RESTRICTED"}")
        sb.appendLine("Exact Alarm Permission:    ${if (exactAlarmAllowed) "ALLOWED" else "RESTRICTED"}")
        sb.appendLine()
        sb.appendLine("------------------- EVENT TIMELINE SUMMARY ---------------------")
        sb.appendLine("Total Events in Log: ${events.size}")
        sb.appendLine("Errors: $errorCount | Warnings: $warningCount | Info: $infoCount")
        sb.appendLine()
        sb.appendLine("------------------- EVENT LOG TIMELINE -------------------------")

        if (events.isEmpty()) {
            sb.appendLine("No events recorded.")
        } else {
            // First pass to register any raw package names into mapping
            for (event in events) {
                if (!event.packageName.isNullOrBlank()) {
                    redactPackage(event.packageName, selfPkg, packageMap)
                }
            }

            for (event in events) {
                val timeStr = DATE_FORMAT.format(Date(event.timestamp))
                val severityTag = String.format(Locale.US, "%-7s", "[${event.severity.name}]")
                val sanitizedDetails = redactDetails(event.details, selfPkg, packageMap)
                val sanitizedPkg = if (!event.packageName.isNullOrBlank()) {
                    " | Target: ${redactPackage(event.packageName, selfPkg, packageMap)}"
                } else {
                    ""
                }

                sb.appendLine("$timeStr $severityTag ${event.title}")
                sb.appendLine("  Details: $sanitizedDetails$sanitizedPkg")
                sb.appendLine()
            }
        }

        sb.appendLine("================================================================")
        sb.appendLine("                     END OF DIAGNOSTIC REPORT                   ")
        sb.appendLine("================================================================")

        return sb.toString()
    }
}
