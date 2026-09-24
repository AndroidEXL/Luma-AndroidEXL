package com.example.animatedsplash

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.animatedsplash.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val openMainRunnable = Runnable { openMainScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prepareInitialState()
        playEntranceAnimation()
    }

    private fun prepareInitialState() = with(binding) {
        logoContainer.apply {
            alpha = 0f
            scaleX = 0.62f
            scaleY = 0.62f
            rotation = -12f
        }
        contentGroup.translationY = 28f
        contentGroup.alpha = 0f
        orbTop.alpha = 0f
        orbBottom.alpha = 0f
    }

    private fun playEntranceAnimation() = with(binding) {
        val logoAnimation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoContainer, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(logoContainer, View.SCALE_X, 0.62f, 1.08f, 1f),
                ObjectAnimator.ofFloat(logoContainer, View.SCALE_Y, 0.62f, 1.08f, 1f),
                ObjectAnimator.ofFloat(logoContainer, View.ROTATION, -12f, 4f, 0f)
            )
            duration = 900L
            interpolator = android.view.animation.OvershootInterpolator(1.25f)
        }

        val contentAnimation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(contentGroup, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(contentGroup, View.TRANSLATION_Y, 28f, 0f)
            )
            duration = 650L
            startDelay = 420L
            interpolator = android.view.animation.DecelerateInterpolator()
        }

        val orbAnimation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(orbTop, View.ALPHA, 0f, 0.9f),
                ObjectAnimator.ofFloat(orbBottom, View.ALPHA, 0f, 0.8f),
                ObjectAnimator.ofFloat(orbTop, View.TRANSLATION_Y, 18f, 0f),
                ObjectAnimator.ofFloat(orbBottom, View.TRANSLATION_Y, -18f, 0f)
            )
            duration = 1000L
            startDelay = 250L
        }

        AnimatorSet().apply {
            playTogether(logoAnimation, contentAnimation, orbAnimation)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startFloatingLogo()
                }
            })
            start()
        }

        handler.postDelayed(openMainRunnable, 2600L)
    }

    private fun startFloatingLogo() {
        val floating = ObjectAnimator.ofFloat(binding.logoContainer, View.TRANSLATION_Y, 0f, -8f, 0f).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        floating.start()
    }

    private fun openMainScreen() {
        val completed = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(LumaApplication.ONBOARDING_COMPLETE_KEY, false)
        val destination = if (completed) MainActivity::class.java else WelcomeActivity::class.java
        startActivity(Intent(this, destination))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMainRunnable)
        super.onDestroy()
    }
}
