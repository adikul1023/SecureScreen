package com.securescreen.app.ui.main

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.securescreen.app.R
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.data.UpdateNotificationManager
import com.securescreen.app.databinding.ActivityMainBinding
import com.securescreen.app.service.ForegroundService
import com.securescreen.app.service.SecureAccessibilityService
import com.securescreen.app.ui.protectedapps.ProtectedAppsActivity
import com.securescreen.app.ui.permissions.PermissionsActivity
import com.securescreen.app.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var repository: AppRepository
    private var protectedPackages: Set<String> = emptySet()
    private var pendingEnableProtectionAfterNotificationPermission = false
    private var pendingRestoreServiceAfterNotificationPermission = false

    private val protectionStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ForegroundService.ACTION_PROTECTION_STATE_CHANGED) {
                viewModel.loadState()
            }
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingRestoreServiceAfterNotificationPermission) {
                    ForegroundService.start(this)
                    ForegroundService.setProtectionEnabled(this, repository.isProtectionEnabled())
                }
                if (pendingEnableProtectionAfterNotificationPermission) {
                    enableProtectionInternal()
                }
            } else {
                Toast.makeText(
                    this,
                    R.string.notification_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }

            pendingEnableProtectionAfterNotificationPermission = false
            pendingRestoreServiceAfterNotificationPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        UpdateNotificationManager.check(this)
        binding.topAppBar.inflateMenu(R.menu.menu_main)
        setupTopBar()
        setupClicks()
        setupModeSelector()
        observeViewModel()

        viewModel.loadState()

        // Restore monitoring automatically if user had protection enabled.
        if (repository.isProtectionEnabled()) {
            if (ensureNotificationPermissionForServiceStart(fromUserEnable = false)) {
                ForegroundService.start(this)
                ForegroundService.setProtectionEnabled(this, true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadState()
        refreshDashboard()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ForegroundService.ACTION_PROTECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(protectionStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(protectionStateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(protectionStateReceiver) }
    }

    private fun setupTopBar() {
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupClicks() {
        binding.enableProtectionButton.setOnClickListener {
            handleProtectionToggle()
        }

        binding.managePermissionsButton.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        binding.manageProtectedAppsButton.setOnClickListener {
            startActivity(Intent(this, ProtectedAppsActivity::class.java))
        }
    }

    private fun setupModeSelector() {
        binding.protectionModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val systemWide = checkedId == R.id.systemWideModeButton
            repository.setAggressiveModeEnabled(systemWide)
            refreshDashboard()
        }
    }

    private fun observeViewModel() {
        viewModel.protectedPackages.observe(this) { packages ->
            protectedPackages = packages
            refreshDashboard()
        }

        viewModel.serviceEnabled.observe(this) { enabled ->
            renderStatus(enabled)
        }
    }

    private fun refreshDashboard() {
        syncModeSelector()
        renderStatus(viewModel.serviceEnabled.value == true)
        renderPermissions()
        renderModeCard()
        renderProtectedAppsCard()
    }

    private fun syncModeSelector() {
        val systemWide = repository.isAggressiveModeEnabled()
        val buttonId = if (systemWide) R.id.systemWideModeButton else R.id.appWiseModeButton
        if (binding.protectionModeGroup.checkedButtonId != buttonId) {
            binding.protectionModeGroup.check(buttonId)
        }
    }

    private fun renderStatus(enabled: Boolean) {
        val statusTextRes = if (enabled) R.string.status_active else R.string.status_inactive
        val statusColor = if (enabled) R.color.status_active else R.color.status_inactive
        val modeTitleRes = if (repository.isAggressiveModeEnabled()) {
            R.string.systemwide_mode_title
        } else {
            R.string.app_specific_mode_title
        }

        binding.statusValue.text = getString(statusTextRes)
        binding.statusValue.setTextColor(ContextCompat.getColor(this, statusColor))
        binding.currentModeValue.text = getString(modeTitleRes)
        binding.protectedAppsSummary.text = getString(
            R.string.protected_apps_summary,
            protectedPackages.size
        )
        binding.enableProtectionButton.text = if (enabled) {
            getString(R.string.disable_protection)
        } else {
            getString(R.string.enable_protection)
        }

        binding.statusValue.text = buildString {
            append(if (enabled) "●" else "○")
            append(' ')
            append(getString(statusTextRes))
        }
    }

    private fun renderPermissions() {
        val usageGranted = PermissionUtils.hasUsageStatsPermission(this)
        val batteryIgnored = PermissionUtils.isIgnoringBatteryOptimizations(this)
        val accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            SecureAccessibilityService::class.java
        )
        val notificationGranted = PermissionUtils.hasNotificationPermission(this)
        val exactAlarmAllowed = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }
        val grantedCount = listOf(
            usageGranted,
            batteryIgnored,
            accessibilityEnabled,
            notificationGranted,
            exactAlarmAllowed
        ).count { it }

        binding.permissionsSummaryText.text = getString(
            R.string.permissions_granted_summary,
            grantedCount,
            5
        )

        applyStatusChip(binding.usageAccessChip, "Usage Access", usageGranted)
        applyStatusChip(binding.batteryOptimizationChip, "Battery", batteryIgnored)
        applyStatusChip(binding.accessibilityChip, "Accessibility", accessibilityEnabled)
        applyStatusChip(binding.notificationChip, "Notifications", notificationGranted)
        applyStatusChip(binding.exactAlarmChip, "Exact Alarm", exactAlarmAllowed)

        binding.managePermissionsButton.visibility = if (grantedCount < 5) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun renderModeCard() {
        binding.modeDescription.text = getString(
            if (repository.isAggressiveModeEnabled()) {
                R.string.mode_description_systemwide
            } else {
                R.string.mode_description_appwise
            }
        )
    }

    private fun renderProtectedAppsCard() {
        val count = protectedPackages.size
        binding.selectedCount.text = getString(R.string.selected_apps_summary, count)
        binding.selectedAppsHint.text = if (repository.isAggressiveModeEnabled()) {
            getString(R.string.systemwide_active_note)
        } else {
            getString(R.string.manage_apps_description)
        }

        binding.selectedAppsCard.visibility = if (repository.isAggressiveModeEnabled()) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
    }

    private fun applyStatusChip(chip: Chip, label: String, isPositive: Boolean) {
        val text = if (isPositive) "✓ $label" else label
        val backgroundRes = R.color.surface_container_high
        val textColor = if (isPositive) R.color.status_active else R.color.text_secondary

        chip.text = text
        chip.isCheckable = false
        chip.isClickable = false
        chip.chipBackgroundColor = ContextCompat.getColorStateList(this, backgroundRes)
        chip.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun handleProtectionToggle() {
        val currentlyEnabled = viewModel.serviceEnabled.value == true

        if (!currentlyEnabled) {
            enableProtectionInternal()
        } else {
            ForegroundService.setProtectionEnabled(this, false)
            viewModel.setServiceEnabled(false)
        }
    }

    private fun enableProtectionInternal() {
        if (!ensureNotificationPermissionForServiceStart(fromUserEnable = true)) {
            return
        }

        if (!ensureExactAlarmPermission()) {
            return
        }

        if (viewModel.serviceEnabled.value == true) {
            return
        }

        if (!PermissionUtils.hasUsageStatsPermission(this)) {
            Toast.makeText(this, R.string.usage_access_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        if (!PermissionUtils.isAccessibilityServiceEnabled(this, SecureAccessibilityService::class.java)) {
            Toast.makeText(this, R.string.accessibility_permission_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!PermissionUtils.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }

        ForegroundService.start(this)
        ForegroundService.setProtectionEnabled(this, true)
        viewModel.setServiceEnabled(true)
    }

    private fun ensureNotificationPermissionForServiceStart(fromUserEnable: Boolean): Boolean {
        if (PermissionUtils.hasNotificationPermission(this)) {
            return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        if (fromUserEnable) {
            pendingEnableProtectionAfterNotificationPermission = true
        } else {
            pendingRestoreServiceAfterNotificationPermission = true
        }

        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }

    private fun ensureExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) {
            return true
        }

        Toast.makeText(this, R.string.exact_alarm_permission_required, Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        return false
    }
}
