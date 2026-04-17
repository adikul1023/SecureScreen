package com.securescreen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.securescreen.app.data.AppRepository

class SecureActivity : AppCompatActivity() {

    private lateinit var repository: AppRepository
    private var targetPackage: String? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_SECURE_ACTIVITY) {
                finishAndRemoveTask()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_secure)
        repository = AppRepository(applicationContext)
        updateTargetPackage(intent)

        isRunning = true

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

        val rootView = findViewById<View>(R.id.secureRoot)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            Log.d(TAG, "IME visible: $imeVisible")
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateTargetPackage(intent)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_CLOSE_SECURE_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(closeReceiver) }
    }

    private fun updateTargetPackage(intent: Intent?) {
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
    }

    private fun shouldRemainProtected(): Boolean {
        val packageName = targetPackage ?: return false
        return repository.getProtectedPackages().contains(packageName)
    }

    companion object {
        private const val TAG = "SecureActivity"
        const val ACTION_CLOSE_SECURE_ACTIVITY = "com.securescreen.app.action.CLOSE_SECURE_ACTIVITY"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

        @Volatile
        var isRunning: Boolean = false
    }
}
