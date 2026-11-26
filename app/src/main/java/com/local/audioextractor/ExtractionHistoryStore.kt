package com.local.audioextractor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val outputUriString: String,
    val outputName: String,
    val outputSizeBytes: Long,
    val elapsedMs: Long,
    val timestampMs: Long
)

object ExtractionHistoryStore {
    private const val PREF_NAME = "audio_extractor_history"
    private const val KEY_ITEMS = "items_json"
    private const val MAX_ITEMS = 30

    fun load(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoryItem(
                            outputUriString = obj.optString("uri"),
                            outputName = obj.optString("name"),
                            outputSizeBytes = obj.optLong("size", 0L),
                            elapsedMs = obj.optLong("elapsed", 0L),
                            timestampMs = obj.optLong("ts", 0L)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun add(context: Context, item: HistoryItem): List<HistoryItem> {
        val updated = (listOf(item) + load(context)).take(MAX_ITEMS)
        save(context, updated)
        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    private fun save(context: Context, items: List<HistoryItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("uri", item.outputUriString)
                    put("name", item.outputName)
                    put("size", item.outputSizeBytes)
                    put("elapsed", item.elapsedMs)
                    put("ts", item.timestampMs)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
