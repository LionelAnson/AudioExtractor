package com.local.audioextractor

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class AudioEditParams(
    val startMs: Long,
    val endMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long
)

object AudioEditEngine {

    private const val DEFAULT_BUFFER_SIZE = 512 * 1024
    private const val MAX_BUFFER_SIZE = 8 * 1024 * 1024
    private const val PROGRESS_THROTTLE_MS = 160L
    private const val CODEC_TIMEOUT_US = 10_000L

    fun edit(
        context: Context,
        inputUri: Uri,
        params: AudioEditParams,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int) -> Unit
    ): ExtractionResult {
        require(params.endMs > params.startMs) { "结束时间必须大于开始时间" }

        val needsFade = params.fadeInMs > 0L || params.fadeOutMs > 0L
        if (!needsFade) {
            try {
                return remuxTrim(context, inputUri, params, isCancelled, onProgress)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: UnsupportedAudioFormatException) {
                // Fall back to decode + encode pipeline for non-M4A source audio (e.g. MP3/WAV).
            }
        }

        return transcodeWithFade(context, inputUri, params, isCancelled, onProgress)
    }

    private fun remuxTrim(
        context: Context,
        inputUri: Uri,
        params: AudioEditParams,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int) -> Unit
    ): ExtractionResult {
        val startElapsed = SystemClock.elapsedRealtime()
        val sourceName = AudioExtractorEngine.queryDisplayName(context, inputUri)
        val outputBaseName = "${stripExtension(sourceName)}_剪辑"

        val startUs = params.startMs * 1000L
        val endUs = params.endMs * 1000L
        val clipDurationUs = (endUs - startUs).coerceAtLeast(1L)

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            extractor.setDataSource(context, inputUri, null)

            val (audioTrackIndex, audioFormat, audioMime) = findAudioTrack(extractor)
            extractor.selectTrack(audioTrackIndex)

            val resolver = context.contentResolver
            outputUri = createPendingOutputUri(context, "$outputBaseName.m4a")
            pfd = resolver.openFileDescriptor(outputUri, "rw")
                ?: throw IOException("无法打开输出文件描述符")

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = try {
                muxer.addTrack(audioFormat)
            } catch (_: Exception) {
                throw UnsupportedAudioFormatException("音轨格式 ($audioMime) 需重新编码")
            }

            muxer.start()
            muxerStarted = true

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val initialBufSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    .coerceIn(DEFAULT_BUFFER_SIZE, MAX_BUFFER_SIZE)
            } else {
                DEFAULT_BUFFER_SIZE
            }

            var buffer = ByteBuffer.allocate(initialBufSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstPtsUs = -1L
            var lastOutPtsUs = -1L
            var lastProgressMs = 0L

            onProgress(0)

            while (true) {
                if (isCancelled()) throw CancellationException("已取消")

                val sampleSize = try {
                    extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    val newCap = (buffer.capacity() * 2).coerceAtMost(MAX_BUFFER_SIZE)
                    if (newCap <= buffer.capacity()) throw e
                    buffer = ByteBuffer.allocate(newCap)
                    extractor.readSampleData(buffer, 0)
                }

                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) {
                    extractor.advance()
                    continue
                }
                if (sampleTimeUs < startUs) {
                    extractor.advance()
                    continue
                }
                if (sampleTimeUs > endUs) {
                    break
                }

                if (firstPtsUs < 0) {
                    firstPtsUs = sampleTimeUs
                }

                var outPtsUs = (sampleTimeUs - firstPtsUs).coerceAtLeast(0L)
                if (lastOutPtsUs >= 0 && outPtsUs <= lastOutPtsUs) {
                    outPtsUs = lastOutPtsUs + 1L
                }
                lastOutPtsUs = outPtsUs

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = outPtsUs
                bufferInfo.flags = mapSampleFlags(extractor.sampleFlags)

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()

                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressMs >= PROGRESS_THROTTLE_MS) {
                    lastProgressMs = now
                    val pct = ((outPtsUs * 100L) / clipDurationUs).toInt().coerceIn(0, 99)
                    onProgress(pct)
                }
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            pfd.close()
            pfd = null

            finalizePendingUri(context, outputUri)
            val (finalName, finalSize) = queryOutputInfo(context, outputUri, "$outputBaseName.m4a")
            val elapsedMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
            onProgress(100)

            return ExtractionResult(
                outputUri = outputUri,
                outputName = finalName,
                relativePath = AudioExtractorEngine.OUTPUT_RELATIVE_PATH,
                outputSizeBytes = finalSize,
                elapsedMs = elapsedMs,
                audioMime = audioMime
            )
        } catch (t: Throwable) {
            cleanupOnFailure(context, muxer, muxerStarted, pfd, outputUri)
            throw t
        } finally {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun transcodeWithFade(
        context: Context,
        inputUri: Uri,
        params: AudioEditParams,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int) -> Unit
    ): ExtractionResult {
        val startElapsed = SystemClock.elapsedRealtime()
        val sourceName = AudioExtractorEngine.queryDisplayName(context, inputUri)
        val outputBaseName = "${stripExtension(sourceName)}_剪辑"

        val startUs = params.startMs * 1000L
        val endUs = params.endMs * 1000L
        val clipDurationUs = (endUs - startUs).coerceAtLeast(1L)

        // Clamp fade durations so fadeIn + fadeOut never exceeds clip duration.
        var fadeInUs = params.fadeInMs.coerceAtLeast(0L) * 1000L
        var fadeOutUs = params.fadeOutMs.coerceAtLeast(0L) * 1000L
        if (fadeInUs + fadeOutUs > clipDurationUs) {
            val scale = clipDurationUs.toDouble() / (fadeInUs + fadeOutUs).toDouble()
            fadeInUs = (fadeInUs * scale).toLong()
            fadeOutUs = (fadeOutUs * scale).toLong()
        }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerTrackIndex = -1
        var outputUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            val (audioTrackIndex, inputFormat, audioMime) = findAudioTrack(extractor)
            extractor.selectTrack(audioTrackIndex)

            val sourceBitRate = if (inputFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_BIT_RATE).coerceIn(96_000, 320_000)
            } else {
                192_000
            }

            decoder = MediaCodec.createDecoderByType(audioMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val resolver = context.contentResolver
            outputUri = createPendingOutputUri(context, "$outputBaseName.m4a")
            pfd = resolver.openFileDescriptor(outputUri, "rw")
                ?: throw IOException("无法打开输出文件描述符")
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Seek slightly before startUs so AAC decoder priming frames settle cleanly.
            val seekTargetUs = (startUs - 200_000L).coerceAtLeast(0L)
            extractor.seekTo(seekTargetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }

            val decodeInfo = MediaCodec.BufferInfo()
            val encodeInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderInputEosSent = false
            var encoderOutputDone = false

            var totalEncodedFrames = 0L
            var lastMuxerPtsUs = -1L
            var lastProgressMs = 0L

            fun ensureEncoderStarted() {
                if (encoder != null) return
                val encFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channelCount
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, sourceBitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }

            fun drainEncoder(waitForEos: Boolean) {
                val enc = encoder ?: return
                val mx = muxer ?: return
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(encodeInfo, if (waitForEos) CODEC_TIMEOUT_US else 0L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!waitForEos) return
                            if (isCancelled()) throw CancellationException("已取消")
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrackIndex = mx.addTrack(enc.outputFormat)
                                mx.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = enc.getOutputBuffer(outIdx)
                            val isConfig = (encodeInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig && encodeInfo.size > 0 && outBuf != null && muxerStarted) {
                                outBuf.position(encodeInfo.offset)
                                outBuf.limit(encodeInfo.offset + encodeInfo.size)
                                if (lastMuxerPtsUs >= 0 && encodeInfo.presentationTimeUs <= lastMuxerPtsUs) {
                                    encodeInfo.presentationTimeUs = lastMuxerPtsUs + 1L
                                }
                                lastMuxerPtsUs = encodeInfo.presentationTimeUs
                                mx.writeSampleData(muxerTrackIndex, outBuf, encodeInfo)
                            }
                            val isEos = (encodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(outIdx, false)
                            if (isEos) {
                                encoderOutputDone = true
                                return
                            }
                        }
                    }
                }
            }

            fun feedPcmToEncoder(pcmBytes: ByteArray, length: Int) {
                if (length <= 0) return
                ensureEncoderStarted()
                val enc = encoder!!
                val bytesPerFrame = channelCount * 2

                var offset = 0
                while (offset < length) {
                    if (isCancelled()) throw CancellationException("已取消")
                    val inIdx = enc.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = enc.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val maxCopy = (inBuf.remaining() / bytesPerFrame) * bytesPerFrame
                        if (maxCopy <= 0) throw IOException("编码器输入缓冲区过小")
                        val toCopy = minOf(length - offset, maxCopy)
                        inBuf.put(pcmBytes, offset, toCopy)
                        val ptsUs = totalEncodedFrames * 1_000_000L / sampleRate
                        enc.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                        totalEncodedFrames += (toCopy / bytesPerFrame)
                        offset += toCopy
                    }
                    drainEncoder(false)
                }
            }

            onProgress(0)

            while (!decoderDone) {
                if (isCancelled()) throw CancellationException("已取消")

                // 1. Feed compressed samples into decoder
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleSize < 0 || sampleTimeUs > endUs + 200_000L) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Drain PCM from decoder, trim to [startUs, endUs], apply fade-in/out, feed encoder
                val outIdx = decoder.dequeueOutputBuffer(decodeInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            val enc = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            if (enc != AudioFormat.ENCODING_PCM_16BIT) {
                                throw UnsupportedAudioFormatException("暂不支持非 16-bit PCM 解码格式")
                            }
                        }
                    }

                    outIdx >= 0 -> {
                        val isEos = (decodeInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val outBuf = decoder.getOutputBuffer(outIdx)

                        if (decodeInfo.size > 0 && outBuf != null) {
                            val bytesPerFrame = channelCount * 2
                            val frameCount = decodeInfo.size / bytesPerFrame
                            val chunkStartUs = decodeInfo.presentationTimeUs
                            val chunkDurationUs = frameCount * 1_000_000L / sampleRate
                            val chunkEndUs = chunkStartUs + chunkDurationUs

                            if (chunkEndUs > startUs && chunkStartUs < endUs) {
                                outBuf.position(decodeInfo.offset)
                                outBuf.limit(decodeInfo.offset + decodeInfo.size)

                                val firstFrame = if (chunkStartUs < startUs) {
                                    (((startUs - chunkStartUs) * sampleRate) / 1_000_000L)
                                        .toInt()
                                        .coerceIn(0, frameCount)
                                } else {
                                    0
                                }

                                val lastFrameExclusive = if (chunkEndUs > endUs) {
                                    (((endUs - chunkStartUs) * sampleRate) / 1_000_000L)
                                        .toInt()
                                        .coerceIn(firstFrame, frameCount)
                                } else {
                                    frameCount
                                }

                                val keepFrames = lastFrameExclusive - firstFrame
                                if (keepFrames > 0) {
                                    val keepBytes = keepFrames * bytesPerFrame
                                    val pcmData = ByteArray(keepBytes)
                                    outBuf.position(decodeInfo.offset + firstFrame * bytesPerFrame)
                                    outBuf.get(pcmData, 0, keepBytes)

                                    applyFadePcm16(
                                        pcmBytes = pcmData,
                                        frameCount = keepFrames,
                                        channelCount = channelCount,
                                        sampleRate = sampleRate,
                                        firstFrameRelUs = totalEncodedFrames * 1_000_000L / sampleRate,
                                        clipDurationUs = clipDurationUs,
                                        fadeInUs = fadeInUs,
                                        fadeOutUs = fadeOutUs
                                    )

                                    feedPcmToEncoder(pcmData, keepBytes)

                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastProgressMs >= PROGRESS_THROTTLE_MS) {
                                        lastProgressMs = now
                                        val processedUs = totalEncodedFrames * 1_000_000L / sampleRate
                                        val pct = ((processedUs * 100L) / clipDurationUs)
                                            .toInt()
                                            .coerceIn(0, 99)
                                        onProgress(pct)
                                    }
                                }
                            }

                            if (chunkEndUs >= endUs) {
                                decoderDone = true
                            }
                        }

                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            decoderDone = true
                        }
                    }
                }
            }

            // 3. Signal EOS to encoder and flush remaining AAC frames
            val enc = encoder ?: throw IOException("所选时间范围内未读取到有效音频数据")
            while (!encoderInputEosSent) {
                if (isCancelled()) throw CancellationException("已取消")
                val inIdx = enc.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIdx >= 0) {
                    val ptsUs = totalEncodedFrames * 1_000_000L / sampleRate
                    enc.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    encoderInputEosSent = true
                }
                drainEncoder(false)
            }

            if (!encoderOutputDone) {
                drainEncoder(true)
            }

            if (muxerStarted) {
                muxer.stop()
                muxerStarted = false
            }
            muxer.release()
            muxer = null

            pfd.close()
            pfd = null

            finalizePendingUri(context, outputUri)
            val (finalName, finalSize) = queryOutputInfo(context, outputUri, "$outputBaseName.m4a")
            val elapsedMs = (SystemClock.elapsedRealtime() - startElapsed).coerceAtLeast(1L)
            onProgress(100)

            return ExtractionResult(
                outputUri = outputUri,
                outputName = finalName,
                relativePath = AudioExtractorEngine.OUTPUT_RELATIVE_PATH,
                outputSizeBytes = finalSize,
                elapsedMs = elapsedMs,
                audioMime = MediaFormat.MIMETYPE_AUDIO_AAC
            )
        } catch (t: Throwable) {
            cleanupOnFailure(context, muxer, muxerStarted, pfd, outputUri)
            throw t
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyFadePcm16(
        pcmBytes: ByteArray,
        frameCount: Int,
        channelCount: Int,
        sampleRate: Int,
        firstFrameRelUs: Long,
        clipDurationUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long
    ) {
        if (fadeInUs <= 0L && fadeOutUs <= 0L) return

        val chunkDurationUs = frameCount * 1_000_000L / sampleRate
        val chunkEndRelUs = firstFrameRelUs + chunkDurationUs
        val fadeOutStartUs = clipDurationUs - fadeOutUs

        val inFadeIn = fadeInUs > 0L && firstFrameRelUs < fadeInUs
        val inFadeOut = fadeOutUs > 0L && chunkEndRelUs > fadeOutStartUs
        if (!inFadeIn && !inFadeOut) return

        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val usPerFrame = 1_000_000.0 / sampleRate.toDouble()

        for (frame in 0 until frameCount) {
            val relUs = firstFrameRelUs + (frame * usPerFrame)
            var gain = 1.0

            if (fadeInUs > 0L && relUs < fadeInUs) {
                val ratio = (relUs / fadeInUs.toDouble()).coerceIn(0.0, 1.0)
                // Quadratic curve feels smoother to human ears.
                gain *= (ratio * ratio)
            }

            if (fadeOutUs > 0L && relUs > fadeOutStartUs) {
                val remaining = ((clipDurationUs - relUs) / fadeOutUs.toDouble()).coerceIn(0.0, 1.0)
                gain *= (remaining * remaining)
            }

            if (gain < 0.9999) {
                val baseByteIndex = frame * channelCount * 2
                for (ch in 0 until channelCount) {
                    val idx = baseByteIndex + ch * 2
                    val sample = bb.getShort(idx).toInt()
                    val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
                    bb.putShort(idx, scaled.toShort())
                }
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Triple<Int, MediaFormat, String> {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return Triple(i, format, mime)
            }
        }
        throw UnsupportedAudioFormatException("未在文件中检测到音频轨道")
    }

    private fun createPendingOutputUri(context: Context, displayName: String): Uri {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, AudioExtractorEngine.OUTPUT_RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: throw IOException("无法创建输出音频文件")
    }

    private fun finalizePendingUri(context: Context, outputUri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(outputUri, values, null, null)
    }

    private fun cleanupOnFailure(
        context: Context,
        muxer: MediaMuxer?,
        muxerStarted: Boolean,
        pfd: ParcelFileDescriptor?,
        outputUri: Uri?
    ) {
        try {
            if (muxerStarted) muxer?.stop()
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
