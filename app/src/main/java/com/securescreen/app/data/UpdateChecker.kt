package com.securescreen.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val sourceUrl: String
)

object UpdateChecker {

    fun check(context: Context): UpdateInfo? {
        val currentVersion = currentVersion(context)
        return fetchFromFdroid(currentVersion) ?: fetchFromGitHub(currentVersion)
    }

    private fun fetchFromFdroid(currentVersion: AppVersion): UpdateInfo? {
        val json = getJson("https://f-droid.org/api/v1/packages/com.securescreen.app") ?: return null
        val versionCode = json.optLong("suggestedVersionCode", 0L)
        val versionName = json.optString("suggestedVersionName")
        if (versionCode <= currentVersion.versionCode || versionName.isBlank()) return null
        return UpdateInfo(versionName, versionCode, "https://f-droid.org/packages/com.securescreen.app/")
    }

    private fun fetchFromGitHub(currentVersion: AppVersion): UpdateInfo? {
        val json = getJson("https://api.github.com/repos/adikul1023/SecureScreen/releases/latest") ?: return null
        val versionName = json.optString("tag_name").removePrefix("v")
        if (!isNewerVersion(versionName, currentVersion.versionName)) return null
        val versionCode = versionName.split('.').fold(0L) { result, part ->
            result * 1000 + (part.toLongOrNull() ?: 0L)
        }
        return UpdateInfo(versionName, versionCode, json.optString("html_url"))
    }

    private fun getJson(url: String): JSONObject? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SecureScreen")
            }
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return@runCatching null
            }
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            result
        }.getOrNull()
    }

    private fun currentVersion(context: Context): AppVersion {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        return AppVersion(packageInfo.versionName.orEmpty(), versionCode)
    }

    private fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateParts = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(candidateParts.size, currentParts.size)
        return (0 until size).firstNotNullOfOrNull { index ->
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            (candidatePart - currentPart).takeIf { it != 0 }
        }?.let { it > 0 } == true
    }

    private data class AppVersion(val versionName: String, val versionCode: Long)
}