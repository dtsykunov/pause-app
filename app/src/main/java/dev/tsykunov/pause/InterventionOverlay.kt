package dev.tsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import dev.tsykunov.pause.databinding.OverlayInterventionBinding
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
    private val seconds: Int,
    private val onOpenAnyway: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val binding = OverlayInterventionBinding.inflate(LayoutInflater.from(service))

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

        binding.panel.post {
            val h = binding.panel.height.toFloat()
            binding.panel.translationY = h
            binding.panel.animate().translationY(0f).setDuration(420).start()
            startBreathing()
            startCountdown()
        }
    }

    private fun bindTexts() {
        binding.openingText.text =
            binding.root.context.getString(R.string.overlay_opening, appLabel)
        binding.attemptsText.text = if (attempts <= 1) {
            binding.root.context.getString(R.string.overlay_attempts_one)
        } else {
            binding.root.context.getString(R.string.overlay_attempts_many, attempts)
        }
        binding.countdownText.text = seconds.toString()
    }

    private fun startBreathing() {
        val anim = ScaleAnimation(
            0.6f, 1f, 0.6f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 2600
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
        binding.buttonsRow.alpha = 0f
        binding.buttonsRow.visibility = View.VISIBLE
        binding.buttonsRow.animate().alpha(1f).setDuration(300).start()
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
