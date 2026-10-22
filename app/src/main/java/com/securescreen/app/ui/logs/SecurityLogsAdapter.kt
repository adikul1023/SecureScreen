package com.securescreen.app.ui.logs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.securescreen.app.R
import com.securescreen.app.data.log.SecurityEvent
import com.securescreen.app.data.log.SecurityEventSeverity
import com.securescreen.app.databinding.ItemSecurityLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SecurityLogsAdapter(
    private val onItemClick: ((SecurityEvent) -> Unit)? = null
) : RecyclerView.Adapter<SecurityLogsAdapter.LogViewHolder>() {

    private var items: List<SecurityEvent> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<SecurityEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemSecurityLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class LogViewHolder(
        private val binding: ItemSecurityLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(event: SecurityEvent) {
            val context = binding.root.context

            binding.titleText.text = event.title
            binding.detailsText.text = event.details ?: ""
            binding.detailsText.visibility = if (event.details.isNullOrBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }

            binding.timestampText.text = dateFormat.format(Date(event.timestamp))

            when (event.severity) {
                SecurityEventSeverity.ERROR -> {
                    binding.severityBadge.text = "ERROR"
                    binding.severityBadge.setTextColor(
                        ContextCompat.getColor(context, R.color.error_red)
                    )
                }
                SecurityEventSeverity.WARNING -> {
                    binding.severityBadge.text = "WARN"
                    binding.severityBadge.setTextColor(
                        ContextCompat.getColor(context, R.color.warning_amber)
                    )
                }
                SecurityEventSeverity.INFO -> {
                    binding.severityBadge.text = "INFO"
                    binding.severityBadge.setTextColor(
                        ContextCompat.getColor(context, R.color.status_active)
                    )
                }
            }

            binding.root.setOnClickListener {
                onItemClick?.invoke(event)
            }
        }
    }
}
