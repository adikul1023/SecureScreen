package com.securescreen.app.service

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
import com.securescreen.app.ui.main.MainActivity

class ForegroundService : Service() {

    private lateinit var repository: AppRepository
    private lateinit var detector: ForegroundAppDetector
    private lateinit var secureOverlayManager: SecureOverlayManager
    private lateinit var watermarkOverlayManager: WatermarkOverlayManager
    private lateinit var inputMethodManager: InputMethodManager

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

    private val secureTeardownRunnable = Runnable {
        secureOverlayManager.hide()
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

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        detector = ForegroundAppDetector(applicationContext)
        secureOverlayManager = SecureOverlayManager(applicationContext)
        watermarkOverlayManager = WatermarkOverlayManager(applicationContext)
        inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        protectionEnabled = repository.isProtectionEnabled()
        runCatching { refreshEnabledImePackages() }
            .onFailure { Log.w(TAG, "Unable to refresh IME package list on create", it) }
        runCatching { registerSystemDialogsReceiver() }
            .onFailure { Log.w(TAG, "Unable to register system dialogs receiver", it) }
        runCatching { createNotificationChannel() }
            .onFailure { Log.w(TAG, "Unable to create notification channel", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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

            startForeground(NOTIFICATION_ID, buildNotification())
            repository.setServiceEnabled(true)
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
        runCatching { unregisterReceiver(systemDialogsReceiver) }
        secureOverlayManager.hide()
        watermarkOverlayManager.hide()
        repository.setServiceEnabled(false)
        secureActive = false
        teardownScheduled = false
        protectedSessionActive = false
        sessionExitCandidateSinceElapsedMs = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePollingTick(): Boolean {
        if (!protectionEnabled) {
            cancelDelayedTeardown()
            secureOverlayManager.hide()
            watermarkOverlayManager.hide()
            secureActive = false
            protectedSessionActive = false
            sessionExitCandidateSinceElapsedMs = 0L
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

        val shouldHoldSession = shouldHoldProtectedSession(
            nowElapsedMs = nowElapsedMs,
            currentForeground = currentForeground,
            protectedPackages = protectedPackages,
            isImeForeground = isImeForeground,
            shouldMaskRecents = shouldMaskRecents,
            fullSystemProtectionEnabled = fullSystemProtectionEnabled
        )

        val shouldSecure = fullSystemProtectionEnabled ||
            shouldSecureByPackage ||
            shouldSecureByIme ||
            shouldHoldSession
        val overlayAllowed = PermissionUtils.canDrawOverlays(this)

        if (overlayAllowed && shouldSecure) {
            cancelDelayedTeardown()
            secureOverlayManager.show(SecureOverlayManager.Mode.TRANSPARENT)
            secureActive = true
        } else if (overlayAllowed && shouldMaskRecents) {
            cancelDelayedTeardown()
            secureOverlayManager.show(SecureOverlayManager.Mode.OPAQUE_MASK)
            secureActive = true
        } else if (!overlayAllowed) {
            cancelDelayedTeardown()
            secureOverlayManager.hide()
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
        if (protectionEnabled == enabled) return

        protectionEnabled = enabled
        repository.setProtectionEnabled(enabled)

        if (!enabled) {
            cancelDelayedTeardown()
            secureOverlayManager.hide()
            watermarkOverlayManager.hide()
            secureActive = false
            protectedSessionActive = false
            sessionExitCandidateSinceElapsedMs = 0L
        } else {
            fastPollingUntilElapsedMs =
                SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS
            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post(pollRunnable)
        }

        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure {
            Log.w(TAG, "Unable to update foreground notification", it)
        }
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
        fullSystemProtectionEnabled: Boolean
    ): Boolean {
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

        if (watermarkEnabled && PermissionUtils.canDrawOverlays(this)) {
            watermarkOverlayManager.showOrUpdate(
                opacityPercent = repository.getWatermarkOpacityPercent(),
                sessionId = repository.getSessionId()
            )
        } else {
            watermarkOverlayManager.hide()
        }
    }

    private fun buildNotification(): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_security)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                getString(
                    if (protectionEnabled) {
                        R.string.notification_text_active
                    } else {
                        R.string.notification_text_paused
                    }
                )
            )
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_security,
                getString(
                    if (protectionEnabled) {
                        R.string.notification_action_disable
                    } else {
                        R.string.notification_action_enable
                    }
                ),
                togglePendingIntent
            )
            .addAction(
                R.drawable.ic_security,
                getString(R.string.notification_action_stop_service),
                stopPendingIntent
            )
            .build()
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

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "secure_screen_service_channel"
        private const val NOTIFICATION_ID = 4511
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
    }
}
