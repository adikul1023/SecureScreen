package com.securescreen.app.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.securescreen.app.R
import com.securescreen.app.data.AppInfo
import com.securescreen.app.databinding.ItemAppBinding

class AppSelectionAdapter(
    private val onToggleChanged: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder>() {

    private var apps: List<AppInfo> = emptyList()
    private var protectedPackages: Set<String> = emptySet()

    fun submit(apps: List<AppInfo>, protectedPackages: Set<String>) {
        this.protectedPackages = protectedPackages
        this.apps = apps.sortedWith(
            compareByDescending<AppInfo> { it.packageName in protectedPackages }
                .thenBy { it.appName.lowercase() }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class AppViewHolder(
        private val binding: ItemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppInfo) {
            binding.appIcon.setImageDrawable(item.icon)
            binding.appName.text = item.appName
            binding.packageName.text = item.packageName
            val isProtected = item.packageName in protectedPackages

            binding.protectSwitch.setOnCheckedChangeListener(null)
            binding.protectSwitch.isChecked = isProtected
            binding.protectSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggleChanged(item.packageName, isChecked)
            }

            val context = binding.root.context
            binding.root.strokeColor = ContextCompat.getColor(
                context,
                if (isProtected) R.color.purple_500 else R.color.glass_stroke
            )
            binding.root.strokeWidth = if (isProtected) 2 else 1

            binding.root.setOnClickListener {
                binding.protectSwitch.isChecked = !binding.protectSwitch.isChecked
            }
        }
    }
}
