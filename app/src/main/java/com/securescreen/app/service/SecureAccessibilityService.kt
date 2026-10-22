package com.securescreen.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.securescreen.app.data.AppRepository
import com.securescreen.app.service.ForegroundService

class SecureAccessibilityService : AccessibilityService() {

    private lateinit var repository: AppRepository
    private lateinit var secureOverlayManager: SecureOverlayManager
    private lateinit var watermarkOverlayManager: WatermarkOverlayManager
    private var receiverRegistered = false

    private val overlayCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW_SECURE_OVERLAY -> {
                    val modeName = intent.getStringExtra(EXTRA_SECURE_MODE)
                    val mode = runCatching { SecureOverlayManager.Mode.valueOf(modeName.orEmpty()) }
                        .getOrDefault(SecureOverlayManager.Mode.TRANSPARENT)
                    secureOverlayManager.show(mode)
                }

                ACTION_HIDE_SECURE_OVERLAY -> {
                    secureOverlayManager.hide()
                }

                ACTION_SHOW_WATERMARK -> {
                    val opacity = intent.getIntExtra(EXTRA_WATERMARK_OPACITY, 45)
                    val sessionId = intent.getStringExtra(EXTRA_WATERMARK_SESSION_ID).orEmpty()
                    watermarkOverlayManager.showOrUpdate(opacity, sessionId)
                }

                ACTION_HIDE_WATERMARK -> {
                    watermarkOverlayManager.hide()
                }

                ACTION_HIDE_ALL -> {
                    secureOverlayManager.hide()
                    watermarkOverlayManager.hide()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        repository = AppRepository(applicationContext)
        secureOverlayManager = SecureOverlayManager(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )
        watermarkOverlayManager = WatermarkOverlayManager(
            this,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )
        registerOverlayReceiver()
        ensureProtectionServiceRunning()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        secureOverlayManager.hide()
        watermarkOverlayManager.hide()
        unregisterOverlayReceiver()
        if (repository.isProtectionEnabled()) {
            ForegroundService.scheduleWatchdog(this)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        secureOverlayManager.hide()
        watermarkOverlayManager.hide()
        unregisterOverlayReceiver()
        if (repository.isProtectionEnabled()) {
            ForegroundService.scheduleWatchdog(this)
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (!pkg.isNullOrBlank()) {
                val intent = Intent(ACTION_WINDOW_STATE_CHANGED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_PACKAGE_NAME, pkg)
                    putExtra(EXTRA_CLASS_NAME, cls)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun registerOverlayReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW_SECURE_OVERLAY)
            addAction(ACTION_HIDE_SECURE_OVERLAY)
            addAction(ACTION_SHOW_WATERMARK)
            addAction(ACTION_HIDE_WATERMARK)
            addAction(ACTION_HIDE_ALL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayCommandReceiver, filter)
        }

        receiverRegistered = true
    }

    private fun unregisterOverlayReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(overlayCommandReceiver) }
        receiverRegistered = false
    }

    private fun ensureProtectionServiceRunning() {
        if (!repository.isProtectionEnabled()) return

        ForegroundService.start(this)
        ForegroundService.setProtectionEnabled(this, true)
        ForegroundService.scheduleWatchdog(this)
    }

    companion object {
        const val ACTION_SHOW_SECURE_OVERLAY = "com.securescreen.app.action.SHOW_SECURE_OVERLAY"
        const val ACTION_HIDE_SECURE_OVERLAY = "com.securescreen.app.action.HIDE_SECURE_OVERLAY"
        const val ACTION_SHOW_WATERMARK = "com.securescreen.app.action.SHOW_WATERMARK"
        const val ACTION_HIDE_WATERMARK = "com.securescreen.app.action.HIDE_WATERMARK"
        const val ACTION_HIDE_ALL = "com.securescreen.app.action.HIDE_ALL_OVERLAYS"

        const val EXTRA_SECURE_MODE = "extra_secure_mode"
        const val EXTRA_WATERMARK_OPACITY = "extra_watermark_opacity"
        const val EXTRA_WATERMARK_SESSION_ID = "extra_watermark_session_id"

        const val ACTION_WINDOW_STATE_CHANGED = "com.securescreen.app.action.WINDOW_STATE_CHANGED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_CLASS_NAME = "extra_class_name"
    }
}
