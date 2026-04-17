package com.securescreen.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Locale

class ForegroundAppDetector(context: Context) {

    data class ForegroundSnapshot(
        val packageName: String?,
        val className: String?,
        val timestamp: Long
    )

    private val usageStatsManager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun getForegroundSnapshot(): ForegroundSnapshot {
        val now = System.currentTimeMillis()
        val beginTime = now - LOOKBACK_WINDOW_MS
        val events = usageStatsManager.queryEvents(beginTime, now)

        var mostRecentPackage: String? = null
        var mostRecentClass: String? = null
        var mostRecentTime = 0L
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)

            if (isForegroundEvent && event.timeStamp > mostRecentTime) {
                mostRecentTime = event.timeStamp
                mostRecentPackage = event.packageName
                mostRecentClass = event.className
            }
        }

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            beginTime,
            now
        )

        val mostRecentUsageStat = usageStats.maxByOrNull(UsageStats::getLastTimeUsed)

        // Some devices deliver UsageEvents with delay. Prefer whichever source is newer.
        if (mostRecentUsageStat != null && mostRecentUsageStat.lastTimeUsed > mostRecentTime) {
            return ForegroundSnapshot(
                packageName = mostRecentUsageStat.packageName,
                className = null,
                timestamp = mostRecentUsageStat.lastTimeUsed
            )
        }

        if (!mostRecentPackage.isNullOrBlank()) {
            return ForegroundSnapshot(
                packageName = mostRecentPackage,
                className = mostRecentClass,
                timestamp = mostRecentTime
            )
        }

        return ForegroundSnapshot(
            packageName = mostRecentUsageStat?.packageName,
            className = null,
            timestamp = mostRecentUsageStat?.lastTimeUsed ?: 0L
        )
    }

    fun getForegroundPackageName(): String? {
        return getForegroundSnapshot().packageName
    }

    fun isRecentsVisible(foregroundPackage: String?, foregroundClass: String?): Boolean {
        if (foregroundPackage.isNullOrBlank()) return false

        val normalizedClass = foregroundClass?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedClass.contains("recent") || normalizedClass.contains("overview")) {
            return true
        }

        if (foregroundPackage == SYSTEM_UI_PACKAGE) {
            return true
        }

        return foregroundPackage in RECENTS_HOST_PACKAGES &&
            normalizedClass.contains("quickstep")
    }

    companion object {
        private const val LOOKBACK_WINDOW_MS = 5_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        private val RECENTS_HOST_PACKAGES = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.vivo.launcher",
            "com.oneplus.launcher"
        )
    }
}
