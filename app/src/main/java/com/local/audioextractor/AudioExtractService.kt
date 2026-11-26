package com.local.audioextractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioExtractService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskQueue = ConcurrentLinkedQueue<Uri>()
    private val totalInBatch = AtomicInteger(0)
    private val completedInBatch = AtomicInteger(0)
    private val cancelled = AtomicBoolean(false)
    private var workerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ExtractionStateHolder.update {
            it.copy(history = ExtractionHistoryStore.load(applicationContext))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                taskQueue.clear()
                ExtractionStateHolder.update {
                    it.copy(
                        isRunning = false,
                        statusMessage = "已取消提取",
                        isError = false
                    )
                }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ENQUEUE -> {
                val uris = extractUrisFromIntent(intent)
                if (uris.isEmpty()) {
                    if (workerJob?.isActive != true) {
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }

                cancelled.set(false)
                if (workerJob?.isActive != true) {
                    totalInBatch.set(0)
                    completedInBatch.set(0)
                }

                uris.forEach { taskQueue.add(it) }
                totalInBatch.addAndGet(uris.size)

                startForegroundWithNotification(
                    title = "准备提取音频…",
                    content = "共 ${totalInBatch.get()} 个文件",
                    progress = 0,
                    indeterminate = true
                )

                startWorkerIfNeeded()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return

        workerJob = serviceScope.launch {
            var successCount = 0
            var failCount = 0
            var lastError = ""
            var lastResult: ExtractionResult? = null

            try {
                while (true) {
                    if (cancelled.get()) break
                    val uri = taskQueue.poll() ?: break

                    val currentIndex = completedInBatch.incrementAndGet()
                    val total = totalInBatch.get().coerceAtLeast(currentIndex)
                    val displayName = AudioExtractorEngine.queryDisplayName(applicationContext, uri)

                    ExtractionStateHolder.update {
                        it.copy(
                            isRunning = true,
                            currentFileName = displayName,
                            progress = 0,
                            currentIndex = currentIndex,
                            totalCount = total,
                            statusMessage = "正在提取 ($currentIndex/$total)：$displayName",
                            isError = false
                        )
                    }

                    updateProgressNotification(
                        title = "正在提取 ($currentIndex/$total)",
                        content = displayName,
                        progress = 0,
                        indeterminate = false
                    )

                    try {
                        val result = AudioExtractorEngine.extract(
                            context = applicationContext,
                            inputUri = uri,
                            isCancelled = { cancelled.get() },
                            onProgress = { pct ->
                                ExtractionStateHolder.update {
                                    it.copy(progress = pct)
                                }
                                updateProgressNotification(
                                    title = "正在提取 ($currentIndex/$total) · $pct%",
                                    content = displayName,
                                    progress = pct,
                                    indeterminate = false
                                )
                            }
                        )

                        successCount++
                        lastResult = result

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
                        ExtractionStateHolder.update { it.copy(history = updatedHistory) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        failCount++
                        lastError = e.message ?: "提取失败"
                    }
                }
            } catch (_: CancellationException) {
                // Handled by ACTION_CANCEL
            } finally {
                if (!cancelled.get()) {
                    val summary = when {
                        failCount == 0 && successCount == 1 && lastResult != null -> {
                            val sec = String.format(Locale.getDefault(), "%.1f", lastResult.elapsedMs / 1000.0)
                            "提取完成：${lastResult.outputName}（耗时 ${sec}s）"
                        }
                        failCount == 0 && successCount > 1 -> {
                            "全部完成：已提取 $successCount 个音频至 Music/提取音频"
                        }
                        successCount > 0 && failCount > 0 -> {
                            "完成 $successCount 个，失败 $failCount 个（$lastError）"
                        }
                        failCount > 0 -> {
                            "提取失败：$lastError"
                        }
                        else -> ""
                    }

                    ExtractionStateHolder.update {
                        it.copy(
                            isRunning = false,
                            progress = if (failCount == 0 && successCount > 0) 100 else 0,
                            statusMessage = summary,
                            isError = failCount > 0 && successCount == 0
                        )
                    }

                    if (summary.isNotEmpty()) {
                        postCompletionNotification(summary, lastResult?.outputUri)
                    }
                }

                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun startForegroundWithNotification(
        title: String,
        content: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val notification = buildProgressNotification(title, content, progress, indeterminate)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID_PROGRESS, notification, serviceType)
    }

    private fun updateProgressNotification(
        title: String,
        content: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(title, content, progress, indeterminate)
        )
    }

    private fun buildProgressNotification(
        title: String,
        content: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, AudioExtractService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_audio)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(openPending)
            .addAction(0, "取消", cancelPending)
            .build()
    }

    private fun postCompletionNotification(message: String, outputUri: Uri?) {
        val contentIntent = if (outputUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputUri, "audio/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(this, MainActivity::class.java)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification_audio)
            .setContentTitle("音频提取")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS,
            "提取进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示后台提取音频的实时进度"
        }
        val resultChannel = NotificationChannel(
            CHANNEL_RESULT,
            "提取结果",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "音频提取完成或失败时通知"
        }
        nm.createNotificationChannel(progressChannel)
        nm.createNotificationChannel(resultChannel)
    }

    private fun extractUrisFromIntent(intent: Intent): List<Uri> {
        val result = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result.add(it) }
            }
        }
        if (result.isEmpty()) {
            intent.data?.let { result.add(it) }
        }
        return result.distinct()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENQUEUE = "com.local.audioextractor.ACTION_ENQUEUE"
        const val ACTION_CANCEL = "com.local.audioextractor.ACTION_CANCEL"

        private const val CHANNEL_PROGRESS = "extract_progress"
        private const val CHANNEL_RESULT = "extract_result"
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_RESULT = 1002

        fun enqueue(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, AudioExtractService::class.java).apply {
                action = ACTION_ENQUEUE
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val clip = ClipData.newUri(context.contentResolver, "videos", uris.first())
                for (i in 1 until uris.size) {
                    clip.addItem(ClipData.Item(uris[i]))
                }
                clipData = clip
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelAll(context: Context) {
            val intent = Intent(context, AudioExtractService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
