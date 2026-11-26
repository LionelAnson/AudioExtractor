package com.local.audioextractor

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

data class ExtractionResult(
    val outputUri: Uri,
    val outputName: String,
    val relativePath: String,
    val outputSizeBytes: Long,
    val elapsedMs: Long,
    val audioMime: String
)

class UnsupportedAudioFormatException(message: String) : Exception(message)

object AudioExtractorEngine {

    const val OUTPUT_SUBFOLDER = "提取音频"
    val OUTPUT_RELATIVE_PATH: String = "${Environment.DIRECTORY_MUSIC}/$OUTPUT_SUBFOLDER"

    private const val DEFAULT_BUFFER_SIZE = 512 * 1024 // 512 KB
    private const val MAX_BUFFER_SIZE = 8 * 1024 * 1024 // 8 MB
    private const val PROGRESS_THROTTLE_MS = 180L

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "video_$stamp.mp4"
    }

    fun extract(
        context: Context,
        inputUri: Uri,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int) -> Unit
    ): ExtractionResult {
        val startMs = SystemClock.elapsedRealtime()
        val sourceName = queryDisplayName(context, inputUri)
        val baseName = stripExtension(sourceName)

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            extractor.setDataSource(context, inputUri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    audioMime = mime
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw UnsupportedAudioFormatException("未在视频中检测到音频轨道")
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$baseName.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, OUTPUT_RELATIVE_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            outputUri = resolver.insert(collection, values)
                ?: throw IOException("无法在 Music/$OUTPUT_SUBFOLDER 创建音频文件")

            pfd = resolver.openFileDescriptor(outputUri, "rw")
                ?: throw IOException("无法打开输出文件描述符")

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val muxerTrackIndex = try {
                muxer.addTrack(audioFormat)
            } catch (e: Exception) {
                throw UnsupportedAudioFormatException(
                    "音轨格式 ($audioMime) 不支持无损直出为 M4A"
                )
            }

            muxer.start()
            muxerStarted = true

            val initialBufSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    .coerceIn(DEFAULT_BUFFER_SIZE, MAX_BUFFER_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }

            var buffer = ByteBuffer.allocate(initialBufSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var lastPtsUs = -1L
            var lastProgressEmitMs = 0L

            onProgress(0)

            while (true) {
                if (isCancelled()) {
                    throw CancellationException("任务已取消")
                }

                val sampleSize = try {
                    extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    val newCap = (buffer.capacity() * 2).coerceAtMost(MAX_BUFFER_SIZE)
                    if (newCap <= buffer.capacity()) throw e
                    buffer = ByteBuffer.allocate(newCap)
                    extractor.readSampleData(buffer, 0)
                }

                if (sampleSize < 0) {
                    break
                }

                val rawPtsUs = extractor.sampleTime
                if (rawPtsUs < 0) {
                    extractor.advance()
                    continue
                }

                val safePtsUs = if (lastPtsUs >= 0 && rawPtsUs < lastPtsUs) {
                    lastPtsUs + 1L
                } else {
                    rawPtsUs
                }
                lastPtsUs = safePtsUs

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = safePtsUs
                bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()

                if (durationUs > 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressEmitMs >= PROGRESS_THROTTLE_MS) {
                        lastProgressEmitMs = now
                        val pct = ((safePtsUs * 100L) / durationUs).toInt().coerceIn(0, 99)
                        onProgress(pct)
                    }
                }
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            pfd.close()
            pfd = null

            val doneValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(outputUri, doneValues, null, null)

            val (finalName, finalSize) = queryOutputInfo(context, outputUri, "$baseName.m4a")
            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            onProgress(100)

            return ExtractionResult(
                outputUri = outputUri,
                outputName = finalName,
                relativePath = OUTPUT_RELATIVE_PATH,
                outputSizeBytes = finalSize,
                elapsedMs = elapsedMs,
                audioMime = audioMime
            )
        } catch (t: Throwable) {
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                pfd?.close()
            } catch (_: Throwable) {
            }
            if (outputUri != null) {
                try {
                    context.contentResolver.delete(outputUri, null, null)
                } catch (_: Throwable) {
                }
            }
            throw t
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun mapSampleFlags(extractorFlags: Int): Int {
        var codecFlags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private fun stripExtension(fileName: String): String {
        val clean = fileName.trim()
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        return stem.ifBlank { "audio_${System.currentTimeMillis()}" }
    }

    private fun queryOutputInfo(context: Context, uri: Uri, fallbackName: String): Pair<String, Long> {
        var name = fallbackName
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }
}
