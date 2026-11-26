package com.local.audioextractor

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.local.audioextractor.databinding.ActivityTrimBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class AudioTrimActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrimBinding
    private lateinit var inputUri: Uri

    private var mediaPlayer: MediaPlayer? = null
    private var playerReady = false
    private var totalDurationMs: Long = 0L

    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var fadeInMs: Long = 0L
    private var fadeOutMs: Long = 0L

    private var playbackPollJob: Job? = null
    private var exportJob: Job? = null
    private val exportCancelled = AtomicBoolean(false)
    private var updatingSliderProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrimBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val parsedUri = intent.data ?: intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (parsedUri == null) {
            Toast.makeText(this, "未找到音频文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        inputUri = parsedUri

        val displayName = intent.getStringExtra(EXTRA_NAME)
            ?: AudioExtractorEngine.queryDisplayName(this, inputUri)
        binding.tvFileName.text = displayName

        setupControls()
        initPlayer()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            if (!playerReady) return@setOnClickListener
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                pausePlayback()
            } else {
                val cur = mp.currentPosition.toLong()
                if (cur < startMs || cur >= endMs - 150L) {
                    mp.seekTo(startMs.toInt())
                }
                startPlayback()
            }
        }

        binding.btnPreviewStart.setOnClickListener {
            if (!playerReady) return@setOnClickListener
            mediaPlayer?.seekTo(startMs.toInt())
            startPlayback()
        }

        binding.btnPreviewEnd.setOnClickListener {
            if (!playerReady) return@setOnClickListener
            val previewWindowMs = max(fadeOutMs + 2_000L, 5_000L)
            val targetMs = max(startMs, endMs - previewWindowMs)
            mediaPlayer?.seekTo(targetMs.toInt())
            startPlayback()
        }

        binding.rangeSlider.addOnChangeListener { slider, _, fromUser ->
            if (!fromUser || updatingSliderProgrammatically || totalDurationMs <= 0L) return@addOnChangeListener
            val vals = slider.values
            if (vals.size == 2) {
                val newStart = (vals[0] * 1000f).toLong().coerceIn(0L, totalDurationMs - MIN_CLIP_MS)
                val newEnd = (vals[1] * 1000f).toLong().coerceIn(newStart + MIN_CLIP_MS, totalDurationMs)
                startMs = newStart
                endMs = newEnd
                refreshTimeLabels()
            }
        }

        binding.btnStartMinus.setOnClickListener { adjustStart(-1_000L) }
        binding.btnStartPlus.setOnClickListener { adjustStart(1_000L) }
        binding.btnStartHere.setOnClickListener {
            if (!playerReady) return@setOnClickListener
            val cur = mediaPlayer?.currentPosition?.toLong() ?: return@setOnClickListener
            setRange(cur.coerceIn(0L, endMs - MIN_CLIP_MS), endMs)
        }

        binding.btnEndMinus.setOnClickListener { adjustEnd(-1_000L) }
        binding.btnEndPlus.setOnClickListener { adjustEnd(1_000L) }
        binding.btnEndHere.setOnClickListener {
            if (!playerReady) return@setOnClickListener
            val cur = mediaPlayer?.currentPosition?.toLong() ?: return@setOnClickListener
            setRange(startMs, cur.coerceIn(startMs + MIN_CLIP_MS, totalDurationMs))
        }

        binding.sliderFadeIn.addOnChangeListener { _, value, _ ->
            fadeInMs = (value * 1000f).toLong()
            refreshFadeLabels()
        }

        binding.sliderFadeOut.addOnChangeListener { _, value, _ ->
            fadeOutMs = (value * 1000f).toLong()
            refreshFadeLabels()
        }

        binding.btnExport.setOnClickListener { startExport() }
        binding.btnCancelExport.setOnClickListener {
            exportCancelled.set(true)
            exportJob?.cancel()
        }

        setControlsEnabled(false)
        refreshFadeLabels()
    }

    private fun initPlayer() {
        try {
            val mp = MediaPlayer()
            mp.setDataSource(this, inputUri)
            mp.setOnPreparedListener { prepared ->
                val dur = prepared.duration.toLong()
                if (dur < MIN_CLIP_MS) {
                    Toast.makeText(this, "音频时长太短，无法剪辑", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setOnPreparedListener
                }
                totalDurationMs = dur
                startMs = 0L
                endMs = dur
                playerReady = true

                updatingSliderProgrammatically = true
                binding.rangeSlider.valueFrom = 0f
                binding.rangeSlider.valueTo = (dur / 1000f).coerceAtLeast(1f)
                binding.rangeSlider.values = listOf(0f, binding.rangeSlider.valueTo)
                updatingSliderProgrammatically = false

                setControlsEnabled(true)
                refreshTimeLabels()
                binding.tvPlaybackTime.text = "当前 ${formatTimeMs(0L)}"
            }
            mp.setOnCompletionListener {
                pausePlayback()
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "无法读取该音频文件（可能已被移动或删除）", Toast.LENGTH_SHORT).show()
                finish()
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Toast.makeText(this, "打开音频失败：${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startPlayback() {
        val mp = mediaPlayer ?: return
        mp.setVolume(1f, 1f)
        mp.start()
        binding.btnPlayPause.text = "暂停"

        playbackPollJob?.cancel()
        playbackPollJob = lifecycleScope.launch {
            while (isActive && mp.isPlaying) {
                val posMs = mp.currentPosition.toLong()
                binding.tvPlaybackTime.text = "当前 ${formatTimeMs(posMs)}"

                if (posMs >= endMs) {
                    pausePlayback()
                    mp.seekTo(startMs.toInt())
                    binding.tvPlaybackTime.text = "当前 ${formatTimeMs(startMs)}"
                    break
                }

                // Real-time preview of fade-in / fade-out volume curve
                val relMs = (posMs - startMs).coerceAtLeast(0L)
                val clipDurMs = (endMs - startMs).coerceAtLeast(1L)
                var gain = 1.0f
                if (fadeInMs > 0L && relMs < fadeInMs) {
                    val r = (relMs.toFloat() / fadeInMs.toFloat()).coerceIn(0f, 1f)
                    gain *= (r * r)
                }
                if (fadeOutMs > 0L && relMs > clipDurMs - fadeOutMs) {
                    val rem = ((clipDurMs - relMs).toFloat() / fadeOutMs.toFloat()).coerceIn(0f, 1f)
                    gain *= (rem * rem)
                }
                mp.setVolume(gain, gain)

                delay(50L)
            }
        }
    }

    private fun pausePlayback() {
        playbackPollJob?.cancel()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
            mediaPlayer?.setVolume(1f, 1f)
        } catch (_: Exception) {
        }
        binding.btnPlayPause.text = "播放"
    }

    private fun adjustStart(deltaMs: Long) {
        if (!playerReady) return
        val newStart = (startMs + deltaMs).coerceIn(0L, endMs - MIN_CLIP_MS)
        setRange(newStart, endMs)
    }

    private fun adjustEnd(deltaMs: Long) {
        if (!playerReady) return
        val newEnd = (endMs + deltaMs).coerceIn(startMs + MIN_CLIP_MS, totalDurationMs)
        setRange(startMs, newEnd)
    }

    private fun setRange(newStartMs: Long, newEndMs: Long) {
        startMs = newStartMs
        endMs = newEndMs
        updatingSliderProgrammatically = true
        val maxSec = binding.rangeSlider.valueTo
        val sSec = (startMs / 1000f).coerceIn(0f, maxSec)
        val eSec = (endMs / 1000f).coerceIn(sSec, maxSec)
        binding.rangeSlider.values = listOf(sSec, eSec)
        updatingSliderProgrammatically = false
        refreshTimeLabels()
    }

    private fun refreshTimeLabels() {
        binding.tvStartTime.text = "起点 ${formatTimeMs(startMs)}"
        binding.tvEndTime.text = "终点 ${formatTimeMs(endMs)}"
        binding.tvClipDuration.text = "选区 ${formatTimeMs((endMs - startMs).coerceAtLeast(0L))}"
    }

    private fun refreshFadeLabels() {
        binding.tvFadeInValue.text = formatFadeText(fadeInMs)
        binding.tvFadeOutValue.text = formatFadeText(fadeOutMs)

        val hasFade = fadeInMs > 0L || fadeOutMs > 0L
        binding.btnExport.text = if (hasFade) {
            "导出音频（含渐强/渐弱）"
        } else {
            "导出音频（无损截取）"
        }
    }

    private fun formatFadeText(ms: Long): String {
        if (ms <= 0L) return "0.0 秒（关闭）"
        return String.format(Locale.getDefault(), "%.1f 秒", ms / 1000.0)
    }

    private fun startExport() {
        if (!playerReady || exportJob?.isActive == true) return
        pausePlayback()

        val params = AudioEditParams(
            startMs = startMs,
            endMs = endMs,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs
        )

        exportCancelled.set(false)
        setControlsEnabled(false)
        binding.cardExportProgress.visibility = View.VISIBLE
        binding.exportProgressBar.progress = 0
        binding.tvExportPercent.text = "0%"
        binding.tvExportStatus.text = if (fadeInMs > 0L || fadeOutMs > 0L) {
            "正在处理渐强/渐弱并导出…"
        } else {
            "正在无损截取导出…"
        }

        exportJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AudioEditEngine.edit(
                        context = applicationContext,
                        inputUri = inputUri,
                        params = params,
                        isCancelled = { exportCancelled.get() },
                        onProgress = { pct ->
                            launch(Dispatchers.Main) {
                                binding.exportProgressBar.progress = pct
                                binding.tvExportPercent.text = "$pct%"
                            }
                        }
                    )
                }

                val updatedHistory = ExtractionHistoryStore.add(
                    applicationContext,
                    HistoryItem(
                        outputUriString = result.outputUri.toString(),
                        outputName = result.outputName,
                        outputSizeBytes = result.outputSizeBytes,
                        elapsedMs = result.elapsedMs,
                        timestampMs = System.currentTimeMillis()
                    )
                )
                val sec = String.format(Locale.getDefault(), "%.1f", result.elapsedMs / 1000.0)
                ExtractionStateHolder.update {
                    it.copy(
                        history = updatedHistory,
                        statusMessage = "剪辑完成：${result.outputName}（耗时 ${sec}s）",
                        isError = false
                    )
                }

                Toast.makeText(
                    this@AudioTrimActivity,
                    "已保存至 Music/提取音频/${result.outputName}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (_: CancellationException) {
                Toast.makeText(this@AudioTrimActivity, "已取消导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@AudioTrimActivity,
                    "导出失败：${e.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.cardExportProgress.visibility = View.GONE
                setControlsEnabled(playerReady)
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnPreviewStart.isEnabled = enabled
        binding.btnPreviewEnd.isEnabled = enabled
        binding.rangeSlider.isEnabled = enabled
        binding.btnStartMinus.isEnabled = enabled
        binding.btnStartPlus.isEnabled = enabled
        binding.btnStartHere.isEnabled = enabled
        binding.btnEndMinus.isEnabled = enabled
        binding.btnEndPlus.isEnabled = enabled
        binding.btnEndHere.isEnabled = enabled
        binding.sliderFadeIn.isEnabled = enabled
        binding.sliderFadeOut.isEnabled = enabled
        binding.btnExport.isEnabled = enabled
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        exportCancelled.set(true)
        exportJob?.cancel()
        playbackPollJob?.cancel()
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_NAME = "extra_name"
        private const val MIN_CLIP_MS = 1_000L

        fun launch(context: Context, uri: Uri, displayName: String? = null) {
            val intent = Intent(context, AudioTrimActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_URI, uri.toString())
                if (!displayName.isNullOrBlank()) {
                    putExtra(EXTRA_NAME, displayName)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
