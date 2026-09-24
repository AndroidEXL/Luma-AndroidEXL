package com.example.animatedsplash

import android.content.Context
import org.json.JSONArray

class LibraryStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun installed(): Set<String> {
        val raw = preferences.getString(INSTALLED_KEY, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
        }.getOrDefault(emptySet())
    }

    fun markInstalled(name: String) {
        saveInstalled(installed() + name)
    }

    fun markRemoved(name: String) {
        saveInstalled(installed() - name)
    }

    private fun saveInstalled(names: Set<String>) {
        preferences.edit()
            .putString(INSTALLED_KEY, JSONArray().apply { names.sorted().forEach(::put) }.toString())
            .apply()
    }

    companion object {
        private const val PREFERENCES = "luma_libraries"
        private const val INSTALLED_KEY = "installed_libraries"
    }
}
