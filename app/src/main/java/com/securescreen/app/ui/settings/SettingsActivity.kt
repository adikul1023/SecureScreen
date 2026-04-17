package com.securescreen.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.securescreen.app.R
import com.securescreen.app.data.AppRepository
import com.securescreen.app.data.PermissionUtils
import com.securescreen.app.databinding.ActivitySettingsBinding
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: AppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
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
    }

    private fun setupViews() {
        binding.watermarkSwitch.isChecked = repository.isWatermarkEnabled()

        val opacity = repository.getWatermarkOpacityPercent().toFloat()
        binding.opacitySlider.value = opacity
        binding.opacityValue.text = getString(R.string.opacity_value, opacity.toInt())

        updateOverlayPermissionState()
    }

    private fun setupListeners() {
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
}
