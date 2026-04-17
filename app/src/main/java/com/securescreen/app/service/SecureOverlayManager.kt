package com.securescreen.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class SecureOverlayManager(private val context: Context) {

    enum class Mode {
        TRANSPARENT,
        OPAQUE_MASK
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var secureOverlayView: View? = null
    private var currentMode: Mode? = null

    @Suppress("DEPRECATION")
    fun show(mode: Mode) {
        if (secureOverlayView == null) {
            val view = View(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            windowManager.addView(view, params)
            secureOverlayView = view
        }

        if (currentMode != mode) {
            applyMode(mode)
            currentMode = mode
        }
    }

    fun hide() {
        secureOverlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        secureOverlayView = null
        currentMode = null
    }

    private fun applyMode(mode: Mode) {
        val view = secureOverlayView ?: return

        when (mode) {
            Mode.TRANSPARENT -> {
                // Keep a tiny alpha so SurfaceFlinger still composes this secure layer.
                view.setBackgroundColor(Color.argb(1, 0, 0, 0))
                view.alpha = 0.01f
            }

            Mode.OPAQUE_MASK -> {
                view.setBackgroundColor(Color.BLACK)
                view.alpha = 0.98f
            }
        }
    }
}
