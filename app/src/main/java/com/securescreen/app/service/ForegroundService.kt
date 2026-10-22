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
import android.os.HandlerThread
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
import com.securescreen.app.data.UpdateNotificationManager
import com.securescreen.app.receiver.NotificationDismissedReceiver
import com.securescreen.app.receiver.WatchdogReceiver
import com.securescreen.app.data.log.SecurityEvent
import com.securescreen.app.data.log.SecurityEventSeverity
import com.securescreen.app.data.log.SecurityEventType
import com.securescreen.app.data.log.SecurityLogger
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
    private var currentSecureMode: SecureOverlayManager.Mode? = null
    private var lastForegroundPackage: String? = null
    private var fastPollingUntilElapsedMs = 0L
    private var teardownScheduled = false
    private var forceRecentsMaskUntilElapsedMs = 0L
    private var isRecentsOverrideActive = false
    private var lastRecentsActiveElapsedMs = 0L
    private var protectedSessionActive = false
    private var sessionExitCandidateSinceElapsedMs = 0L
    private var enabledImePackages: Set<String> = emptySet()
    private var imePackageRefreshElapsedMs = 0L
    
    private var protectionEnabled = true
    private var isHealthInterrupted = false
    private var usingAccessibilityOverlay = false
    private var usingAccessibilityWatermark = false

    private var currentlyActiveProtectedPackage: String? = null
    private var lastUsageGranted: Boolean? = null
    private var lastOverlayGranted: Boolean? = null
    private var lastAccessibilityGranted: Boolean? = null

    private var watchdogHandlerThread: HandlerThread? = null
    private var watchdogHandler: Handler? = null

    private val secureTeardownRunnable = Runnable {
        hideSecureOverlay()
        secureActive = false
        currentSecureMode = null
        teardownScheduled = false
        protectedSessionActive = false
        isRecentsOverrideActive = false
        lastRecentsActiveElapsedMs = 0L
        sessionExitCandidateSinceElapsedMs = 0L
    }

    private val windowStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SecureAccessibilityService.ACTION_WINDOW_STATE_CHANGED) return

            val pkg = intent.getStringExtra(SecureAccessibilityService.EXTRA_PACKAGE_NAME)
            val cls = intent.getStringExtra(SecureAccessibilityService.EXTRA_CLASS_NAME)

            if (!pkg.isNullOrBlank()) {
                detector.updateFromAccessibility(pkg, cls)
            }

            if (detector.isRecentsVisible(pkg, cls)) {
                if (protectionEnabled && repository.getProtectedPackages().isNotEmpty()) {
                    forceRecentsMaskUntilElapsedMs =
                        SystemClock.elapsedRealtime() + RECENTS_SIGNAL_GRACE_MS
                }
            }
            fastPollingUntilElapsedMs =
                SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS

            mainHandler.removeCallbacks(pollRunnable)
            mainHandler.post {
                handlePollingTick("ACCESSIBILITY_WINDOW_STATE")
            }
        }
    }

    @Suppress("DEPRECATION")
    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return

            val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON).orEmpty().lowercase()
            val isRecentsTrigger = reason.contains(REASON_RECENT_APPS) ||
                reason.contains(REASON_OVERVIEW)
            val isHomeTrigger = reason.contains("homekey") || reason.contains("home")

            if (isRecentsTrigger) {
                // Whenever app-specific protection is enabled with configured protected apps,
                // opening Recents triggers temporary protection regardless of the prior foreground app.
                if (protectionEnabled && repository.getProtectedPackages().isNotEmpty()) {
                    forceRecentsMaskUntilElapsedMs =
                        SystemClock.elapsedRealtime() + RECENTS_SIGNAL_GRACE_MS
                    fastPollingUntilElapsedMs =
                        SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS

                    mainHandler.removeCallbacks(pollRunnable)
                    mainHandler.post {
                        handlePollingTick("SYSTEM_DIALOG_RECENTS")
                    }
                }
            } else if (isHomeTrigger) {
                // User pressed home button / performed home gesture to return to desktop
                forceRecentsMaskUntilElapsedMs = 0L
                fastPollingUntilElapsedMs =
                    SystemClock.elapsedRealtime() + FAST_POLL_AFTER_SWITCH_WINDOW_MS

                mainHandler.removeCallbacks(pollRunnable)
                mainHandler.post {
                    handlePollingTick("SYSTEM_DIALOG_HOME")
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            WatchdogRecoveryManager.markHeartbeat()
            val shouldStayActive = runCatching { handlePollingTick("POLL_TICK") }
                .getOrElse { throwable ->
                    Log.e(TAG, "Recoverable error in polling tick", throwable)
                    SecurityLogger.log(
                        this@ForegroundService,
                        SecurityEvent(
                            eventType = SecurityEventType.PROTECTION_UNAVAILABLE,
                            severity = SecurityEventSeverity.WARNING,
                            title = "Polling Loop Recovered",
                            details = "Recovered from ${throwable.javaClass.simpleName}: ${throwable.message ?: "Internal error"}"
                        )
                    )
                    runCatching {
                        detector = ForegroundAppDetector(applicationContext)
                    }
                    false
                }

            mainHandler.postDelayed(
                this,
                if (shouldStayActive) ACTIVE_POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS
            )
        }
    }

    private val inServiceWatchdogRunnable = object : Runnable {
        override fun run() {
            if (protectionEnabled && !isHealthInterrupted) {
                if (WatchdogRecoveryManager.isMonitoringStalled(WatchdogRecoveryManager.STALL_THRESHOLD_MS)) {
                    val stallSec = WatchdogRecoveryManager.getStallDurationMs() / 1000
                    Log.w(TAG, "In-service watchdog detected stalled polling loop (${stallSec}s)")
                    WatchdogRecoveryManager.recordRecoveryAttempt(
                        this@ForegroundService,
                        "In-service watchdog detected stalled polling loop (${stallSec}s inactive)"
                    )
                    mainHandler.removeCallbacks(pollRunnable)
                    mainHandler.post(pollRunnable)
                } else {
                    WatchdogRecoveryManager.checkAndRecordStableRun(this@ForegroundService)
                }
            }
            watchdogHandler?.postDelayed(this, IN_SERVICE_WATCHDOG_CHECK_INTERVAL_MS)
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

    private val updateCheckRunnable = object : Runnable {
        override fun run() {
            UpdateNotificationManager.check(this@ForegroundService)
            mainHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)

        // Reinitialize runtime state safely to eliminate any zombie/leaked views from dead processes
        secureOverlayManager = SecureOverlayManager(applicationContext)
        watermarkOverlayManager = WatermarkOverlayManager(applicationContext)
        secureOverlayManager.hide()
        watermarkOverlayManager.hide()
        secureActive = false
        currentSecureMode = null
        usingAccessibilityOverlay = false
        usingAccessibilityWatermark = false
        protectedSessionActive = false
        isRecentsOverrideActive = false
        lastRecentsActiveElapsedMs = 0L
        sessionExitCandidateSinceElapsedMs = 0L
        currentlyActiveProtectedPackage = null
        lastUsageGranted = null
        lastOverlayGranted = null
        lastAccessibilityGranted = null

        WatchdogRecoveryManager.markServiceStarted()
        val wasServiceMarkedEnabled = repository.isServiceEnabled()
        if (wasServiceMarkedEnabled) {
            WatchdogRecoveryManager.recordRecoveryAttempt(
                this,
                "Service recreated by Android runtime (START_STICKY recovery)"
            )
        } else {
            SecurityLogger.logServiceRestarted(
                this,
                "Foreground service initialized",
                SecurityEventSeverity.INFO
            )
        }
        detector = ForegroundAppDetector(applicationContext)
        inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyguardManager = getSystemService(KeyguardManager::class.java)
        protectionEnabled = repository.isProtectionEnabled()
        scheduleWatchdog(this)
        runCatching { refreshEnabledImePackages() }
            .onFailure { Log.w(TAG, "Unable to refresh IME package list on create", it) }
        runCatching { registerSystemDialogsReceiver() }
            .onFailure { Log.w(TAG, "Unable to register system dialogs receiver", it) }
        runCatching { registerWindowStateReceiver() }
            .onFailure { Log.w(TAG, "Unable to register window state receiver", it) }
        runCatching { createNotificationChannel() }
            .onFailure { Log.w(TAG, "Unable to create notification channel", it) }
        
        initInServiceWatchdog()
        UpdateNotificationManager.check(this)
        mainHandler.postDelayed(updateCheckRunnable, UPDATE_CHECK_INTERVAL_MS)
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

                ACTION_RECOVER_SERVICE -> {
                    val reason = intent.getStringExtra(EXTRA_RECOVERY_REASON) ?: "Watchdog self-recovery"
                    Log.i(TAG, "Recovering service: $reason")
                    WatchdogRecoveryManager.markHeartbeat()
                    setProtectionEnabledInternal(true)
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
        watchdogHandler?.removeCallbacks(inServiceWatchdogRunnable)
        watchdogHandlerThread?.quitSafely()
        watchdogHandlerThread = null
        watchdogHandler = null

        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(secureTeardownRunnable)
        mainHandler.removeCallbacks(healthCheckRunnable)
        mainHandler.removeCallbacks(updateCheckRunnable)
        runCatching { unregisterReceiver(systemDialogsReceiver) }
        runCatching { unregisterReceiver(windowStateReceiver) }
        hideSecureOverlay()
        hideWatermark()
        if (repository.isProtectionEnabled()) {
            scheduleWatchdog(this)
        } else {
            cancelWatchdog(this)
        }
        repository.setServiceEnabled(false)
        secureActive = false
        currentSecureMode = null
        teardownScheduled = false
        protectedSessionActive = false
        isRecentsOverrideActive = false
        lastRecentsActiveElapsedMs = 0L
        sessionExitCandidateSinceElapsedMs = 0L
    }

    private fun initInServiceWatchdog() {
        if (watchdogHandlerThread == null) {
            val thread = HandlerThread("SS_WatchdogThread").apply { start() }
            watchdogHandlerThread = thread
            watchdogHandler = Handler(thread.looper)
            watchdogHandler?.postDelayed(inServiceWatchdogRunnable, IN_SERVICE_WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (repository.isProtectionEnabled()) {
            scheduleWatchdog(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePollingTick(eventSource: String = "POLL_TICK"): Boolean {
        if (!protectionEnabled || isHealthInterrupted) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            currentSecureMode = null
            protectedSessionActive = false
            isRecentsOverrideActive = false
            lastRecentsActiveElapsedMs = 0L
            sessionExitCandidateSinceElapsedMs = 0L
            return true
        }

        if (keyguardManager.isKeyguardLocked) {
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            currentSecureMode = null
            isRecentsOverrideActive = false
            lastRecentsActiveElapsedMs = 0L
            return true
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val snapshot = detector.getForegroundSnapshot()
        val currentForeground = snapshot.packageName
        val currentClass = snapshot.className
        val protectedPackages = repository.getProtectedPackages()
        val fullSystemProtectionEnabled = repository.isAggressiveModeEnabled()

        val isProtectedAppForeground = !currentForeground.isNullOrBlank() &&
            currentForeground in protectedPackages &&
            currentForeground != packageName

        if (currentForeground != lastForegroundPackage) {
            Log.d(TAG, "Foreground app changed: $lastForegroundPackage -> $currentForeground (class=$currentClass, protected=$protectedPackages, enabled=$protectionEnabled)")
            fastPollingUntilElapsedMs = nowElapsedMs + FAST_POLL_AFTER_SWITCH_WINDOW_MS

            if (currentlyActiveProtectedPackage != null && currentForeground != currentlyActiveProtectedPackage) {
                val isIme = isImeForegroundPackage(currentForeground)
                val isRecents = detector.isRecentsVisible(currentForeground, currentClass)
                val isSystemUi = detector.isSystemUiPackage(currentForeground)
                if (!isIme && !isRecents && !isSystemUi) {
                    SecurityLogger.logAppExited(this, currentlyActiveProtectedPackage!!)
                    currentlyActiveProtectedPackage = null
                }
            }

            if (isProtectedAppForeground) {
                SecurityLogger.logAppEntered(this, currentForeground!!)
                currentlyActiveProtectedPackage = currentForeground
            }

            lastForegroundPackage = currentForeground
        }

        val shouldBypassSystemWideProtection = fullSystemProtectionEnabled &&
            shouldBypassSystemWideProtection()

        val evaluation = ProtectionSessionEvaluator.evaluate(
            nowElapsedMs = nowElapsedMs,
            currentForeground = currentForeground,
            protectedPackages = protectedPackages,
            servicePackageName = packageName,
            fullSystemProtectionEnabled = fullSystemProtectionEnabled,
            shouldBypassSystemWideProtection = shouldBypassSystemWideProtection,
            isImeForeground = isImeForegroundPackage(currentForeground),
            isRecentsDetected = detector.isRecentsVisible(currentForeground, currentClass),
            isLauncherPackage = detector.isLauncherPackage(currentForeground),
            isLauncherHomeWorkspace = detector.isLauncherHomeWorkspace(currentForeground, currentClass),
            isSystemUiPackage = detector.isSystemUiPackage(currentForeground),
            currentState = ProtectionSessionEvaluator.EvaluationState(
                protectedSessionActive = protectedSessionActive,
                sessionExitCandidateSinceElapsedMs = sessionExitCandidateSinceElapsedMs,
                forceRecentsMaskUntilElapsedMs = forceRecentsMaskUntilElapsedMs,
                isRecentsOverrideActive = isRecentsOverrideActive,
                lastRecentsActiveElapsedMs = lastRecentsActiveElapsedMs
            ),
            currentSecureMode = currentSecureMode,
            sessionExitConfirmMs = SESSION_EXIT_CONFIRM_MS
        )

        Log.d(
            TAG,
            "TRANSITION_EVAL: src=$eventSource, pkg=$currentForeground, cls=$currentClass, " +
                "override=[$isRecentsOverrideActive -> ${evaluation.updatedState.isRecentsOverrideActive}], " +
                "session=[$protectedSessionActive -> ${evaluation.updatedState.protectedSessionActive}], " +
                "targetMode=${evaluation.targetMode}, teardown=${evaluation.shouldTeardownImmediately}, " +
                "secureActive=$secureActive"
        )

        protectedSessionActive = evaluation.updatedState.protectedSessionActive
        sessionExitCandidateSinceElapsedMs = evaluation.updatedState.sessionExitCandidateSinceElapsedMs
        forceRecentsMaskUntilElapsedMs = evaluation.updatedState.forceRecentsMaskUntilElapsedMs
        isRecentsOverrideActive = evaluation.updatedState.isRecentsOverrideActive
        lastRecentsActiveElapsedMs = evaluation.updatedState.lastRecentsActiveElapsedMs

        val accessibilityEnabled = isAccessibilityOverlayEnabled()
        val overlayAllowed = accessibilityEnabled || PermissionUtils.canDrawOverlays(this)

        if (overlayAllowed && evaluation.targetMode != null) {
            cancelDelayedTeardown()
            showSecureOverlay(evaluation.targetMode, accessibilityEnabled)
            secureActive = true
        } else if (!overlayAllowed || shouldBypassSystemWideProtection) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            secureActive = false
            currentSecureMode = null
            protectedSessionActive = false
            isRecentsOverrideActive = false
            lastRecentsActiveElapsedMs = 0L
            sessionExitCandidateSinceElapsedMs = 0L
        } else if (secureActive) {
            if (evaluation.shouldTeardownImmediately) {
                cancelDelayedTeardown()
                hideSecureOverlay()
                secureActive = false
                currentSecureMode = null
            } else {
                scheduleDelayedTeardown()
            }
        }

        handleWatermarkIfNeeded()

        return evaluation.targetMode != null || secureActive ||
            teardownScheduled || isFastPollingWindowActive()
    }

    private fun setProtectionEnabledInternal(enabled: Boolean) {
        if (protectionEnabled == enabled && !isHealthInterrupted) return

        protectionEnabled = enabled
        isHealthInterrupted = false
        repository.setProtectionEnabled(enabled)

        if (enabled) {
            SecurityLogger.logProtectionEnabled(this, "Protection activated")
        } else {
            SecurityLogger.logProtectionDisabled(this, "Protection paused / deactivated")
        }

        if (!enabled) {
            cancelDelayedTeardown()
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            currentSecureMode = null
            protectedSessionActive = false
            isRecentsOverrideActive = false
            lastRecentsActiveElapsedMs = 0L
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

    private fun registerWindowStateReceiver() {
        val filter = IntentFilter(SecureAccessibilityService.ACTION_WINDOW_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(windowStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(windowStateReceiver, filter)
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
        if (currentSecureMode != mode) {
            Log.d(TAG, "Overlay mode transition: $currentSecureMode -> $mode")
            currentSecureMode = mode
        }
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
        if (currentSecureMode != null) {
            Log.d(TAG, "Overlay mode transition: $currentSecureMode -> null")
            currentSecureMode = null
        }
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
        val hasAccessibility = isAccessibilityOverlayEnabled()

        if (lastUsageGranted == true && !hasUsage) {
            SecurityLogger.logPermissionRevoked(this, "Usage Access", "Usage access permission was revoked")
        } else if (lastUsageGranted == false && hasUsage) {
            SecurityLogger.logPermissionRestored(this, "Usage Access")
        }
        lastUsageGranted = hasUsage

        if (lastOverlayGranted == true && !hasOverlay) {
            SecurityLogger.logPermissionRevoked(this, "Overlay Permission", "Display over other apps permission was revoked")
        } else if (lastOverlayGranted == false && hasOverlay) {
            SecurityLogger.logPermissionRestored(this, "Overlay Permission")
        }
        lastOverlayGranted = hasOverlay

        if (lastAccessibilityGranted == true && !hasAccessibility) {
            SecurityLogger.logPermissionRevoked(this, "Accessibility Service", "SecureScreen accessibility service was turned off")
        } else if (lastAccessibilityGranted == false && hasAccessibility) {
            SecurityLogger.logPermissionRestored(this, "Accessibility Service")
        }
        lastAccessibilityGranted = hasAccessibility

        val isHealthy = hasOverlay && hasUsage
        
        if (!isHealthy && !isHealthInterrupted) {
            Log.w(TAG, "Health check failed! Overlay: $hasOverlay, Usage: $hasUsage")
            isHealthInterrupted = true
            hideSecureOverlay()
            hideWatermark()
            secureActive = false
            updateForegroundNotification(paused = false, interrupted = true)

            val missing = mutableListOf<String>()
            if (!hasUsage) missing.add("Usage Access")
            if (!hasOverlay) missing.add("Overlay / Accessibility")
            SecurityLogger.logProtectionUnavailable(
                this,
                "Protection paused due to missing permission(s): ${missing.joinToString(", ")}"
            )
        } else if (isHealthy && isHealthInterrupted) {
            Log.i(TAG, "Health check passed. Restoring protection.")
            isHealthInterrupted = false
            updateForegroundNotification(paused = false, interrupted = false)
            SecurityLogger.logProtectionRestored(
                this,
                "Required permissions verified. Protection resumed."
            )
        }
    }

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "secure_screen_service_channel"
        private const val NOTIFICATION_ID = 4511
        private const val WATCHDOG_REQUEST_CODE = 4512
        private const val IN_SERVICE_WATCHDOG_CHECK_INTERVAL_MS = 10_000L
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L
        private const val IDLE_POLL_INTERVAL_MS = 80L
        private const val ACTIVE_POLL_INTERVAL_MS = 80L
        private const val FAST_POLL_AFTER_SWITCH_WINDOW_MS = 1_200L
        private const val SECURE_TEARDOWN_DELAY_MS = 500L
        private const val RECENTS_SIGNAL_GRACE_MS = 1_500L
        private const val IME_PACKAGE_REFRESH_INTERVAL_MS = 10_000L
        private const val SESSION_EXIT_CONFIRM_MS = 1_500L
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val REASON_RECENT_APPS = "recent"
        private const val REASON_OVERVIEW = "overview"

        private const val ACTION_STOP = "com.securescreen.app.action.STOP_SERVICE"
        private const val ACTION_TOGGLE_PROTECTION =
            "com.securescreen.app.action.TOGGLE_PROTECTION"
        private const val ACTION_SET_PROTECTION =
            "com.securescreen.app.action.SET_PROTECTION"
        const val ACTION_RECOVER_SERVICE = "com.securescreen.app.action.RECOVER_SERVICE"
        private const val ACTION_WATCHDOG = "com.securescreen.app.action.WATCHDOG"
        const val ACTION_PROTECTION_STATE_CHANGED =
            "com.securescreen.app.action.PROTECTION_STATE_CHANGED"
        private const val EXTRA_PROTECTION_ENABLED = "extra_protection_enabled"
        const val EXTRA_RECOVERY_REASON = "extra_recovery_reason"

        fun start(context: Context) {
            val intent = Intent(context, ForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun recover(context: Context, reason: String) {
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ACTION_RECOVER_SERVICE
                putExtra(EXTRA_RECOVERY_REASON, reason)
            }
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
            val intervalMs = WatchdogRecoveryManager.getNextWatchdogInterval(context)
            val triggerAtMs = SystemClock.elapsedRealtime() + intervalMs

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
