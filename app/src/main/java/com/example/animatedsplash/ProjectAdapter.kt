package com.example.animatedsplash

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.animatedsplash.databinding.ItemProjectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProjectAdapter(
    private val onProjectClick: (Project) -> Unit,
    private val onProjectExpand: (Project) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.ProjectViewHolder>() {

    private val projects = mutableListOf<Project>()
    private val dateFormatter = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())

    fun submitProjects(items: List<Project>, ascending: Boolean = false) {
        projects.clear()
        projects.addAll(
            if (ascending) items.sortedBy { it.name.lowercase(Locale.getDefault()) }
            else items.sortedByDescending { it.updatedAt }
        )
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(projects[position])
    }

    override fun getItemCount(): Int = projects.size

    inner class ProjectViewHolder(
        private val binding: ItemProjectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(project: Project) = with(binding) {
            val context = root.context
            val shortId = (project.id % 1000L).toString()
            projectNumber.text = context.getString(R.string.project_number, shortId)
            val displayName = project.name.trim().ifBlank {
                project.inoFileName?.substringBeforeLast('.')?.ifBlank { "Arduino Project" }
                    ?: "Arduino Project"
            }
            projectName.text = displayName
            val elapsed = (System.currentTimeMillis() - project.updatedAt).coerceAtLeast(0L)
            val relative = when {
                elapsed < TimeUnit.MINUTES.toMillis(1) -> context.getString(R.string.relative_now)
                elapsed < TimeUnit.HOURS.toMillis(1) -> context.getString(
                    R.string.relative_minutes,
                    TimeUnit.MILLISECONDS.toMinutes(elapsed).toInt().coerceAtLeast(1)
                )
                elapsed < TimeUnit.DAYS.toMillis(1) -> context.getString(
                    R.string.relative_hours,
                    TimeUnit.MILLISECONDS.toHours(elapsed).toInt().coerceAtLeast(1)
                )
                elapsed < TimeUnit.DAYS.toMillis(7) -> context.getString(
                    R.string.relative_days,
                    TimeUnit.MILLISECONDS.toDays(elapsed).toInt().coerceAtLeast(1)
                )
                else -> dateFormatter.format(Date(project.updatedAt))
            }
            projectMeta.text = context.getString(R.string.project_last_opened, relative)
            projectType.text = context.getString(R.string.project_type_code)
            projectIcon.setImageResource(iconResource(project.iconKey))
            projectIcon.contentDescription = context.getString(R.string.project_icon_description)
            root.setOnClickListener { onProjectClick(project) }
            expandButton.setOnClickListener { onProjectExpand(project) }
        }
    }

    private fun iconResource(iconKey: String): Int = ProjectIconCatalog.resource(iconKey)
}
