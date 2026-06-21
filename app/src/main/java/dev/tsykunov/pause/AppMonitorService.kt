package dev.tsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for foreground app changes. When a paused app genuinely comes to the front it shows
 * the intervention overlay.
 *
 * Window-state-change events are noisy: an app can emit them while being *backgrounded*, so we
 * never act on the raw event. Instead we wait briefly for things to settle, then confirm what
 * the real active window is via [rootInActiveWindow]. This distinguishes a deliberate launch
 * from stray background chatter without relying on time-based cooldowns.
 */
class AppMonitorService : AccessibilityService() {

    private var overlay: InterventionOverlay? = null

    /** The last app we confirmed as the real foreground window. */
    private var lastForeground: String? = null

    /** Per-app "don't re-prompt" deadline, set after the user chooses to open anyway. */
    private val allowedUntil = HashMap<String, Long>()
    private val graceMs = 20_000L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private val settleDelayMs = 250L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == packageName) return
        if (overlay?.isShowing == true) return
        if (pkg == lastForeground) return // already the known foreground app

        pendingCheck?.let { handler.removeCallbacks(it) }
        val check = Runnable { evaluateForeground() }
        pendingCheck = check
        handler.postDelayed(check, settleDelayMs)
    }

    /** Confirm the real foreground app and intervene if it's a paused one. */
    private fun evaluateForeground() {
        val active = rootInActiveWindow?.packageName?.toString() ?: return
        if (active == packageName) return
        lastForeground = active

        if (overlay?.isShowing == true) return
        if (!Prefs.isBlocked(this, active)) return
        if (System.currentTimeMillis() < (allowedUntil[active] ?: 0L)) return

        val attempts = Prefs.recordAttempt(this, active)
        Prefs.incInterruptions(this, active)
        showOverlay(active, attempts, Prefs.pauseSeconds(this), Prefs.phrase(this))
    }

    private fun showOverlay(pkg: String, attempts: Int, seconds: Int, phrase: String) {
        overlay = InterventionOverlay(
            service = this,
            appLabel = appLabel(pkg),
            attempts = attempts,
            seconds = seconds,
            phrase = phrase,
            onOpenAnyway = {
                Prefs.incOpens(this, pkg)
                allowedUntil[pkg] = System.currentTimeMillis() + graceMs
                overlay = null
            },
            onClose = {
                Prefs.incCancels(this, pkg)
                overlay = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        ).also { it.show() }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        pendingCheck?.let { handler.removeCallbacks(it) }
        overlay?.dismissNow()
        overlay = null
        return super.onUnbind(intent)
    }
}
