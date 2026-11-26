package com.local.audioextractor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.local.audioextractor.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyAdapter: HistoryAdapter

    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            AudioExtractService.enqueue(this, uris)
        }
    }

    private val pickAudioToTrimLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            AudioTrimActivity.launch(this, uri)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Even if denied, extraction works normally in foreground service.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()

        historyAdapter = HistoryAdapter(
            onPlayClick = { item -> openAudio(item) },
            onTrimClick = { item ->
                AudioTrimActivity.launch(this, Uri.parse(item.outputUriString), item.outputName)
            },
            onShareClick = { item -> shareAudio(item) }
        )

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }

        binding.btnPickVideos.setOnClickListener {
            pickVideosLauncher.launch(arrayOf("video/*"))
        }

        binding.btnPickAudioToTrim.setOnClickListener {
            pickAudioToTrimLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnCancel.setOnClickListener {
            AudioExtractService.cancelAll(this)
        }

        binding.btnClearHistory.setOnClickListener {
            ExtractionHistoryStore.clear(this)
            ExtractionStateHolder.update { it.copy(history = emptyList()) }
            Toast.makeText(this, "已清空历史记录（音频文件仍保留在手机中）", Toast.LENGTH_SHORT).show()
        }

        ExtractionStateHolder.update {
            it.copy(history = ExtractionHistoryStore.load(applicationContext))
        }

        lifecycleScope.launch {
            ExtractionStateHolder.state.collect { state ->
                renderState(state)
            }
        }
    }

    private fun renderState(state: ExtractionUiState) {
        binding.cardProgress.visibility = if (state.isRunning) View.VISIBLE else View.GONE
        if (state.isRunning) {
            binding.tvProgressTitle.text = "正在提取 (${state.currentIndex}/${state.totalCount})"
            binding.tvProgressFile.text = state.currentFileName
            binding.progressBar.progress = state.progress
            binding.tvProgressPercent.text = "${state.progress}%"
        }

        if (!state.isRunning && state.statusMessage.isNotBlank()) {
            binding.tvStatusBanner.visibility = View.VISIBLE
            binding.tvStatusBanner.text = state.statusMessage
            val bgColor = ContextCompat.getColor(
                this,
                if (state.isError) R.color.status_error_bg else R.color.status_ok_bg
            )
            val textColor = ContextCompat.getColor(
                this,
                if (state.isError) R.color.status_error_text else R.color.status_ok_text
            )
            binding.tvStatusBanner.setBackgroundColor(bgColor)
            binding.tvStatusBanner.setTextColor(textColor)
        } else {
            binding.tvStatusBanner.visibility = View.GONE
        }

        historyAdapter.submitList(state.history)
        val hasHistory = state.history.isNotEmpty()
        binding.tvEmptyHistory.visibility = if (hasHistory) View.GONE else View.VISIBLE
        binding.btnClearHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
    }

    private fun openAudio(item: HistoryItem) {
        val uri = Uri.parse(item.outputUriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "选择播放器打开"))
        } catch (_: Exception) {
            Toast.makeText(this, "未找到可用的音频播放器，或文件已被移动/删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAudio(item: HistoryItem) {
        val uri = Uri.parse(item.outputUriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "分享音频"))
        } catch (_: Exception) {
            Toast.makeText(this, "无法分享该音频文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
