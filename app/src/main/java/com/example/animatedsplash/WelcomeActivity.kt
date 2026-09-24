package com.example.animatedsplash

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.animatedsplash.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selectedFiles: List<Uri> ->
        if (selectedFiles.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.files_selected, selectedFiles.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prepareEntrance()
        bindActions()
    }

    private fun prepareEntrance() = with(binding) {
        chipContainer.alpha = 0f
        chipContainer.scaleX = 0.72f
        chipContainer.scaleY = 0.72f
        welcomeEyebrow.alpha = 0f
        welcomeTitle.alpha = 0f
        welcomeMessage.alpha = 0f
        nextButton.alpha = 0f

        chipContainer.animate()
            .alpha(1f)
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(520L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .withEndAction {
                chipContainer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()

        animateIn(welcomeEyebrow, 180L, 380L)
        animateIn(welcomeTitle, 260L, 430L)
        animateIn(welcomeMessage, 340L, 480L)
        animateIn(nextButton, 430L, 520L)
    }

    private fun animateIn(view: View, delay: Long, duration: Long) {
        view.translationY = 24f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun bindActions() = with(binding) {
        nextButton.setOnClickListener { showPermissionsPage() }
        openSettingsButton.setOnClickListener { openInstallPermissionSettings() }
        fileAccessButton.setOnClickListener { openFilePicker() }
        continueButton.setOnClickListener {
            if (!canInstallPackages()) {
                Toast.makeText(
                    this@WelcomeActivity,
                    R.string.permission_continue_hint,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(LumaApplication.ONBOARDING_COMPLETE_KEY, true)
                .apply()
            startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private fun showPermissionsPage() = with(binding) {
        welcomePage.animate()
            .alpha(0f)
            .translationX(-70f)
            .setDuration(240L)
            .withEndAction {
                welcomePage.visibility = View.GONE
                permissionsPage.visibility = View.VISIBLE
                permissionsPage.alpha = 0f
                permissionsPage.translationX = 70f
                nextButton.visibility = View.GONE
                continueButton.alpha = 0f
                continueButton.visibility = View.VISIBLE
                stepTwo.animate().alpha(1f).setDuration(180L).start()
                permissionsPage.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(360L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                continueButton.animate()
                    .alpha(1f)
                    .setDuration(320L)
                    .start()
            }
            .start()
    }

    private fun openFilePicker() {
        filePicker.launch(arrayOf("*/*"))
    }

    private fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(settingsIntent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        } else {
            Toast.makeText(this, getString(R.string.settings_not_available), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val enabled = packageManager.canRequestPackageInstalls()
            binding.openSettingsButton.alpha = if (enabled) 0.55f else 1f
        }
    }
}
