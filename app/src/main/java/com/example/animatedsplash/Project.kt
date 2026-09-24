package com.example.animatedsplash

data class Project(
    val id: Long,
    val name: String,
    val type: ProjectType,
    val iconKey: String = "spark",
    val importedFileName: String? = null,
    val sourceUri: String? = null,
    val inoFileName: String? = null,
    val libraries: Set<String> = emptySet(),
    val createdAt: Long,
    val updatedAt: Long
)

enum class ProjectType(val key: String) {
    CODE("code");

    companion object {
        fun fromKey(key: String): ProjectType {
            return entries.firstOrNull { it.key == key } ?: CODE
        }
    }
}
