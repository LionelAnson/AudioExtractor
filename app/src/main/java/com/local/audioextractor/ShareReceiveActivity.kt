package com.local.audioextractor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ShareReceiveActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = collectSharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "未收到可提取的视频文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        AudioExtractService.enqueue(this, uris)

        val toastText = if (uris.size == 1) {
            "开始无损提取音频…"
        } else {
            "开始提取 ${uris.size} 个视频的音频…"
        }
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(mainIntent)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun collectSharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val result = linkedSetOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) result.add(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.forEach { result.add(it) }
            }
        }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result.add(it) }
            }
        }

        intent.data?.let { result.add(it) }
        return result.toList()
    }
}
