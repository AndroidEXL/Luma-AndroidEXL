package com.example.animatedsplash

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.example.animatedsplash.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        selectCurrentMode()
        bindThemeMode()
        bindEditorFont()
        bindWorkflowSettings()
        bindGuideActions()
    }

    private fun selectCurrentMode() {
        val currentMode = getSavedThemeMode()
        val buttonId = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.dayModeButton
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.nightModeButton
            else -> R.id.systemModeButton
        }
        binding.appearanceToggleGroup.check(buttonId)
    }

    private fun bindThemeMode() {
        binding.appearanceToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.dayModeButton -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.nightModeButton -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != getSavedThemeMode()) {
                getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(LumaApplication.THEME_MODE_KEY, mode)
                    .apply()
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun bindEditorFont() {
        refreshEditorFontLabel()
        binding.editorFontButton.setOnClickListener {
            val sizes = arrayOf("12", "14", "16", "18")
            val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            val current = preferences.getFloat(
                LumaApplication.EDITOR_FONT_SIZE_KEY,
                LumaApplication.DEFAULT_EDITOR_FONT_SIZE
            )
            val checked = sizes.indexOfFirst { it.toFloat() == current }.coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_font_title)
                .setSingleChoiceItems(sizes, checked) { dialog, which ->
                    preferences.edit()
                        .putFloat(LumaApplication.EDITOR_FONT_SIZE_KEY, sizes[which].toFloat())
                        .apply()
                    refreshEditorFontLabel()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun refreshEditorFontLabel() {
        val size = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getFloat(LumaApplication.EDITOR_FONT_SIZE_KEY, LumaApplication.DEFAULT_EDITOR_FONT_SIZE)
        binding.editorFontButton.text = getString(R.string.editor_font_current, size.toInt().toString())
    }

    private fun bindWorkflowSettings() {
        val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
        binding.autoSaveSwitch.isChecked = preferences.getBoolean(
            LumaApplication.SAVE_AUTO_KEY,
            LumaApplication.DEFAULT_SAVE_AUTO_ENABLED
        )
        binding.keepScreenOnSwitch.isChecked = preferences.getBoolean(LumaApplication.KEEP_SCREEN_ON_KEY, false)
        refreshDefaultBaudLabel()
        binding.autoSaveSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(LumaApplication.SAVE_AUTO_KEY, checked).apply()
        }
        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(LumaApplication.KEEP_SCREEN_ON_KEY, checked).apply()
            if (checked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding.defaultBaudButton.setOnClickListener {
            val rates = arrayOf("9600", "19200", "38400", "57600", "115200")
            val current = preferences.getInt(LumaApplication.DEFAULT_SERIAL_BAUD_KEY, LumaApplication.DEFAULT_SERIAL_BAUD)
            val selected = rates.indexOf(current.toString()).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.default_baud_title)
                .setSingleChoiceItems(rates, selected) { dialog, which ->
                    preferences.edit().putInt(LumaApplication.DEFAULT_SERIAL_BAUD_KEY, rates[which].toInt()).apply()
                    refreshDefaultBaudLabel()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun refreshDefaultBaudLabel() {
        val rate = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(LumaApplication.DEFAULT_SERIAL_BAUD_KEY, LumaApplication.DEFAULT_SERIAL_BAUD)
        binding.defaultBaudButton.text = getString(R.string.default_baud_current, rate)
    }

    private fun bindGuideActions() {
        val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
        binding.openGuideButton.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
        binding.replayTutorialButton.setOnClickListener {
            preferences.edit().putBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, false).apply()
            preferences.edit().putInt(
                LumaApplication.TUTORIAL_FLOW_STAGE_KEY,
                LumaApplication.TUTORIAL_STAGE_PROJECT
            ).apply()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REPLAY_TUTORIAL, true))
            finish()
        }
    }

    private fun getSavedThemeMode(): Int {
        return getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(
                LumaApplication.THEME_MODE_KEY,
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
    }
}
