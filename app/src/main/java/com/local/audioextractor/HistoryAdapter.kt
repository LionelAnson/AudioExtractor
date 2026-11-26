package com.local.audioextractor

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.local.audioextractor.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onPlayClick: (HistoryItem) -> Unit,
    private val onShareClick: (HistoryItem) -> Unit
) : ListAdapter<HistoryItem, HistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            val ctx = binding.root.context
            binding.tvFileName.text = item.outputName

            val sizeText = if (item.outputSizeBytes > 0) {
                Formatter.formatShortFileSize(ctx, item.outputSizeBytes)
            } else {
                "未知大小"
            }
            val elapsedSec = String.format(Locale.getDefault(), "%.1fs", item.elapsedMs / 1000.0)
            val timeText = TIME_FORMAT.format(Date(item.timestampMs))
            binding.tvMeta.text = "$sizeText · 耗时 $elapsedSec · $timeText"

            binding.root.setOnClickListener { onPlayClick(item) }
            binding.btnShare.setOnClickListener { onShareClick(item) }
        }
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryItem>() {
            override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return oldItem.outputUriString == newItem.outputUriString &&
                    oldItem.timestampMs == newItem.timestampMs
            }

            override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
