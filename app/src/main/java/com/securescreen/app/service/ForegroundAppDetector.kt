package com.securescreen.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

class ForegroundAppDetector(
    private val context: Context? = null,
    private val usageStatsManagerOverride: UsageStatsManager? = null
) {

    data class ForegroundSnapshot(
        val packageName: String?,
        val className: String?,
        val timestamp: Long
    )

    private val usageStatsManager: UsageStatsManager? by lazy {
        usageStatsManagerOverride ?: runCatching {
            context?.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        }.getOrNull()
    }

    private val launcherPackages = mutableSetOf<String>()

    @Volatile
    private var lastConfirmedSnapshot = ForegroundSnapshot(
        packageName = null,
        className = null,
        timestamp = 0L
    )

    @Volatile
    private var accessibilitySnapshot: ForegroundSnapshot? = null

    init {
        updateLauncherPackages()
    }

    fun updateFromAccessibility(packageName: String?, className: String?, timestampMs: Long = System.currentTimeMillis()) {
        if (!packageName.isNullOrBlank()) {
            val snapshot = ForegroundSnapshot(
                packageName = packageName,
                className = className,
                timestamp = timestampMs
            )
            accessibilitySnapshot = snapshot
            lastConfirmedSnapshot = snapshot
        }
    }

    fun updateLauncherPackages() {
        val ctx = context ?: return
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            val packages = resolveInfos.mapNotNull { it.activityInfo?.packageName }
            synchronized(launcherPackages) {
                launcherPackages.clear()
                launcherPackages.addAll(packages)
                launcherPackages.addAll(RECENTS_HOST_PACKAGES)
            }
        }
    }

    fun isLauncherPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        synchronized(launcherPackages) {
            return packageName in launcherPackages || packageName in RECENTS_HOST_PACKAGES
        }
    }

    fun isSystemUiPackage(packageName: String?): Boolean {
        return packageName == SYSTEM_UI_PACKAGE
    }

    fun resetLastConfirmedSnapshot() {
        lastConfirmedSnapshot = ForegroundSnapshot(null, null, 0L)
        accessibilitySnapshot = null
    }

    fun getForegroundSnapshot(): ForegroundSnapshot {
        val now = System.currentTimeMillis()
        val a11y = accessibilitySnapshot
        // If an accessibility event occurred within the last 5 seconds, use it as fast real-time ground truth.
        // AccessibilityService delivers window transitions in <5ms, whereas UsageStatsManager buffers events.
        if (a11y != null && now - a11y.timestamp < ACCESSIBILITY_FRESHNESS_WINDOW_MS) {
            lastConfirmedSnapshot = a11y
            return a11y
        }

        val manager = usageStatsManager ?: return a11y ?: lastConfirmedSnapshot
        return runCatching {
            val beginTime = now - LOOKBACK_WINDOW_MS
            val events = manager.queryEvents(beginTime, now)

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

                if (isForegroundEvent && event.timeStamp >= mostRecentTime) {
                    mostRecentTime = event.timeStamp
                    mostRecentPackage = event.packageName
                    mostRecentClass = event.className
                }
            }

            if (a11y != null && a11y.timestamp >= mostRecentTime) {
                lastConfirmedSnapshot = a11y
                return@runCatching a11y
            }

            if (!mostRecentPackage.isNullOrBlank()) {
                val snapshot = ForegroundSnapshot(
                    packageName = mostRecentPackage,
                    className = mostRecentClass,
                    timestamp = mostRecentTime
                )
                lastConfirmedSnapshot = snapshot
                return@runCatching snapshot
            }

            if (a11y != null) {
                return@runCatching a11y
            }

            // If queryEvents returned no new foreground transition in the lookback window,
            // but we already have a confirmed foreground app from a prior poll, keep it.
            if (!lastConfirmedSnapshot.packageName.isNullOrBlank()) {
                return@runCatching lastConfirmedSnapshot
            }

            // Fallback for initial startup only when lastConfirmedSnapshot is empty:
            val usageStats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                beginTime,
                now
            )
            val mostRecentUsageStat = usageStats?.maxByOrNull(UsageStats::getLastTimeUsed)
            if (mostRecentUsageStat != null && mostRecentUsageStat.lastTimeUsed > 0L) {
                val snapshot = ForegroundSnapshot(
                    packageName = mostRecentUsageStat.packageName,
                    className = null,
                    timestamp = mostRecentUsageStat.lastTimeUsed
                )
                lastConfirmedSnapshot = snapshot
                return@runCatching snapshot
            }

            lastConfirmedSnapshot
        }.getOrElse {
            a11y ?: lastConfirmedSnapshot
        }
    }

    fun getForegroundPackageName(): String? {
        return getForegroundSnapshot().packageName
    }

    fun isRecentsVisible(foregroundPackage: String?, foregroundClass: String?): Boolean {
        if (foregroundPackage.isNullOrBlank()) return false

        val normalizedClass = foregroundClass?.lowercase(Locale.ROOT).orEmpty()
        val normalizedPkg = foregroundPackage.lowercase(Locale.ROOT)

        // 1. Any class explicitly named with recents / overview / quickstep / taskswitcher / taskview
        if (normalizedClass.contains("recent") ||
            normalizedClass.contains("overview") ||
            normalizedClass.contains("quickstep") ||
            normalizedClass.contains("taskswitcher") ||
            normalizedClass.contains("taskview")
        ) {
            return true
        }

        // 2. Known launcher/recents host packages
        if (isLauncherPackage(normalizedPkg) || normalizedPkg in RECENTS_HOST_PACKAGES) {
            if (normalizedClass.contains("quickstep") ||
                normalizedClass.contains("recent") ||
                normalizedClass.contains("overview") ||
                normalizedClass.contains("recentsactivity") ||
                normalizedClass.contains("taskswitcher") ||
                normalizedClass.contains("taskview")
            ) {
                return true
            }
        }

        // 3. System UI: Only recents if class explicitly indicates recents/overview/quickstep/taskswitcher
        if (normalizedPkg == SYSTEM_UI_PACKAGE) {
            return normalizedClass.contains("recent") ||
                normalizedClass.contains("overview") ||
                normalizedClass.contains("quickstep") ||
                normalizedClass.contains("taskswitcher") ||
                normalizedClass.contains("taskview")
        }

        return false
    }

    fun isLauncherHomeWorkspace(foregroundPackage: String?, foregroundClass: String?): Boolean {
        if (foregroundPackage.isNullOrBlank()) return false
        val normalizedPkg = foregroundPackage.lowercase(Locale.ROOT)
        if (!isLauncherPackage(normalizedPkg)) return false

        // If class indicates recents, it is not the home workspace
        if (isRecentsVisible(foregroundPackage, foregroundClass)) return false

        val normalizedClass = foregroundClass?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedClass.isBlank()) {
            // When class name is omitted/null on a confirmed launcher package and it is not recents,
            // it represents the default launcher desktop.
            return true
        }

        return normalizedClass.contains("launcher") ||
            normalizedClass.contains("home") ||
            normalizedClass.contains("workspace") ||
            normalizedClass.contains("quickstep") ||
            normalizedClass.contains("desktop") ||
            normalizedClass.contains("view") ||
            normalizedClass.contains("layout")
    }

    companion object {
        private const val LOOKBACK_WINDOW_MS = 30_000L
        private const val ACCESSIBILITY_FRESHNESS_WINDOW_MS = 5_000L
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

