package com.example.animatedsplash

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.addTextChangedListener
import com.example.animatedsplash.databinding.ActivityLibraryBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var projectStore: ProjectStore
    private lateinit var libraryStore: LibraryStore
    private lateinit var indexService: LibraryIndexService
    private var project: Project? = null
    private var catalog: List<ArduinoLibrary> = LibraryCatalog.all
    private var showingInstalledOnly = true
    private var projectOnly = false
    private var selectedCategory: String? = null
    private val recentSearches = mutableListOf<String>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val delayedSearch = Runnable {
        if (::binding.isInitialized) {
            renderList(binding.librarySearchInput.text?.toString().orEmpty())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectStore = ProjectStore(this)
        libraryStore = LibraryStore(this)
        indexService = LibraryIndexService(this)
        project = projectStore.load().firstOrNull {
            it.id == intent.getLongExtra(EXTRA_PROJECT_ID, Long.MIN_VALUE)
        }
        binding.libraryProjectLabel.text = project?.name.orEmpty()
        recentSearches += getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getStringSet(LIBRARY_SEARCH_HISTORY_KEY, emptySet())
            .orEmpty()
            .sorted()
        binding.libraryBackButton.setOnClickListener { finish() }
        binding.librarySearchInput.addTextChangedListener {
            searchHandler.removeCallbacks(delayedSearch)
            searchHandler.postDelayed(delayedSearch, 180L)
        }
        binding.addInternetLibraryButton.setOnClickListener {
            showingInstalledOnly = false
            updateModeButton()
            renderList(binding.librarySearchInput.text?.toString().orEmpty())
            showOnlineLibraryDialog()
        }
        binding.libraryModeButton.setOnClickListener {
            showingInstalledOnly = !showingInstalledOnly
            updateModeButton()
            renderList(binding.librarySearchInput.text?.toString().orEmpty())
        }
        binding.libraryProjectOnlyButton.setOnClickListener {
            projectOnly = !projectOnly
            updateFilterButtons()
            renderList(binding.librarySearchInput.text?.toString().orEmpty())
        }
        binding.libraryCategoryButton.setOnClickListener { showCategoryPicker() }
        binding.libraryHistoryButton.setOnClickListener { showSearchHistory() }
        binding.libraryClearSearchButton.setOnClickListener {
            binding.librarySearchInput.text?.clear()
            binding.librarySearchInput.requestFocus()
        }
        updateModeButton()
        updateFilterButtons()
        renderList("")
        loadOnlineCatalog()
    }

    private fun loadOnlineCatalog() {
        binding.libraryProgress.visibility = View.VISIBLE
        binding.libraryStatus.text = getString(R.string.ide_library_online_loading)
        indexService.loadAsync(
            onSuccess = { libraries, cached ->
                runOnUiThread {
                    catalog = libraries
                    binding.libraryProgress.visibility = View.GONE
                    binding.libraryStatus.text = if (cached) {
                        getString(R.string.ide_library_online_cache)
                    } else {
                        getString(R.string.ide_library_online_ready, libraries.size)
                    }
                    renderList(binding.librarySearchInput.text?.toString().orEmpty())
                }
            },
            onFailure = { error ->
                runOnUiThread {
                    binding.libraryProgress.visibility = View.GONE
                    binding.libraryStatus.text = getString(R.string.ide_library_online_error_detail, error)
                    renderList(binding.librarySearchInput.text?.toString().orEmpty())
                }
            }
        )
    }

    private fun updateModeButton() {
        binding.libraryModeButton.text = if (showingInstalledOnly) {
            getString(R.string.ide_library_browse_online)
        } else {
            getString(R.string.ide_library_show_installed)
        }
        binding.addInternetLibraryButton.visibility = if (showingInstalledOnly) View.VISIBLE else View.GONE
    }

    private fun updateFilterButtons() {
        binding.libraryProjectOnlyButton.setText(
            if (projectOnly) R.string.library_project_filter_on else R.string.library_project_filter
        )
        binding.libraryCategoryButton.text = selectedCategory ?: getString(R.string.library_category_all)
    }

    private fun installedNames(): Set<String> = libraryStore.installed() + ArduinoLibraryStorage.installedNames(this)

    private fun isInstalled(name: String): Boolean {
        val normalized = safeLibraryName(name)
        return installedNames().any { safeLibraryName(it) == normalized }
    }

    private fun displayLibraries(): List<ArduinoLibrary> {
        if (!showingInstalledOnly) return catalog
        val installed = installedNames()
        val known = catalog.filter { isInstalled(it.name) }.toMutableList()
        val knownKeys = known.map { safeLibraryName(it.name) }.toSet()
        installed.filter { safeLibraryName(it) !in knownKeys }
            .sortedBy { it.lowercase() }
            .forEach { name ->
                known += ArduinoLibrary(
                    name = name,
                    description = getString(R.string.ide_library_installed_local),
                    category = getString(R.string.ide_library_category_local),
                    version = ""
                )
            }
        return known
    }

    private fun renderList(query: String) {
        val normalized = query.trim()
        if (normalized.length >= 2) recordSearch(normalized)
        val scoped = displayLibraries()
            .filter { library -> !projectOnly || project?.libraries?.any { safeLibraryName(it) == safeLibraryName(library.name) } == true }
            .filter { library -> selectedCategory == null || library.category.equals(selectedCategory, ignoreCase = true) }
        val ranked = rankLibraries(scoped, normalized)
        val items = ranked.map { it.first }
        renderSuggestions(ranked.take(4).map { it.first }, normalized)
        binding.libraryList.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (showingInstalledOnly) getString(R.string.ide_library_no_installed)
                else getString(R.string.ide_library_empty)
                setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }
            binding.libraryList.addView(empty)
            binding.libraryStatus.text = getString(R.string.library_results_count, 0)
            return
        }
        binding.libraryStatus.text = getString(R.string.library_results_count, items.size)
        items.take(120).forEach { addLibraryRow(it) }
    }

    private fun rankLibraries(libraries: List<ArduinoLibrary>, query: String): List<Pair<ArduinoLibrary, Int>> {
        if (query.isBlank()) return libraries.sortedBy { it.name.lowercase(Locale.ROOT) }.map { it to 0 }
        return libraries.mapNotNull { library -> librarySearchScore(library, query)?.let { score -> library to score } }
            .sortedWith(compareByDescending<Pair<ArduinoLibrary, Int>> { it.second }.thenBy { it.first.name.lowercase(Locale.ROOT) })
    }

    private fun librarySearchScore(library: ArduinoLibrary, query: String): Int? {
        val canonicalQuery = canonical(query)
        val canonicalName = canonical(library.name)
        val tokens = tokenize(library.name) + tokenize(library.description) + tokenize(library.category)
        if (canonicalQuery.isBlank()) return 0
        if (canonicalName == canonicalQuery) return 1_000
        if (canonicalName.startsWith(canonicalQuery)) return 920
        if (canonicalName.contains(canonicalQuery)) return 860
        val queryTokens = tokenize(query)
        if (queryTokens.isNotEmpty() && queryTokens.all { q -> tokens.any { token -> token.startsWith(q) || token.contains(q) } }) return 780
        val text = canonical("${library.name} ${library.description} ${library.category}")
        if (text.contains(canonicalQuery)) return 700
        val distance = levenshtein(canonicalName, canonicalQuery)
        val tolerance = when (canonicalQuery.length) { in 1..4 -> 1; in 5..8 -> 2; else -> 3 }
        return if (distance <= tolerance) 500 - distance * 40 else null
    }

    private fun canonical(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    private fun tokenize(value: String): List<String> = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.ROOT)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }

    private fun levenshtein(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        first.forEachIndexed { index, char ->
            val current = IntArray(second.length + 1)
            current[0] = index + 1
            second.forEachIndexed { secondIndex, secondChar ->
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (char == secondChar) 0 else 1
                )
            }
            previous = current
        }
        return previous.last()
    }

    private fun renderSuggestions(suggestions: List<ArduinoLibrary>, query: String) {
        binding.librarySuggestions.removeAllViews()
        if (query.isBlank()) return
        suggestions.distinctBy { safeLibraryName(it.name) }.forEach { library ->
            binding.librarySuggestions.addView(MaterialButton(this).apply {
                text = library.name
                textSize = 10f
                isAllCaps = false
                minWidth = 0
                setOnClickListener { binding.librarySearchInput.setText(library.name) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        }
    }

    private fun recordSearch(query: String) {
        recentSearches.removeAll { it.equals(query, ignoreCase = true) }
        recentSearches.add(0, query)
        while (recentSearches.size > MAX_RECENT_SEARCHES) recentSearches.removeAt(recentSearches.lastIndex)
        getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .edit().putStringSet(LIBRARY_SEARCH_HISTORY_KEY, recentSearches.toSet()).apply()
    }

    private fun showSearchHistory() {
        if (recentSearches.isEmpty()) {
            Toast.makeText(this, R.string.library_search_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_search_history)
            .setItems(recentSearches.toTypedArray()) { _, which ->
                binding.librarySearchInput.setText(recentSearches[which])
            }
            .setNeutralButton(R.string.library_clear_history) { _, _ ->
                recentSearches.clear()
                getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                    .edit().remove(LIBRARY_SEARCH_HISTORY_KEY).apply()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun showCategoryPicker() {
        val categories = displayLibraries().map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
        val labels = arrayOf(getString(R.string.library_category_all), *categories.toTypedArray())
        val selected = selectedCategory?.let { categories.indexOf(it) + 1 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.library_category_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                selectedCategory = categories.getOrNull(which - 1)
                updateFilterButtons()
                renderList(binding.librarySearchInput.text?.toString().orEmpty())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun addLibraryRow(library: ArduinoLibrary) {
        val currentProject = project
        val inProject = currentProject?.libraries?.any { safeLibraryName(it) == safeLibraryName(library.name) } == true
        val installed = isInstalled(library.name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@LibraryActivity, R.drawable.project_card)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setOnLongClickListener {
                if (!installed) return@setOnLongClickListener false
                confirmDeleteLibrary(library)
                true
            }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = library.name
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@LibraryActivity, if (inProject) R.color.library_added else R.color.library_not_added))
        }
        val version = library.version.takeIf { it.isNotBlank() }?.let { "  v$it" }.orEmpty()
        val description = TextView(this).apply {
            text = "${library.category}$version  •  ${library.description}"
            textSize = 11f
            maxLines = 2
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.text_secondary))
            setPadding(0, dp(5), 0, 0)
        }
        info.addView(name)
        info.addView(description)
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val action = MaterialButton(this).apply {
            text = if (inProject) getString(R.string.ide_library_remove_project)
            else if (installed) getString(R.string.ide_library_add_project)
            else getString(R.string.ide_library_add_internet)
            textSize = 10f
            isAllCaps = false
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                if (!installed) installOne(library) else updateProjectLibrary(library.name, add = true)
            }
        }
        if (inProject) {
            action.setOnClickListener { updateProjectLibrary(library.name, add = false) }
        }
        row.addView(action, LinearLayout.LayoutParams(-2, dp(44)))
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        binding.libraryList.addView(row, params)
    }

    private fun showOnlineLibraryDialog() {
        val available = catalog.take(250)
        if (available.isEmpty()) {
            binding.libraryStatus.text = getString(R.string.ide_library_online_loading)
            return
        }
        val names = available.map { it.name }.toTypedArray()
        val installed = installedNames()
        val checked = BooleanArray(names.size) { index -> installed.any { safeLibraryName(it) == safeLibraryName(names[index]) } }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_library_add_internet)
            .setMultiChoiceItems(names, checked, null)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val listView = (dialog as? androidx.appcompat.app.AlertDialog)?.listView
                val selected = if (listView != null) {
                    available.indices.filter { listView.isItemChecked(it) }.map { available[it] }
                } else emptyList()
                installLibraries(selected, addToProject = true)
            }
            .show()
    }

    private fun installOne(library: ArduinoLibrary) {
        showingInstalledOnly = false
        updateModeButton()
        installLibraries(listOf(library), addToProject = true)
    }

    private fun installLibraries(libraries: List<ArduinoLibrary>, addToProject: Boolean) {
        val installed = installedNames()
        val alreadyInstalled = libraries.filter { isInstalled(it.name) }
        if (addToProject) alreadyInstalled.forEach { updateProjectLibrary(it.name, add = true, refresh = false) }
        val downloadable = libraries.filter { !isInstalled(it.name) && !it.downloadUrl.isNullOrBlank() }
        if (downloadable.isEmpty()) {
            if (alreadyInstalled.isNotEmpty()) {
                binding.libraryStatus.text = getString(R.string.ide_library_added_to_project, alreadyInstalled.size)
                renderList(binding.librarySearchInput.text?.toString().orEmpty())
            } else {
                binding.libraryStatus.text = getString(R.string.ide_library_online_error)
            }
            return
        }
        binding.libraryProgress.visibility = View.VISIBLE
        binding.libraryStatus.text = getString(R.string.ide_library_download_progress, downloadable.first().name)
        indexService.downloadAsync(
            libraries = downloadable,
            onProgress = { library ->
                runOnUiThread {
                    binding.libraryStatus.text = getString(R.string.ide_library_download_progress, library.name)
                    libraryStore.markInstalled(library.name)
                    if (addToProject) updateProjectLibrary(library.name, add = true, refresh = false)
                }
            },
            onComplete = { completed ->
                runOnUiThread {
                    binding.libraryProgress.visibility = View.GONE
                    completed.forEach { libraryStore.markInstalled(it.name) }
                    binding.libraryStatus.text = if (addToProject && completed.isNotEmpty()) {
                        getString(R.string.ide_library_added_to_project, completed.size)
                    } else getString(R.string.ide_library_download_done)
                    showingInstalledOnly = true
                    updateModeButton()
                    renderList(binding.librarySearchInput.text?.toString().orEmpty())
                }
            },
            onFailure = { error ->
                runOnUiThread {
                    binding.libraryProgress.visibility = View.GONE
                    binding.libraryStatus.text = getString(R.string.ide_library_online_error_detail, error)
                    renderList(binding.librarySearchInput.text?.toString().orEmpty())
                }
            }
        )
    }

    private fun confirmDeleteLibrary(library: ArduinoLibrary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_library_delete_title)
            .setMessage(getString(R.string.ide_library_delete_message, library.name))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_library_delete) { _, _ -> deleteLibrary(library) }
            .show()
    }

    private fun deleteLibrary(library: ArduinoLibrary) {
        binding.libraryProgress.visibility = View.VISIBLE
        binding.libraryStatus.text = getString(R.string.ide_library_deleting, library.name)
        Thread {
            val deleted = ArduinoLibraryStorage.deleteInstalledLibrary(this, library.name)
            libraryStore.markRemoved(library.name)
            val projects = projectStore.load().map { stored ->
                stored.copy(
                    libraries = stored.libraries.filterNot { safeLibraryName(it) == safeLibraryName(library.name) }.toSet(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            projectStore.save(projects)
            runOnUiThread {
                binding.libraryProgress.visibility = View.GONE
                binding.libraryStatus.text = if (deleted) getString(R.string.ide_library_deleted, library.name)
                else getString(R.string.ide_library_delete_failed, library.name)
                project = projectStore.load().firstOrNull { it.id == project?.id }
                renderList(binding.librarySearchInput.text?.toString().orEmpty())
                Toast.makeText(this, binding.libraryStatus.text, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun updateProjectLibrary(name: String, add: Boolean, refresh: Boolean = true) {
        val current = project ?: return
        val libraries = current.libraries.toMutableSet()
        val existing = libraries.firstOrNull { safeLibraryName(it) == safeLibraryName(name) }
        if (add) libraries.add(name) else existing?.let { libraries.remove(it) }
        val updated = current.copy(libraries = libraries, updatedAt = System.currentTimeMillis())
        projectStore.save(projectStore.load().map { if (it.id == current.id) updated else it })
        project = updated
        if (refresh) renderList(binding.librarySearchInput.text?.toString().orEmpty())
    }

    private fun safeLibraryName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifBlank { "library" }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        searchHandler.removeCallbacks(delayedSearch)
        indexService.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT_ID = "library_project_id"
        private const val LIBRARY_SEARCH_HISTORY_KEY = "library_search_history"
        private const val MAX_RECENT_SEARCHES = 12
    }
}
