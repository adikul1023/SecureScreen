package com.securescreen.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatermarkOverlayManager(
    private val context: Context,
    private val windowTypeOverride: Int? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var watermarkView: TextView? = null

    fun showOrUpdate(opacityPercent: Int, sessionId: String) {
        val opacityAlpha = opacityPercent.coerceIn(10, 100) / 100f

        if (watermarkView == null) {
            watermarkView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setBackgroundColor(Color.parseColor("#55000000"))
                setPadding(24, 16, 24, 16)
                this.alpha = opacityAlpha
            }

            val windowType = windowTypeOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 64
            }

            val added = runCatching {
                windowManager.addView(watermarkView, params)
                true
            }.getOrElse {
                android.util.Log.e("WatermarkOverlay", "Failed to add watermark view", it)
                watermarkView = null
                false
            }
            if (!added) return
        }

        watermarkView?.apply {
            this.alpha = opacityAlpha
            text = buildWatermarkText(sessionId)
        }
    }

    fun hide() {
        watermarkView?.let {
            runCatching { windowManager.removeView(it) }
        }
        watermarkView = null
    }

    private fun buildWatermarkText(sessionId: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        return "SecureScreen  $timestamp\nSession: $sessionId"
    }
}
