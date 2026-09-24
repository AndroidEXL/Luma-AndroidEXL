package com.example.animatedsplash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProjectStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<Project> {
        val raw = preferences.getString(PROJECTS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Project(
                            id = item.getLong("id"),
                            name = item.getString("name"),
                            type = ProjectType.fromKey(item.optString("type", ProjectType.CODE.key)),
                            iconKey = item.optString("iconKey", "spark"),
                            importedFileName = item.optString("importedFileName").takeIf { it.isNotBlank() },
                            sourceUri = item.optString("sourceUri").takeIf { it.isNotBlank() },
                            inoFileName = item.optString("inoFileName").takeIf { it.isNotBlank() },
                            libraries = item.optJSONArray("libraries")?.let { array ->
                                buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
                            } ?: emptySet(),
                            createdAt = item.getLong("createdAt"),
                            updatedAt = item.getLong("updatedAt")
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun save(projects: List<Project>) {
        val array = JSONArray()
        projects.forEach { project ->
            array.put(
                JSONObject().apply {
                    put("id", project.id)
                    put("name", project.name)
                    put("type", project.type.key)
                    put("iconKey", project.iconKey)
                    put("importedFileName", project.importedFileName ?: "")
                    put("sourceUri", project.sourceUri ?: "")
                    put("inoFileName", project.inoFileName ?: "")
                    put("libraries", JSONArray().apply { project.libraries.forEach(::put) })
                    put("createdAt", project.createdAt)
                    put("updatedAt", project.updatedAt)
                }
            )
        }
        preferences.edit().putString(PROJECTS_KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "luma_projects"
        private const val PROJECTS_KEY = "projects_json"
    }
}
