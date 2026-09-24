package com.example.animatedsplash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.animatedsplash.databinding.ActivityGuideBinding

class GuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.guideBackButton.setOnClickListener { finish() }
        binding.replayGuideButton.setOnClickListener {
            getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, false)
                .putInt(LumaApplication.TUTORIAL_FLOW_STAGE_KEY, LumaApplication.TUTORIAL_STAGE_PROJECT)
                .apply()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REPLAY_TUTORIAL, true))
            finish()
        }
    }
}
