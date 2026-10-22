package com.securescreen.app.ui.logs

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.securescreen.app.R
import com.securescreen.app.data.log.DiagnosticReportGenerator
import com.securescreen.app.data.log.SecurityEvent
import com.securescreen.app.data.log.SecurityEventSeverity
import com.securescreen.app.data.log.SecurityLogDatabaseHelper
import com.securescreen.app.data.log.SecurityLogger
import com.securescreen.app.databinding.ActivitySecurityLogsBinding
import com.securescreen.app.databinding.DialogDiagnosticReportBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecurityLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityLogsBinding
    private lateinit var dbHelper: SecurityLogDatabaseHelper
    private lateinit var adapter: SecurityLogsAdapter

    private var currentSeverityFilter: SecurityEventSeverity? = null
    private var currentSearchQuery: String = ""

    private val logsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SecurityLogger.ACTION_SECURITY_LOGS_UPDATED) {
                loadLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = SecurityLogDatabaseHelper(applicationContext)

        setupTopBar()
        setupRecyclerView()
        setupFilters()
        setupSearch()
        loadLogs()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SecurityLogger.ACTION_SECURITY_LOGS_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logsUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logsUpdateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(logsUpdateReceiver) }
    }

    private fun setupTopBar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
        binding.topAppBar.post {
            binding.topAppBar.setContentInsetStartWithNavigation(0)
        }

        binding.topAppBar.inflateMenu(R.menu.menu_security_logs)
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_export_report -> {
                    showDiagnosticReportDialog()
                    true
                }
                R.id.action_clear_logs -> {
                    showClearLogsConfirmationDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = SecurityLogsAdapter { event ->
            showEventDetailsDialog(event)
        }
        binding.logsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.logsRecyclerView.adapter = adapter
    }

    private fun setupFilters() {
        binding.severityChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentSeverityFilter = when (checkedIds.firstOrNull()) {
                R.id.filterErrorsChip -> SecurityEventSeverity.ERROR
                R.id.filterWarningsChip -> SecurityEventSeverity.WARNING
                R.id.filterInfoChip -> SecurityEventSeverity.INFO
                else -> null
            }
            loadLogs()
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener { text ->
            currentSearchQuery = text?.toString().orEmpty()
            loadLogs()
        }

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val filter = currentSeverityFilter
            val query = currentSearchQuery

            val (events, counts) = withContext(Dispatchers.IO) {
                val list = dbHelper.getEvents(
                    limit = 500,
                    severityFilter = filter,
                    query = query
                )
                val countMap = dbHelper.getCountsBySeverity()
                val total = dbHelper.getEventCount()
                list to (countMap to total)
            }

            val countMap = counts.first
            val totalCount = counts.second

            binding.totalCountText.text = getString(R.string.total_events_count, totalCount)
            val errorCount = countMap[SecurityEventSeverity.ERROR] ?: 0
            val warningCount = countMap[SecurityEventSeverity.WARNING] ?: 0

            binding.errorCountBadge.text = getString(R.string.error_count_badge, errorCount)
            binding.warningCountBadge.text = getString(R.string.warning_count_badge, warningCount)

            adapter.submitList(events)

            if (events.isEmpty()) {
                binding.noLogsLayout.visibility = android.view.View.VISIBLE
                binding.logsRecyclerView.visibility = android.view.View.GONE
            } else {
                binding.noLogsLayout.visibility = android.view.View.GONE
                binding.logsRecyclerView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun showDiagnosticReportDialog() {
        lifecycleScope.launch {
            val allEvents = withContext(Dispatchers.IO) {
                dbHelper.getEvents(limit = 1000)
            }

            val report = withContext(Dispatchers.Default) {
                DiagnosticReportGenerator.generateRedactedReport(this@SecurityLogsActivity, allEvents)
            }

            val dialogBinding = DialogDiagnosticReportBinding.inflate(layoutInflater)
            dialogBinding.reportContentText.text = report

            val dialog = MaterialAlertDialogBuilder(this@SecurityLogsActivity)
                .setView(dialogBinding.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialogBinding.copyReportButton.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SecureScreen Diagnostic Report", report)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@SecurityLogsActivity, R.string.report_copied, Toast.LENGTH_SHORT).show()
            }

            dialogBinding.shareReportButton.setOnClickListener {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, report)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_report_subject))
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share_report)))
            }

            dialog.show()
        }
    }

    private fun showClearLogsConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_logs_confirm_title)
            .setMessage(R.string.clear_logs_confirm_message)
            .setPositiveButton(R.string.clear_logs_button) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        dbHelper.clearLogs()
                    }
                    loadLogs()
                    Toast.makeText(this@SecurityLogsActivity, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEventDetailsDialog(event: SecurityEvent) {
        val details = buildString {
            append("Timestamp: ${java.util.Date(event.timestamp)}\n")
            append("Severity: ${event.severity.name}\n")
            append("Type: ${event.eventType.name}\n\n")
            append("Details: ${event.details ?: "None"}\n")
            if (!event.packageName.isNullOrBlank()) {
                append("Target Package: ${event.packageName}\n")
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(event.title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy_to_clipboard) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Security Event", details)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
