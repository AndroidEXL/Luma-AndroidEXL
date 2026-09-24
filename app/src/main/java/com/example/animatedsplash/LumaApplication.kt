package com.example.animatedsplash

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class LumaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val savedMode = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(THEME_MODE_KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)
    }

    companion object {
        const val PREFERENCES_NAME = "luma_settings"
        const val THEME_MODE_KEY = "theme_mode"
        const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
        const val INTERACTIVE_GUIDE_COMPLETE_KEY = "interactive_guide_complete"
        const val TUTORIAL_FLOW_STAGE_KEY = "tutorial_flow_stage"
        const val TUTORIAL_STAGE_PROJECT = 0
        const val TUTORIAL_STAGE_IDE = 1
        const val TUTORIAL_STAGE_COMPLETE = 2
        const val EDITOR_FONT_SIZE_KEY = "editor_font_size"
        const val SAVE_AUTO_KEY = "save_auto_enabled"
        const val KEEP_SCREEN_ON_KEY = "keep_screen_on"
        const val DEFAULT_SERIAL_BAUD_KEY = "default_serial_baud"
        const val DEFAULT_SAVE_AUTO_ENABLED = true
        const val DEFAULT_EDITOR_FONT_SIZE = 14f
        const val DEFAULT_SERIAL_BAUD = 9600
        const val PROJECT_STATE_PREFERENCES = "luma_project_state"
    }
}
