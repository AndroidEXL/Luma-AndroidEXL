package com.example.animatedsplash

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.animatedsplash.databinding.ActivityMainBinding
import com.example.animatedsplash.databinding.BottomSheetProjectActionsBinding
import com.example.animatedsplash.databinding.DialogCreateProjectBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectStore: ProjectStore
    private lateinit var inoFileStore: InoFileStore
    private lateinit var backupStore: ProjectBackupStore
    private lateinit var projectAdapter: ProjectAdapter
    private var projects = emptyList<Project>()
    private var pendingExport: Project? = null
    private var projectQuery = ""
    private var sortProjectsAscending = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { selectedFile ->
        selectedFile?.let { importArdx(it) }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { destination ->
        val project = pendingExport ?: return@registerForActivityResult
        if (destination != null) {
            runCatching {
                contentResolver.openOutputStream(destination)?.use { output ->
                    writeArdxArchive(project, output)
                } ?: error("تعذر فتح ملف التصدير")
                Toast.makeText(this, R.string.export_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, it.message ?: "تعذر التصدير", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExport = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectStore = ProjectStore(this)
        inoFileStore = InoFileStore(this)
        backupStore = ProjectBackupStore(this)
        projectAdapter = ProjectAdapter(
            onProjectClick = { project -> openIde(project) },
            onProjectExpand = { project -> showProjectActions(project) }
        )

        binding.projectList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = projectAdapter
            setHasFixedSize(true)
        }

        binding.createProjectButton.setOnClickListener { showProjectDialog() }
        binding.importProjectButton.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.projectSearchInput.doAfterTextChanged { editable ->
            projectQuery = editable?.toString().orEmpty()
            renderProjects()
        }
        binding.projectSortButton.setOnClickListener {
            sortProjectsAscending = !sortProjectsAscending
            binding.projectSortButton.text = getString(
                if (sortProjectsAscending) R.string.project_sort_ascending else R.string.projects_sort_recent
            )
            renderProjects()
        }
        binding.projectSortButton.text = getString(R.string.projects_sort_recent)
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadProjects()
        val replayTutorial = intent.getBooleanExtra(EXTRA_REPLAY_TUTORIAL, false)
        val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
        if (replayTutorial) {
            preferences.edit()
                .putBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, false)
                .putInt(LumaApplication.TUTORIAL_FLOW_STAGE_KEY, LumaApplication.TUTORIAL_STAGE_PROJECT)
                .apply()
        }
        val tutorialStage = preferences.getInt(
            LumaApplication.TUTORIAL_FLOW_STAGE_KEY,
            LumaApplication.TUTORIAL_STAGE_PROJECT
        )
        if ((replayTutorial || tutorialStage == LumaApplication.TUTORIAL_STAGE_PROJECT) &&
            !preferences.getBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, false)) {
            binding.mainRoot.postDelayed({ startInteractiveGuide() }, 450L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::projectStore.isInitialized) loadProjects()
    }

    private fun loadProjects() {
        projects = projectStore.load()
        renderProjects()
    }

    private fun renderProjects() {
        val query = projectQuery.trim()
        val visibleProjects = if (query.isBlank()) projects else projects.filter { project ->
            project.name.contains(query, ignoreCase = true) ||
                project.inoFileName.orEmpty().contains(query, ignoreCase = true)
        }
        projectAdapter.submitProjects(visibleProjects, ascending = sortProjectsAscending)
        val hasProjects = projects.isNotEmpty()
        val hasVisibleProjects = visibleProjects.isNotEmpty()
        binding.projectList.visibility = if (hasVisibleProjects) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasProjects) View.GONE else View.VISIBLE
        binding.noResultsState.visibility = if (hasProjects && !hasVisibleProjects) View.VISIBLE else View.GONE
        binding.projectsCount.text = getString(R.string.projects_count, visibleProjects.size, projects.size)
    }

    private fun showProjectDialog(existingProject: Project? = null, guidedFirstProject: Boolean = false) {
        val dialogBinding = DialogCreateProjectBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(existingProject?.name.orEmpty())
        dialogBinding.iconToggleGroup.check(iconButtonId(existingProject?.iconKey ?: "spark"))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existingProject == null) R.string.create_project else R.string.edit_project)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(
                if (existingProject == null) R.string.create_action else R.string.save_action,
                null
            )
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    dialogBinding.nameInputLayout.error = getString(R.string.project_name_hint)
                    return@setOnClickListener
                }
                dialogBinding.nameInputLayout.error = null
                val now = System.currentTimeMillis()
                val iconKey = iconKeyFromButton(dialogBinding.iconToggleGroup.checkedButtonId)
                val templateIndex = dialogBinding.templateSpinner.selectedItemPosition
                val updatedProject = existingProject?.copy(
                    name = name,
                    type = ProjectType.CODE,
                    iconKey = iconKey,
                    inoFileName = safeInoFileName(name),
                    updatedAt = now
                ) ?: Project(
                    id = now,
                    name = name,
                    type = ProjectType.CODE,
                    iconKey = iconKey,
                    inoFileName = safeInoFileName(name),
                    createdAt = now,
                    updatedAt = now
                )
                projects = if (existingProject == null) {
                    projects + updatedProject
                } else {
                    projects.map { if (it.id == existingProject.id) updatedProject else it }
                }.sortedByDescending { it.updatedAt }
                projectStore.save(projects)
                val projectFile = inoFileStore.ensureFile(updatedProject)
                if (existingProject == null) {
                    projectFile.writeText(templateCode(templateIndex, name))
                }
                loadProjects()
                dialog.dismiss()
                if (guidedFirstProject && existingProject == null) {
                    getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                        .edit().putInt(LumaApplication.TUTORIAL_FLOW_STAGE_KEY, LumaApplication.TUTORIAL_STAGE_IDE).apply()
                }
                openIde(updatedProject)
            }
        }
        dialog.show()
        if (guidedFirstProject) {
            dialog.window?.decorView?.post {
                startProjectCreationGuide(dialogBinding, dialog)
            }
        }
    }

    private fun startInteractiveGuide() {
        SpotlightTutorial(
            activity = this,
            steps = listOf(
                TutorialStep(binding.mainTitle, getString(R.string.tutorial_welcome_title), getString(R.string.tutorial_welcome_body)),
                TutorialStep(binding.createProjectButton, getString(R.string.tutorial_create_title), getString(R.string.tutorial_create_body)),
                TutorialStep(binding.projectSearchInput, getString(R.string.tutorial_search_title), getString(R.string.tutorial_search_body)),
                TutorialStep(binding.importProjectButton, getString(R.string.tutorial_import_title), getString(R.string.tutorial_import_body)),
                TutorialStep(binding.settingsButton, getString(R.string.tutorial_settings_title), getString(R.string.tutorial_settings_body))
            ),
            onFinished = { },
            onLastStep = { showProjectDialog(guidedFirstProject = true) }
        ).start()
    }

    private fun startProjectCreationGuide(
        dialogBinding: DialogCreateProjectBinding,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        val dialogRoot = dialog.window?.decorView as? ViewGroup ?: return
        val createButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
        SpotlightTutorial(
            activity = this,
            hostRoot = dialogRoot,
            steps = listOf(
                TutorialStep(dialogBinding.nameInput, getString(R.string.tutorial_project_name_title), getString(R.string.tutorial_project_name_body)),
                TutorialStep(dialogBinding.templateSpinner, getString(R.string.tutorial_template_title), getString(R.string.tutorial_template_body)),
                TutorialStep(dialogBinding.iconToggleGroup, getString(R.string.tutorial_icon_title), getString(R.string.tutorial_icon_body)),
                TutorialStep(createButton, getString(R.string.tutorial_finish_project_title), getString(R.string.tutorial_finish_project_body))
            ),
            onFinished = { },
            onLastStep = {
                dialogBinding.nameInput.requestFocus()
                Toast.makeText(this, R.string.tutorial_create_project_prompt, Toast.LENGTH_LONG).show()
            }
        ).start()
    }

    private fun showProjectActions(project: Project) {
        val sheetBinding = BottomSheetProjectActionsBinding.inflate(layoutInflater)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(sheetBinding.root)
        sheetBinding.actionsProjectName.text = project.name

        sheetBinding.backupAction.setOnClickListener {
            backupProject(project)
            sheet.dismiss()
        }
        sheetBinding.duplicateAction.setOnClickListener {
            duplicateProject(project)
            sheet.dismiss()
        }
        sheetBinding.detailsAction.setOnClickListener {
            showProjectDetails(project)
        }
        sheetBinding.deleteAction.setOnClickListener {
            sheet.dismiss()
            confirmDelete(project)
        }
        sheetBinding.exportAction.setOnClickListener {
            sheet.dismiss()
            beginExport(project)
        }
        sheet.show()
    }

    private fun backupProject(project: Project) {
        val code = inoFileStore.read(project)
        runCatching { backupStore.save(project, code) }
            .onSuccess { Toast.makeText(this, R.string.project_backup_done, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, it.message ?: "تعذر حفظ النسخة الاحتياطية", Toast.LENGTH_SHORT).show() }
    }

    private fun duplicateProject(project: Project) {
        val now = System.currentTimeMillis()
        val copyName = getString(R.string.duplicate_project_name, project.name)
        val duplicate = project.copy(
            id = now,
            name = copyName,
            inoFileName = safeInoFileName(copyName),
            importedFileName = null,
            sourceUri = null,
            createdAt = now,
            updatedAt = now
        )
        inoFileStore.ensureFile(duplicate).writeText(inoFileStore.read(project))
        projects = (projects + duplicate).sortedByDescending { it.updatedAt }
        projectStore.save(projects)
        loadProjects()
        Toast.makeText(this, R.string.duplicate_project_done, Toast.LENGTH_SHORT).show()
    }

    private fun showProjectDetails(project: Project) {
        val files = inoFileStore.projectFiles(project)
        val lines = files.sumOf { file -> runCatching { file.readLines().size }.getOrDefault(0) }
        val size = files.sumOf { file -> file.length() }
        val message = getString(
            R.string.project_details_message,
            project.inoFileName.orEmpty(),
            files.size,
            lines,
            project.libraries.size,
            size / 1024.0
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(project.name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(project: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, project.name))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                projects = projects.filterNot { it.id == project.id }
                projectStore.save(projects)
                loadProjects()
            }
            .show()
    }

    private fun beginExport(project: Project) {
        pendingExport = project
        val safeName = project.name
            .replace(Regex("[^A-Za-z0-9_ -]"), "")
            .trim()
            .ifBlank { "luma_project" }
        exportLauncher.launch("$safeName.ardx")
    }

    private fun writeArdxArchive(project: Project, output: OutputStream) {
        val files = inoFileStore.projectFiles(project)
        val fileList = JSONArray()
        files.forEach { file ->
            fileList.put(
                JSONObject().apply {
                    put("path", inoFileStore.relativePath(project, file))
                    put("size", file.length())
                }
            )
        }
        val manifest = JSONObject().apply {
            put("format", "Luma ARDX")
            put("version", 2)
            put("name", project.name)
            put("type", project.type.key)
            put("iconKey", project.iconKey)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            put("inoFileName", project.inoFileName ?: "")
            put("libraries", JSONArray(project.libraries.toList()))
            put("files", fileList)
            put("icon", "icon.png")
        }
        ZipOutputStream(output.buffered()).use { zip ->
            fun addBytes(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            addBytes("manifest.json", manifest.toString(2).toByteArray(Charsets.UTF_8))

            BitmapFactory.decodeResource(resources, iconResource(project.iconKey))?.let { bitmap ->
                zip.putNextEntry(ZipEntry("icon.png"))
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bitmap.recycle()
            }

            files.forEach { file ->
                zip.putNextEntry(ZipEntry("files/${inoFileStore.relativePath(project, file)}"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun iconResource(iconKey: String): Int = ProjectIconCatalog.resource(iconKey)

    private fun importArdx(uri: Uri) {
        val displayName = readDisplayName(uri) ?: "Arduino Project.ardx"
        if (!displayName.lowercase().endsWith(".ardx")) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val importedName = displayName.substringBeforeLast('.', displayName)
            .trim()
            .ifBlank { "Arduino Project" }
        val importedProject = Project(
            id = now,
            name = importedName,
            type = ProjectType.CODE,
            iconKey = "chip",
            importedFileName = displayName,
            sourceUri = uri.toString(),
            inoFileName = safeInoFileName(importedName),
            createdAt = now,
            updatedAt = now
        )
        projects = (projects + importedProject).sortedByDescending { it.updatedAt }
        projectStore.save(projects)
        val importedCode = InoFileStore(this).readArdx(uri, contentResolver)
        val importedFile = inoFileStore.ensureFile(importedProject)
        if (!importedCode.isNullOrBlank()) importedFile.writeText(importedCode)
        loadProjects()
        Toast.makeText(this, getString(R.string.import_success, importedName), Toast.LENGTH_SHORT).show()
        openIde(importedProject)
    }

    private fun readDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun openIde(project: Project) {
        val openedProject = project.copy(updatedAt = System.currentTimeMillis())
        projects = projects.map { if (it.id == project.id) openedProject else it }
        projectStore.save(projects)
        inoFileStore.ensureFile(openedProject)
        startActivity(
            Intent(this, IdeActivity::class.java)
                .putExtra(IdeActivity.EXTRA_PROJECT_ID, openedProject.id)
                .putExtra(IdeActivity.EXTRA_PROJECT_NAME, openedProject.name)
                .putExtra(IdeActivity.EXTRA_PROJECT_TYPE, openedProject.type.key)
                .putExtra(IdeActivity.EXTRA_PROJECT_ICON, openedProject.iconKey)
                .putExtra(IdeActivity.EXTRA_INO_FILE, openedProject.inoFileName)
                .putExtra(IdeActivity.EXTRA_CREATED_AT, openedProject.createdAt)
        )
    }

    private fun templateCode(index: Int, projectName: String): String {
        return when (index) {
            1 -> """// $projectName — Blink
const int LED_PIN = LED_BUILTIN;

void setup() {
  pinMode(LED_PIN, OUTPUT);
}

void loop() {
  digitalWrite(LED_PIN, HIGH);
  delay(1000);
  digitalWrite(LED_PIN, LOW);
  delay(1000);
}
"""
            2 -> """// $projectName — Analog Sensor
const int SENSOR_PIN = A0;

void setup() {
  Serial.begin(9600);
}

void loop() {
  int value = analogRead(SENSOR_PIN);
  Serial.println(value);
  delay(100);
}
"""
            3 -> """// $projectName — Serial Hello
void setup() {
  Serial.begin(9600);
  Serial.println(\"Luma is ready\");
}

void loop() {
  if (Serial.available()) {
    String command = Serial.readStringUntil('\\n');
    Serial.print(\"Received: \");
    Serial.println(command);
  }
}
"""
            4 -> """// $projectName — LCD Hello
#include <LiquidCrystal.h>

LiquidCrystal lcd(12, 11, 5, 4, 3, 2);

void setup() {
  lcd.begin(16, 2);
  lcd.print(\"Hello Arduino\");
}

void loop() {
}
"""
            else -> """// $projectName

void setup() {
}

void loop() {
}
"""
        }
    }

    private fun safeInoFileName(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_ -]"), "").replace(Regex("\\s+"), "_")
        return "${safe.ifBlank { "arduino_project" }}.ino"
    }

    private fun iconButtonId(iconKey: String): Int = when (iconKey) {
        "chip" -> R.id.iconChipButton
        "robot" -> R.id.iconRobotButton
        "sensor" -> R.id.iconSensorButton
        "light" -> R.id.iconLightButton
        "rocket" -> R.id.iconRocketButton
        "terminal" -> R.id.iconTerminalButton
        "bolt" -> R.id.iconBoltButton
        "wifi" -> R.id.iconWifiButton
        "motor" -> R.id.iconMotorButton
        "thermo" -> R.id.iconThermoButton
        "display" -> R.id.iconDisplayButton
        "music" -> R.id.iconMusicButton
        "camera" -> R.id.iconCameraButton
        "bluetooth" -> R.id.iconBluetoothButton
        "cloud" -> R.id.iconCloudButton
        "lock" -> R.id.iconLockButton
        "clock" -> R.id.iconClockButton
        "leaf" -> R.id.iconLeafButton
        "satellite" -> R.id.iconSatelliteButton
        else -> R.id.iconSparkButton
    }

    private fun iconKeyFromButton(buttonId: Int): String = when (buttonId) {
        R.id.iconChipButton -> "chip"
        R.id.iconRobotButton -> "robot"
        R.id.iconSensorButton -> "sensor"
        R.id.iconLightButton -> "light"
        R.id.iconRocketButton -> "rocket"
        R.id.iconTerminalButton -> "terminal"
        R.id.iconBoltButton -> "bolt"
        R.id.iconWifiButton -> "wifi"
        R.id.iconMotorButton -> "motor"
        R.id.iconThermoButton -> "thermo"
        R.id.iconDisplayButton -> "display"
        R.id.iconMusicButton -> "music"
        R.id.iconCameraButton -> "camera"
        R.id.iconBluetoothButton -> "bluetooth"
        R.id.iconCloudButton -> "cloud"
        R.id.iconLockButton -> "lock"
        R.id.iconClockButton -> "clock"
        R.id.iconLeafButton -> "leaf"
        R.id.iconSatelliteButton -> "satellite"
        else -> "spark"
    }

    companion object {
        const val EXTRA_REPLAY_TUTORIAL = "replay_interactive_tutorial"
    }
}
