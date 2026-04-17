package com.securescreen.app.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

class AppRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInstalledLaunchableApps(): List<AppInfo> {
        val packageManager = context.packageManager

        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launcherPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        }
            .map { it.activityInfo.packageName }
            .toSet()

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
        }

        data class Candidate(
            val appInfo: AppInfo,
            val isSystemApp: Boolean
        )

        return installedApps
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter {
                // Include launchable apps even on strict package-visibility devices.
                it.packageName in launcherPackages ||
                    packageManager.getLaunchIntentForPackage(it.packageName) != null
            }
            .mapNotNull { app ->
                runCatching {
                    val appName = packageManager.getApplicationLabel(app).toString()
                    val icon = packageManager.getApplicationIcon(app)
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                    Candidate(
                        appInfo = AppInfo(
                            appName = appName,
                            packageName = app.packageName,
                            icon = icon
                        ),
                        isSystemApp = isSystem
                    )
                }.getOrNull()
            }
            .distinctBy { it.appInfo.packageName }
            .sortedWith(
                compareBy<Candidate> { it.isSystemApp }
                    .thenBy { it.appInfo.appName.lowercase() }
            )
            .map { it.appInfo }
            .toList()
    }

    fun getProtectedPackages(): Set<String> {
        return prefs.getStringSet(KEY_PROTECTED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setPackageProtected(packageName: String, isProtected: Boolean) {
        val updatedSet = getProtectedPackages().toMutableSet()
        if (isProtected) {
            updatedSet.add(packageName)
        } else {
            updatedSet.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_PROTECTED_PACKAGES, updatedSet).apply()
    }

    fun isServiceEnabled(): Boolean = prefs.getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun isProtectionEnabled(): Boolean = prefs.getBoolean(KEY_PROTECTION_ENABLED, false)

    fun setProtectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    fun isAutoStartOnBootEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun isWatermarkEnabled(): Boolean = prefs.getBoolean(KEY_WATERMARK_ENABLED, false)

    fun setWatermarkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATERMARK_ENABLED, enabled).apply()
    }

    fun getWatermarkOpacityPercent(): Int = prefs.getInt(KEY_WATERMARK_OPACITY, 45)

    fun setWatermarkOpacityPercent(opacityPercent: Int) {
        val sanitized = opacityPercent.coerceIn(10, 100)
        prefs.edit().putInt(KEY_WATERMARK_OPACITY, sanitized).apply()
    }

    fun isAggressiveModeEnabled(): Boolean = prefs.getBoolean(KEY_AGGRESSIVE_MODE, false)

    fun setAggressiveModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGGRESSIVE_MODE, enabled).apply()
    }

    fun getSessionId(): String {
        val existing = prefs.getString(KEY_SESSION_ID, null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_SESSION_ID, newId).apply()
        return newId
    }

    companion object {
        private const val PREFS_NAME = "secure_screen_prefs"
        private const val KEY_PROTECTED_PACKAGES = "protected_packages"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
        private const val KEY_WATERMARK_ENABLED = "watermark_enabled"
        private const val KEY_WATERMARK_OPACITY = "watermark_opacity"
        private const val KEY_AGGRESSIVE_MODE = "aggressive_mode"
        private const val KEY_SESSION_ID = "session_id"
    }
}
