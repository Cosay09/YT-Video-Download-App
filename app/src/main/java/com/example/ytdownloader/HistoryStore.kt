package com.example.ytdownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val name: String,
    val uri: String,
    val mime: String,
    val time: Long
)

object HistoryStore {
    private const val KEY = "history"

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(context: Context): List<HistoryItem> {
        val text = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                HistoryItem(
                    o.getString("name"),
                    o.getString("uri"),
                    o.getString("mime"),
                    o.getLong("time")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, item: HistoryItem) {
        // A replaced file has the same uri, so it moves to the top instead of repeating
        val list = listOf(item) + load(context).filter { it.uri != item.uri }
        save(context, list.take(100))
    }

    fun remove(context: Context, item: HistoryItem) {
        save(context, load(context).filter { it.uri != item.uri })
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<HistoryItem>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("uri", it.uri)
                    .put("mime", it.mime)
                    .put("time", it.time)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}