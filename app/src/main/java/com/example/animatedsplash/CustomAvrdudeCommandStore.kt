package com.example.animatedsplash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomAvrdudeCommand(
    val id: Long,
    val name: String,
    val arguments: String
)

class CustomAvrdudeCommandStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): List<CustomAvrdudeCommand> = runCatching {
        val values = JSONArray(preferences.getString(COMMANDS_KEY, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val name = value.optString("name").trim()
                val arguments = value.optString("arguments").trim()
                if (name.isNotBlank() && arguments.isNotBlank()) {
                    add(CustomAvrdudeCommand(value.optLong("id"), name, arguments))
                }
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun upsert(command: CustomAvrdudeCommand) {
        val normalized = command.copy(
            id = command.id.takeIf { it > 0L } ?: System.currentTimeMillis(),
            name = command.name.trim().take(MAX_NAME_CHARS),
            arguments = command.arguments.trim().take(MAX_ARGUMENT_CHARS)
        )
        require(normalized.name.isNotBlank()) { "Command name is required" }
        require(normalized.arguments.isNotBlank()) { "avrdude arguments are required" }
        val commands = load().filterNot { it.id == normalized.id }.toMutableList()
        commands += normalized
        persist(commands.takeLast(MAX_COMMANDS))
    }

    fun delete(id: Long) {
        persist(load().filterNot { it.id == id })
    }

    private fun persist(commands: List<CustomAvrdudeCommand>) {
        val values = JSONArray()
        commands.forEach { command ->
            values.put(JSONObject().apply {
                put("id", command.id)
                put("name", command.name)
                put("arguments", command.arguments)
            })
        }
        preferences.edit().putString(COMMANDS_KEY, values.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "luma_custom_avrdude_commands"
        const val COMMANDS_KEY = "commands"
        const val MAX_COMMANDS = 24
        const val MAX_NAME_CHARS = 64
        const val MAX_ARGUMENT_CHARS = 2_048
    }
}
