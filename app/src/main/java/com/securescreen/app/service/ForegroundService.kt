package com.securescreen.app.service

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.securescreen.app.R
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.receiver.NotificationDismissedReceiver
import com.securescreen.app.receiver.WatchdogReceiver
import com.securescreen.app.ui.main.MainActivity

class ForegroundService : Service() {

    private lateinit var repository: AppRepository
    private lateinit var detector: ForegroundAppDetector
    private lateinit var secureOverlayManager: SecureOverlayManager
    private lateinit var watermarkOverlayManager: WatermarkOverlayManager
    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var keyguardManager: KeyguardManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var secureActive = false
    private var lastForegroundPackage: String? = null
    private var fastPollingUntilElapsedMs = 0L
    private var teardownScheduled = false
    private var forceRecentsMaskUntilElapsedMs = 0L
    private var protectedSessionActive = false
    private var sessionExitCandidateSinceElapsedMs = 0L
    private var enabledImePackages: Set<String> = emptySet()
    private var imePackageRefreshElapsedMs = 0L
    
    private var protectionEnabled = true
    private var isHealthInterrupted = false
    private var usingAccessibilityOverlay = false
    private var usingAccessibilityWatermark = false

    private val secureTeardownRunnable = Runnable {
        hideSecureOverlay()
        secureActive = false
        teardownScheduled = false
        protectedSessionActive = false
        sessionExitCandidateSinceElapsedMs = 0L
    }

    @Suppress("DEPRECATION")
    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return

            val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON).orEmpty().lowercase()
            val isRecentsTrigger = reason.contains(REASON_RECENT_APPS) ||
                reason.contains(REASON_OVERVIEW)

            if (!isRecentsTrigger) return

            forceRecentsMaskUntilElapsedMs =
                SystemClock.elapsedRealtime() + RECENTS_SIGNAL_GRACE_MS
            fastPollingUntilElapsedMs =
                SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS

            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val shouldStayActive = runCatching { handlePollingTick() }
                .getOrElse {
                    Log.e(TAG, "Polling tick failed", it)
                    false
                }

            mainHandler.postDelayed(
                this,
                if (shouldStayActive) ACTIVE_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS
            )
        }
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (protectionEnabled) {
                checkHealthAndPermissions()
            }
            mainHandler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        detector = ForegroundAppDetector(applicationContext)
        secureOverlayManager = SecureOverlayManager(applicationContext)
        watermarkOverlayManager = WatermarkOverlayManager(applicationContext)
        inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyguardManager = getSystemService(KeyguardManager::class.java)
        protectionEnabled = repository.isProtectionEnabled()
        scheduleWatchdog(this)
        runCatching { refreshEnabledImePackages() }
            .onFailure { Log.w(TAG, "Unable to refresh IME package list on create", it) }
        runCatching { registerSystemDialogsReceiver() }
            .onFailure { Log.w(TAG, "Unable to register system dialogs receiver", it) }
        runCatching { createNotificationChannel() }
            .onFailure { Log.w(TAG, "Unable to create notification channel", it) }
        mainHandler.post(healthCheckRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setProtectionEnabledInternal(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val startResult = runCatching {
            when (intent?.action) {
                ACTION_TOGGLE_PROTECTION -> {
                    setProtectionEnabledInternal(!protectionEnabled)
                }

                ACTION_SET_PROTECTION -> {
                    val enabled = intent.getBooleanExtra(EXTRA_PROTECTION_ENABLED, true)
                    setProtectionEnabledInternal(enabled)
                }

                else -> Unit
            }

            updateForegroundNotification()
            repository.setServiceEnabled(true)
            scheduleWatchdog(this)
            fastPollingUntilElapsedMs =
                SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS

            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)

            START_STICKY
        }.getOrElse { throwable ->
            Log.e(TAG, "onStartCommand failed", throwable)
            repository.setServiceEnabled(false)
            stopSelf()
            START_NOT_STICKY
        }

        return startResult
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(secureTeardownRunnable)
        mainHandler.removeCallbacks(healthCheckRunnable)
        runCatching { unregisterReceiver(systemDialogsReceiver) }
        hideSecureOverlay()
        hideWatermark()
        if (repository.isProtectionEnabled()) {
            scheduleWatchdog(this)
        } else {
            cancelWatchdog(this)
        }
        repository.setServiceEnabled(false)
        secureActive = false
        teardownScheduled = false
        protectedSessionActive = false
        sessionExitCandidateSinceElapsedMs = 0L
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (repository.isProtectionEnabled()) {
            scheduleWatchdog(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePollingTick(): Boolean {
        if (!protectionEnabled || isHealthInterrupted) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            protectedSessionActive = false
            sessionExitCandidateSinceElapsedMs = 0L
            return true
        }

        if (keyguardManager.isKeyguardLocked) {
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            return true
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val snapshot = detector.getForegroundSnapshot()
        val currentForeground = snapshot.packageName
        if (currentForeground != lastForegroundPackage) {
            Log.d(TAG, "Foreground app changed: $lastForegroundPackage -> $currentForeground")
            fastPollingUntilElapsedMs = nowElapsedMs + FAST_POLL_AFTER_SWITCH_WINDOW_MS
            lastForegroundPackage = currentForeground
        }

        val protectedPackages = repository.getProtectedPackages()
        val fullSystemProtectionEnabled = repository.isAggressiveModeEnabled()
        val shouldSecureByPackage = !currentForeground.isNullOrBlank() &&
            currentForeground in protectedPackages &&
            currentForeground != packageName
        if (shouldSecureByPackage || fullSystemProtectionEnabled) {
            protectedSessionActive = true
            sessionExitCandidateSinceElapsedMs = 0L
        }

        val isImeForeground = isImeForegroundPackage(currentForeground)
        val shouldSecureByIme = isImeForeground && protectedSessionActive

        val shouldMaskRecents = !fullSystemProtectionEnabled && protectedPackages.isNotEmpty() &&
            (detector.isRecentsVisible(snapshot.packageName, snapshot.className) ||
                isRecentsSignalActive())

        val shouldBypassSystemWideProtection = fullSystemProtectionEnabled &&
            shouldBypassSystemWideProtection()

        val shouldHoldSession = shouldHoldProtectedSession(
            nowElapsedMs = nowElapsedMs,
            currentForeground = currentForeground,
            protectedPackages = protectedPackages,
            isImeForeground = isImeForeground,
            shouldMaskRecents = shouldMaskRecents,
            fullSystemProtectionEnabled = fullSystemProtectionEnabled,
            shouldBypassSystemWideProtection = shouldBypassSystemWideProtection
        )

        val shouldSecure = (fullSystemProtectionEnabled && !shouldBypassSystemWideProtection) ||
            shouldSecureByPackage ||
            shouldSecureByIme ||
            shouldHoldSession
        val accessibilityEnabled = isAccessibilityOverlayEnabled()
        val overlayAllowed = accessibilityEnabled || PermissionUtils.canDrawOverlays(this)

        if (overlayAllowed && shouldSecure) {
            cancelDelayedTeardown()
            showSecureOverlay(SecureOverlayManager.Mode.TRANSPARENT, accessibilityEnabled)
            secureActive = true
        } else if (overlayAllowed && shouldMaskRecents) {
            cancelDelayedTeardown()
            showSecureOverlay(SecureOverlayManager.Mode.OPAQUE_MASK, accessibilityEnabled)
            secureActive = true
        } else if (!overlayAllowed || shouldBypassSystemWideProtection) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            secureActive = false
            protectedSessionActive = false
            sessionExitCandidateSinceElapsedMs = 0L
        } else if (secureActive) {
            scheduleDelayedTeardown()
        }

        handleWatermarkIfNeeded()

        return shouldSecure || shouldMaskRecents || secureActive ||
            teardownScheduled || isFastPollingWindowActive()
    }

    private fun setProtectionEnabledInternal(enabled: Boolean) {
        if (protectionEnabled == enabled && !isHealthInterrupted) return

        protectionEnabled = enabled
        isHealthInterrupted = false
        repository.setProtectionEnabled(enabled)

        if (!enabled) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            protectedSessionActive = false
            sessionExitCandidateSinceElapsedMs = 0L
        } else {
            fastPollingUntilElapsedMs =
                SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)
        }

        updateForegroundNotification()
        notifyProtectionStateChanged(enabled)
    }

    private fun notifyProtectionStateChanged(enabled: Boolean) {
        val intent = Intent(ACTION_PROTECTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROTECTION_ENABLED, enabled)
        }
        sendBroadcast(intent)
    }

    private fun shouldHoldProtectedSession(
        nowElapsedMs: Long,
        currentForeground: String?,
        protectedPackages: Set<String>,
        isImeForeground: Boolean,
        shouldMaskRecents: Boolean,
        fullSystemProtectionEnabled: Boolean,
        shouldBypassSystemWideProtection: Boolean
    ): Boolean {
        if (fullSystemProtectionEnabled && shouldBypassSystemWideProtection) return false
        if (fullSystemProtectionEnabled) return true
        if (!protectedSessionActive) return false

        val stableNonProtectedForeground = !currentForeground.isNullOrBlank() &&
            currentForeground !in protectedPackages &&
            currentForeground != packageName &&
            !isImeForeground &&
            !shouldMaskRecents

        if (!stableNonProtectedForeground) {
            sessionExitCandidateSinceElapsedMs = 0L
            return true
        }

        if (sessionExitCandidateSinceElapsedMs == 0L) {
            sessionExitCandidateSinceElapsedMs = nowElapsedMs
            return true
        }

        return nowElapsedMs - sessionExitCandidateSinceElapsedMs < SESSION_EXIT_CONFIRM_MS
    }

    private fun scheduleDelayedTeardown() {
        if (teardownScheduled) return

        teardownScheduled = true
        mainHandler.postDelayed(secureTeardownRunnable, SECURE_TEARDOWN_DELAY_MS)
    }

    private fun cancelDelayedTeardown() {
        if (!teardownScheduled) return

        mainHandler.removeCallbacks(secureTeardownRunnable)
        teardownScheduled = false
    }

    private fun isFastPollingWindowActive(): Boolean {
        return SystemClock.elapsedRealtime() < fastPollingUntilElapsedMs
    }

    private fun isRecentsSignalActive(): Boolean {
        return SystemClock.elapsedRealtime() < forceRecentsMaskUntilElapsedMs
    }

    @Suppress("DEPRECATION")
    private fun registerSystemDialogsReceiver() {
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemDialogsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemDialogsReceiver, filter)
        }
    }

    private fun isImeForegroundPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false

        val now = SystemClock.elapsedRealtime()
        if (enabledImePackages.isEmpty() ||
            now - imePackageRefreshElapsedMs > IME_PACKAGE_REFRESH_INTERVAL_MS
        ) {
            refreshEnabledImePackages()
        }

        return packageName in enabledImePackages
    }

    private fun refreshEnabledImePackages() {
        val enabledPackages = runCatching {
            inputMethodManager.enabledInputMethodList
                .map { it.packageName }
        }.getOrDefault(emptyList())

        val defaultImePackage = runCatching {
            val value = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ).orEmpty()
            value.substringBefore('/')
        }.getOrDefault("")

        enabledImePackages = (enabledPackages + defaultImePackage)
            .filter { it.isNotBlank() }
            .toSet()

        imePackageRefreshElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun handleWatermarkIfNeeded() {
        val watermarkEnabled = repository.isWatermarkEnabled()
        val accessibilityEnabled = isAccessibilityOverlayEnabled()

        if (watermarkEnabled && (accessibilityEnabled || PermissionUtils.canDrawOverlays(this))) {
            showWatermark(
                opacityPercent = repository.getWatermarkOpacityPercent(),
                sessionId = repository.getSessionId(),
                useAccessibility = accessibilityEnabled
            )
        } else {
            hideWatermark()
        }
    }

    private fun isAccessibilityOverlayEnabled(): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(
            this,
            SecureAccessibilityService::class.java
        )
    }

    private fun shouldBypassSystemWideProtection(): Boolean {
        return keyguardManager.isKeyguardLocked
    }

    private fun showSecureOverlay(mode: SecureOverlayManager.Mode, useAccessibility: Boolean) {
        if (useAccessibility) {
            usingAccessibilityOverlay = true
            sendAccessibilityOverlayCommand(SecureAccessibilityService.ACTION_SHOW_SECURE_OVERLAY) {
                putExtra(SecureAccessibilityService.EXTRA_SECURE_MODE, mode.name)
            }
        } else {
            usingAccessibilityOverlay = false
            secureOverlayManager.show(mode)
        }
    }

    private fun hideSecureOverlay() {
        if (usingAccessibilityOverlay) {
            sendAccessibilityOverlayCommand(SecureAccessibilityService.ACTION_HIDE_SECURE_OVERLAY)
        } else {
            secureOverlayManager.hide()
        }
        usingAccessibilityOverlay = false
    }

    private fun showWatermark(opacityPercent: Int, sessionId: String, useAccessibility: Boolean) {
        if (useAccessibility) {
            usingAccessibilityWatermark = true
            sendAccessibilityOverlayCommand(SecureAccessibilityService.ACTION_SHOW_WATERMARK) {
                putExtra(SecureAccessibilityService.EXTRA_WATERMARK_OPACITY, opacityPercent)
                putExtra(SecureAccessibilityService.EXTRA_WATERMARK_SESSION_ID, sessionId)
            }
        } else {
            usingAccessibilityWatermark = false
            watermarkOverlayManager.showOrUpdate(opacityPercent, sessionId)
        }
    }

    private fun hideWatermark() {
        if (usingAccessibilityWatermark) {
            sendAccessibilityOverlayCommand(SecureAccessibilityService.ACTION_HIDE_WATERMARK)
        } else {
            watermarkOverlayManager.hide()
        }
        usingAccessibilityWatermark = false
    }

    private fun sendAccessibilityOverlayCommand(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(action).setPackage(packageName)
        intent.configure()
        sendBroadcast(intent)
    }

    private fun updateForegroundNotification(paused: Boolean = false, interrupted: Boolean = false) {
        startForeground(NOTIFICATION_ID, buildNotification(paused, interrupted))
    }

    private fun buildNotification(paused: Boolean = false, interrupted: Boolean = false): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PROTECTION
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            102,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(this, NotificationDismissedReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val protectionPaused = paused || interrupted || !protectionEnabled

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_security)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    when {
                        interrupted -> R.string.notification_text_interrupted
                        paused -> R.string.notification_text_paused
                        else -> R.string.notification_text_active
                    }
                )
            )
            .setOngoing(true)
            .setDeleteIntent(deleteIntent)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_security,
                getString(
                    if (protectionPaused) R.string.notification_action_enable else R.string.notification_action_disable
                ),
                togglePendingIntent
            )
            .addAction(
                R.drawable.ic_security,
                getString(R.string.notification_action_stop_service),
                stopPendingIntent
            )
        
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun checkHealthAndPermissions() {
        if (!protectionEnabled) return
        
        val hasOverlay = PermissionUtils.canDrawOverlays(this) || isAccessibilityOverlayEnabled()
        val hasUsage = PermissionUtils.hasUsageStatsPermission(this)
        
        val isHealthy = hasOverlay && hasUsage
        
        if (!isHealthy && !isHealthInterrupted) {
            Log.w(TAG, "Health check failed! Overlay: $hasOverlay, Usage: $hasUsage")
            isHealthInterrupted = true
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            updateForegroundNotification(paused = false, interrupted = true)
        } else if (isHealthy && isHealthInterrupted) {
            Log.i(TAG, "Health check passed. Restoring protection.")
            isHealthInterrupted = false
            updateForegroundNotification(paused = false, interrupted = false)
        }
    }

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "secure_screen_service_channel"
        private const val NOTIFICATION_ID = 4511
        private const val WATCHDOG_REQUEST_CODE = 4512
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L
        private const val IDLE_POLL_INTERVAL_MS = 80L
        private const val ACTIVE_POLL_INTERVAL_MS = 80L
        private const val FAST_POLL_AFTER_SWITCH_WINDOW_MS = 1_200L
        private const val SECURE_TEARDOWN_DELAY_MS = 500L
        private const val RECENTS_SIGNAL_GRACE_MS = 1_500L
        private const val IME_PACKAGE_REFRESH_INTERVAL_MS = 10_000L
        private const val SESSION_EXIT_CONFIRM_MS = 1_500L

        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val REASON_RECENT_APPS = "recent"
        private const val REASON_OVERVIEW = "overview"

        private const val ACTION_STOP = "com.securescreen.app.action.STOP_SERVICE"
        private const val ACTION_TOGGLE_PROTECTION =
            "com.securescreen.app.action.TOGGLE_PROTECTION"
        private const val ACTION_SET_PROTECTION =
            "com.securescreen.app.action.SET_PROTECTION"
        private const val ACTION_WATCHDOG = "com.securescreen.app.action.WATCHDOG"
        const val ACTION_PROTECTION_STATE_CHANGED =
            "com.securescreen.app.action.PROTECTION_STATE_CHANGED"
        private const val EXTRA_PROTECTION_ENABLED = "extra_protection_enabled"

        fun start(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setProtectionEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ACTION_SET_PROTECTION
                putExtra(EXTRA_PROTECTION_ENABLED, enabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }

        fun scheduleWatchdog(context: Context) {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAtMs = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
        }

        fun cancelWatchdog(context: Context) {
            val alarmManager =
                context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
