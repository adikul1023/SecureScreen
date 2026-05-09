package com.securescreen.app.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.securescreen.app.R
import com.securescreen.app.data.AppInfo
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.databinding.ActivityMainBinding
import com.securescreen.app.service.ForegroundService
import com.securescreen.app.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: AppSelectionAdapter
    private lateinit var repository: AppRepository
    private var allApps: List<AppInfo> = emptyList()
    private var protectedPackages: Set<String> = emptySet()
    private var appSearchQuery: String = ""
    private var updatingScopeSelector = false
    private var updatingBootToggle = false
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
        setupRecyclerView()
        setupSearch()
        setupClicks()
        setupScopeSelector()
        observeViewModel()

        viewModel.loadApps()

        // Restore monitoring automatically if user had protection enabled.
        if (repository.isServiceEnabled()) {
            if (ensureNotificationPermissionForServiceStart(fromUserEnable = false)) {
                ForegroundService.start(this)
                ForegroundService.setProtectionEnabled(this, repository.isProtectionEnabled())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadState()
        updatePermissionState()
        syncScopeSelector()
        syncAutoStartToggle()
        updateBatteryOptimizationState()
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

    private fun setupRecyclerView() {
        adapter = AppSelectionAdapter { packageName, isProtected ->
            viewModel.setPackageProtected(packageName, isProtected)
        }

        binding.appsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.appsRecyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSearchQuery = s?.toString().orEmpty()
                renderAppList()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupClicks() {
        binding.grantUsageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.enableProtectionButton.setOnClickListener {
            handleProtectionToggle()
        }

        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.openBatteryOptimizationGuideButton.setOnClickListener {
            openBatteryOptimizationGuide()
        }

        binding.autoStartOnBootSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingBootToggle) return@setOnCheckedChangeListener
            repository.setAutoStartOnBootEnabled(isChecked)
        }
    }

    private fun setupScopeSelector() {
        binding.protectionScopeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingScopeSelector) return@setOnCheckedChangeListener

            val systemWide = checkedId == R.id.systemWideModeRadio
            repository.setAggressiveModeEnabled(systemWide)
            updateScopeUi(systemWide, viewModel.protectedPackages.value?.size ?: 0)
        }
    }

    private fun syncScopeSelector() {
        val systemWide = repository.isAggressiveModeEnabled()
        updatingScopeSelector = true
        binding.protectionScopeGroup.check(
            if (systemWide) R.id.systemWideModeRadio else R.id.appWiseModeRadio
        )
        updatingScopeSelector = false
        updateScopeUi(systemWide, viewModel.protectedPackages.value?.size ?: 0)
    }

    private fun syncAutoStartToggle() {
        updatingBootToggle = true
        binding.autoStartOnBootSwitch.isChecked = repository.isAutoStartOnBootEnabled()
        updatingBootToggle = false
    }

    private fun updateBatteryOptimizationState() {
        val ignored = PermissionUtils.isIgnoringBatteryOptimizations(this)
        val status = getString(
            if (ignored) {
                R.string.battery_optimization_ignored
            } else {
                R.string.battery_optimization_restricted
            }
        )

        binding.batteryOptimizationState.text = getString(
            R.string.battery_optimization_status,
            status
        )
        binding.batteryOptimizationState.setTextColor(
            ContextCompat.getColor(
                this,
                if (ignored) R.color.status_active else R.color.status_inactive
            )
        )
    }

    private fun openBatteryOptimizationGuide() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ),
            Intent(Settings.ACTION_SETTINGS)
        )

        val opened = intents.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null
        }?.let { intent ->
            runCatching { startActivity(intent) }.isSuccess
        } ?: false

        if (!opened) {
            Toast.makeText(this, R.string.unable_to_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateScopeUi(systemWide: Boolean, protectedCount: Int) {
        binding.modeDescription.text = getString(
            if (systemWide) {
                R.string.mode_description_systemwide
            } else {
                R.string.mode_description_appwise
            }
        )

        if (systemWide) {
            binding.selectedCount.text = getString(R.string.systemwide_active_note)
            binding.searchInputLayout.visibility = View.GONE
            binding.noAppsText.visibility = View.GONE
            binding.appsRecyclerView.visibility = View.GONE
        } else {
            binding.selectedCount.text = getString(R.string.selected_count, protectedCount)
            binding.searchInputLayout.visibility = View.VISIBLE
            binding.appsRecyclerView.visibility = View.VISIBLE
            renderAppList()
        }
    }

    private fun renderAppList() {
        if (repository.isAggressiveModeEnabled()) {
            adapter.submit(emptyList(), protectedPackages)
            return
        }

        val query = appSearchQuery.trim()
        val filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { app ->
                app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }

        adapter.submit(filteredApps, protectedPackages)
        binding.noAppsText.visibility = if (filteredApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun observeViewModel() {
        viewModel.apps.observe(this) { apps ->
            allApps = apps
            renderAppList()
        }

        viewModel.protectedPackages.observe(this) { protectedPackages ->
            this.protectedPackages = protectedPackages
            renderAppList()
            updateScopeUi(repository.isAggressiveModeEnabled(), protectedPackages.size)
        }

        viewModel.serviceEnabled.observe(this) { enabled ->
            val statusTextRes = if (enabled) R.string.status_active else R.string.status_inactive
            val statusColor = if (enabled) {
                ContextCompat.getColor(this, R.color.status_active)
            } else {
                ContextCompat.getColor(this, R.color.status_inactive)
            }
            binding.statusValue.text = getString(statusTextRes)
            binding.statusValue.setTextColor(statusColor)
            binding.enableProtectionButton.text = if (enabled) {
                getString(R.string.disable_protection)
            } else {
                getString(R.string.enable_protection)
            }
        }
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

        if (viewModel.serviceEnabled.value == true) {
            return
        }

        if (!PermissionUtils.hasUsageStatsPermission(this)) {
            Toast.makeText(this, R.string.usage_access_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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

    private fun updatePermissionState() {
        val usageGranted = PermissionUtils.hasUsageStatsPermission(this)
        binding.usagePermissionState.text = if (usageGranted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_not_granted)
        }

        binding.usagePermissionState.setTextColor(
            ContextCompat.getColor(
                this,
                if (usageGranted) R.color.status_active else R.color.status_inactive
            )
        )
    }
}
