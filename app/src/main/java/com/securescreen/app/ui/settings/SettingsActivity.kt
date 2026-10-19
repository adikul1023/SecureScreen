package com.securescreen.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.securescreen.app.R
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.databinding.ActivitySettingsBinding
import com.google.android.material.slider.Slider
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.securescreen.app.data.UpdateChecker

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        setupToolbar()
        setupViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val overlayGranted = PermissionUtils.canDrawOverlays(this)
        if (!overlayGranted && repository.isWatermarkEnabled()) {
            repository.setWatermarkEnabled(false)
            binding.watermarkSwitch.isChecked = false
        }
        updateOverlayPermissionState()
        checkForUpdates()
    }

    private fun setupViews() {
        binding.watermarkSwitch.isChecked = repository.isWatermarkEnabled()

        val opacity = repository.getWatermarkOpacityPercent().toFloat()
        binding.opacitySlider.value = opacity
        binding.opacityValue.text = getString(R.string.opacity_value, opacity.toInt())

        updateOverlayPermissionState()
        binding.versionValue.text = getVersionText()

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentLocaleTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
        
        val languageTextRes = when (currentLocaleTag) {
            "en" -> R.string.language_english
            "zh-CN" -> R.string.language_chinese_simplified
            "es" -> R.string.language_spanish
            "fr" -> R.string.language_french
            "ja" -> R.string.language_japanese
            else -> R.string.language_system_default
        }
        binding.languageValue.setText(languageTextRes)
    }

    private fun setupToolbar() {
        binding.topAppBar.setNavigationOnClickListener {
            finish()
        }
        binding.topAppBar.post {
            binding.topAppBar.setContentInsetStartWithNavigation(0)
        }
        binding.topAppBar.subtitle = getString(R.string.about_description)
    }

    private fun setupListeners() {
        binding.languageCard.setOnClickListener {
            showLanguageSelectionDialog()
        }

        binding.watermarkSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !PermissionUtils.canDrawOverlays(this)) {
                openOverlayPermissionScreen()
                binding.watermarkSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            repository.setWatermarkEnabled(isChecked)
        }

        binding.opacitySlider.addOnChangeListener(
            Slider.OnChangeListener { _, value, _ ->
                val opacity = value.toInt()
                binding.opacityValue.text = getString(R.string.opacity_value, opacity)
                repository.setWatermarkOpacityPercent(opacity)
            }
        )

        binding.grantOverlayPermissionButton.setOnClickListener {
            openOverlayPermissionScreen()
        }

        binding.githubButton.setOnClickListener {
            openUrl(GITHUB_URL)
        }
        binding.buyMeACoffeeButton.setOnClickListener {
            openUrl(BUY_ME_A_COFFEE_URL)
        }
        binding.updateButton.setOnClickListener {
            binding.updateButton.tag?.toString()?.let(::openUrl)
        }
    }

    private fun openOverlayPermissionScreen() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun updateOverlayPermissionState() {
        val granted = PermissionUtils.canDrawOverlays(this)
        binding.overlayPermissionState.text = if (granted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_not_granted)
        }
        binding.overlayPermissionState.setTextColor(
            ContextCompat.getColor(
                this,
                if (granted) R.color.status_active else R.color.status_inactive
            )
        )
    }

    private fun checkForUpdates() {
        binding.updateStatus.text = getString(R.string.checking_for_updates)
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                UpdateChecker.check(applicationContext)
            }
            if (update == null) {
                binding.updateStatus.text = getString(R.string.up_to_date)
                binding.updateButton.visibility = android.view.View.GONE
            } else {
                binding.updateStatus.text = getString(R.string.update_available, update.versionName)
                binding.updateButton.tag = update.sourceUrl
                binding.updateButton.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun getVersionText(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

        val versionName = packageInfo.versionName.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }

        return getString(R.string.version_label, versionName, versionCode)
    }

    private fun showLanguageSelectionDialog() {
        val options = arrayOf(
            getString(R.string.language_system_default),
            getString(R.string.language_english),
            getString(R.string.language_chinese_simplified),
            getString(R.string.language_spanish),
            getString(R.string.language_french),
            getString(R.string.language_japanese)
        )
        
        val tags = arrayOf("", "en", "zh-CN", "es", "fr", "ja")
        
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentLocaleTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
        
        val checkedItem = tags.indexOf(currentLocaleTag).takeIf { it >= 0 } ?: 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_section_title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val selectedTag = tags[which]
                val appLocale = if (selectedTag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(selectedTag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/adikul1023/SecureScreen"
        private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/adikul1023"
    }
}
