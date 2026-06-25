package com.dtsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.os.CountDownTimer
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import com.dtsykunov.pause.databinding.OverlayInterventionBinding
import kotlin.math.ceil

/**
 * The full-screen "screen cover" shown over a paused app: a panel slides up to cover the
 * screen, a breathing circle pulses while a countdown runs, then the choice buttons appear.
 * The panel slides back down when dismissed.
 */
class InterventionOverlay(
    private val service: AccessibilityService,
    private val appLabel: String,
    private val attempts: Int,
    private val lastOpenedAt: Long?,
    private val seconds: Int,
    private val phrase: String,
    private val showTimer: Boolean,
    private val onOpenAnyway: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    // Material widgets require a Material theme on the inflating context; the bare service
    // context has none, so wrap it in the app theme.
    private val themedContext = ContextThemeWrapper(service, R.style.Theme_Pause)
    private val binding = OverlayInterventionBinding.inflate(LayoutInflater.from(themedContext))

    var isShowing = false
        private set

    private var timer: CountDownTimer? = null
    private var removed = false

    fun show() {
        bindTexts()

        binding.closeButton.setOnClickListener { onClose(); slideOutAndRemove() }
        binding.openButton.setOnClickListener { onOpenAnyway(); slideOutAndRemove() }

        // Let the overlay handle the back key as "not now".
        binding.root.isFocusableInTouchMode = true
        binding.root.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                onClose(); slideOutAndRemove(); true
            } else false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        wm.addView(binding.root, params)
        isShowing = true
        binding.root.requestFocus()

        // Cover the screen instantly so the app behind isn't briefly visible; ease the
        // content in rather than sliding the whole panel up.
        binding.content.alpha = 0f
        binding.content.post {
            binding.content.animate().alpha(1f).setDuration(250).start()
            startBreathing()
            startCountdown()
        }
    }

    private fun bindTexts() {
        binding.openingText.text =
            binding.root.context.getString(R.string.overlay_opening, appLabel)
        val ctx = binding.root.context
        val sinceLast = lastOpenedAt?.let { System.currentTimeMillis() - it }?.takeIf { it >= 0 }
        binding.attemptsText.text = when {
            sinceLast != null && attempts <= 1 ->
                ctx.getString(R.string.overlay_last_opened_one, formatAgo(sinceLast))
            sinceLast != null ->
                ctx.getString(R.string.overlay_last_opened_many, formatAgo(sinceLast), attempts)
            attempts <= 1 -> ctx.getString(R.string.overlay_attempts_one)
            else -> ctx.getString(R.string.overlay_attempts_many, attempts)
        }
        binding.countdownText.text = seconds.toString()
        binding.countdownText.visibility = if (showTimer) View.VISIBLE else View.GONE
        binding.breatheText.text = phrase
    }

    /** Compact elapsed time for "Last opened … ago": "45s", "10m", "3h", "2d". */
    private fun formatAgo(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86400 -> "${sec / 3600}h"
            else -> "${sec / 86400}d"
        }
    }

    private fun startBreathing() {
        val anim = ScaleAnimation(
            0.72f, 1f, 0.72f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            // ~5s per phase => ~10s inhale/exhale cycle, a slow deep-breathing pace.
            duration = 5000
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        binding.breathCircle.startAnimation(anim)
    }

    private fun startCountdown() {
        timer = object : CountDownTimer(seconds * 1000L, 100) {
            override fun onTick(msLeft: Long) {
                binding.countdownText.text = ceil(msLeft / 1000.0).toInt().coerceAtLeast(1).toString()
            }

            override fun onFinish() {
                binding.countdownText.text = "0"
                revealButtons()
            }
        }.start()
    }

    private fun revealButtons() {
        binding.openButton.alpha = 0f
        binding.openButton.visibility = View.VISIBLE
        binding.openButton.animate().alpha(1f).setDuration(300).start()
    }

    private fun slideOutAndRemove() {
        timer?.cancel()
        val h = binding.panel.height.toFloat()
        binding.panel.animate()
            .translationY(h)
            .setDuration(360)
            .withEndAction { removeView() }
            .start()
    }

    /** Remove immediately without animation (e.g. when the service is shutting down). */
    fun dismissNow() {
        timer?.cancel()
        removeView()
    }

    private fun removeView() {
        if (removed) return
        removed = true
        isShowing = false
        try {
            wm.removeView(binding.root)
        } catch (_: Exception) {
        }
    }
}
