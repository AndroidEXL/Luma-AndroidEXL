package com.example.animatedsplash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BuildHistoryEntry(
    val type: String,
    val success: Boolean,
    val timestamp: Long,
    val durationMs: Long,
    val board: String,
    val port: String,
    val summary: String
)

/** Keeps a small, project-scoped history without persisting unbounded compiler logs. */
class BuildHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun add(projectId: Long, entry: BuildHistoryEntry) {
        val items = load(projectId).toMutableList()
        items.add(0, entry)
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("type", item.type)
                put("success", item.success)
                put("timestamp", item.timestamp)
                put("duration", item.durationMs)
                put("board", item.board)
                put("port", item.port)
                put("summary", item.summary.take(MAX_SUMMARY_CHARS))
            })
        }
        preferences.edit().putString(key(projectId), array.toString()).apply()
    }

    fun load(projectId: Long): List<BuildHistoryEntry> {
        val raw = preferences.getString(key(projectId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(BuildHistoryEntry(
                        type = item.optString("type", "Check"),
                        success = item.optBoolean("success"),
                        timestamp = item.optLong("timestamp"),
                        durationMs = item.optLong("duration"),
                        board = item.optString("board"),
                        port = item.optString("port"),
                        summary = item.optString("summary")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun key(projectId: Long) = "project_${projectId}_history"

    private companion object {
        const val PREFERENCES = "luma_build_history"
        const val MAX_ITEMS = 30
        const val MAX_SUMMARY_CHARS = 900
    }
}
