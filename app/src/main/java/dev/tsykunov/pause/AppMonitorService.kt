package dev.tsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for foreground app changes and shows the pause overlay when a paused app genuinely
 * comes to the front.
 *
 * Window-state-change events are noisy — they fire for the keyboard, system UI, in-app
 * navigation, back gestures, recents, dialogs, and apps being backgrounded. So we never act on
 * the raw event: after a short settle we read the real active window ([rootInActiveWindow]),
 * ignore transient/system windows, and only react when the *confirmed* foreground app actually
 * changes to a different real app. A per-app allow-window after "Open anyway" prevents nagging
 * during that app's session.
 */
class AppMonitorService : AccessibilityService() {

    private var overlay: InterventionOverlay? = null

    /** Last confirmed real foreground app (transient/system windows don't count). */
    private var currentApp: String? = null

    /** Per-app "skip the pause" deadlines, set after Open anyway or inherited from an allowed app. */
    private val allowedUntil = HashMap<String, Long>()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private val settleDelayMs = 250L

    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A pause shouldn't linger on the lock screen.
            cancelPending()
            overlay?.dismissNow()
            overlay = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (overlay?.isShowing == true) return

        // Debounce, then confirm against the real active window.
        cancelPending()
        val check = Runnable { evaluateForeground() }
        pendingCheck = check
        handler.postDelayed(check, settleDelayMs)
    }

    /** Confirm the real foreground app and intervene if it's a newly-opened paused one. */
    private fun evaluateForeground() {
        if (keyguard.isKeyguardLocked) return
        val active = rootInActiveWindow?.packageName?.toString() ?: return
        if (isTransientWindow(active)) return // system UI, keyboard, our own overlay
        if (active == currentApp) return      // same app: in-app nav, keyboard toggle, dialog, back

        currentApp = active

        if (overlay?.isShowing == true) return
        if (!Prefs.isBlocked(this, active)) return

        val now = System.currentTimeMillis()
        // The allow-window is strictly per app: Open anyway only skips the pause for that app.
        if (now < (allowedUntil[active] ?: 0L)) return

        val attempts = Prefs.recordAttempt(this, active)
        Prefs.incInterruptions(this, active)
        showOverlay(active, attempts, Prefs.pauseSeconds(this), Prefs.phrase(this), Prefs.showTimer(this))
    }

    private fun allowWindowMs(): Long = Prefs.allowMinutes(this) * 60_000L

    private fun isTransientWindow(pkg: String): Boolean =
        pkg == packageName ||
            pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg == currentImePackage()

    private fun currentImePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotEmpty() }

    private fun showOverlay(pkg: String, attempts: Int, seconds: Int, phrase: String, showTimer: Boolean) {
        val shownAt = SystemClock.elapsedRealtime()
        overlay = InterventionOverlay(
            service = this,
            appLabel = appLabel(pkg),
            attempts = attempts,
            seconds = seconds,
            phrase = phrase,
            showTimer = showTimer,
            onOpenAnyway = {
                Prefs.incOpens(this, pkg)
                recordBreathing(pkg, shownAt, seconds)
                allowedUntil[pkg] = System.currentTimeMillis() + allowWindowMs()
                overlay = null
            },
            onClose = {
                Prefs.incCancels(this, pkg)
                recordBreathing(pkg, shownAt, seconds)
                overlay = null
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        ).also { it.show() }
    }

    /** Count only the forced pause time, never the deliberation after "Open anyway" appears. */
    private fun recordBreathing(pkg: String, shownAt: Long, seconds: Int) {
        val elapsed = SystemClock.elapsedRealtime() - shownAt
        Prefs.addBreathingMs(this, pkg, minOf(elapsed, seconds * 1000L))
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun cancelPending() {
        pendingCheck?.let { handler.removeCallbacks(it) }
        pendingCheck = null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cancelPending()
        overlay?.dismissNow()
        overlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
