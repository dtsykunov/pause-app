package com.dtsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

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

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // A pause shouldn't linger on the lock screen.
                Intent.ACTION_SCREEN_OFF -> {
                    cancelPending()
                    overlay?.dismissNow()
                    overlay = null
                }
                // Waking back into an app counts as reopening it: re-confirm the foreground
                // so an expired "Open anyway" window pauses again. Clearing currentApp makes
                // the same app read as a fresh foreground. SCREEN_ON covers no-lock devices;
                // USER_PRESENT covers unlock on secure ones. A redundant double-fire is
                // harmless (evaluateForeground returns while the keyguard is still locked).
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    currentApp = null
                    cancelPending()
                    val check = Runnable { evaluateForeground() }
                    pendingCheck = check
                    handler.postDelayed(check, settleDelayMs)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (overlay?.isShowing == true) {
            // Pulling the notification shade down opens it *behind* our accessibility overlay,
            // where it's unusable. The shade isn't a navigation, so just dropping the pause would
            // leave the paused app in front once the shade closes. Treat the pull like Cancel:
            // drop the pause and send the app to the background (no stats — a shade pull isn't a
            // deliberate Cancel). GLOBAL_ACTION_HOME also collapses the shade.
            if (isShadeOpen()) {
                dismissOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
            // A move to any real window other than the paused app (home, recents, another app)
            // means the user navigated away with a system gesture. Drop the pause and let that
            // destination show: no stats, no forced home.
            if (!isTransientWindow(pkg) && pkg != currentApp) {
                dismissOverlay()
            }
            return
        }

        // Debounce, then confirm against the real active window.
        cancelPending()
        val check = Runnable { evaluateForeground() }
        pendingCheck = check
        handler.postDelayed(check, settleDelayMs)
    }

    /** Confirm the real foreground app and intervene if it's a newly-opened paused one. */
    private fun evaluateForeground() {
        if (keyguard.isKeyguardLocked) return
        if (!Prefs.globalEnabled(this)) return
        val active = rootInActiveWindow?.packageName?.toString() ?: return
        if (isTransientWindow(active)) return // system UI, keyboard, our own overlay
        if (active == currentApp) return      // same app: in-app nav, keyboard toggle, dialog, back

        currentApp = active

        if (overlay?.isShowing == true) return
        if (!Prefs.isBlocked(this, active)) return

        val now = System.currentTimeMillis()
        // The allow-window is strictly per app: Open anyway only skips the pause for that app.
        if (now < (allowedUntil[active] ?: 0L)) return

        val lastOpenedAt = Prefs.lastOpenedAt(this, active)
        val attempts = Prefs.recordAttempt(this, active)
        Prefs.incInterruptions(this, active)
        showOverlay(active, attempts, lastOpenedAt, Prefs.pauseSeconds(this), Prefs.phrase(this), Prefs.showTimer(this))
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

    /**
     * True when the notification shade (or quick settings) is pulled down. The expanded shade is a
     * near-fullscreen [AccessibilityWindowInfo.TYPE_SYSTEM] window; the only other system windows
     * are the thin status and navigation bars, so a tall one means the shade is open. (Reading the
     * window list needs FLAG_RETRIEVE_INTERACTIVE_WINDOWS in the service config.)
     */
    private fun isShadeOpen(): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        val bounds = Rect()
        return windows?.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                run { w.getBoundsInScreen(bounds); bounds.height() >= screenH * 0.6 }
        } == true
    }

    private fun showOverlay(pkg: String, attempts: Int, lastOpenedAt: Long?, seconds: Int, phrase: String, showTimer: Boolean) {
        val shownAt = SystemClock.elapsedRealtime()
        overlay = InterventionOverlay(
            service = this,
            appLabel = appLabel(pkg),
            attempts = attempts,
            lastOpenedAt = lastOpenedAt,
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
                Prefs.recordCancel24h(this, pkg)
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

    /** Drop the pause immediately and stop any pending foreground check. */
    private fun dismissOverlay() {
        cancelPending()
        overlay?.dismissNow()
        overlay = null
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
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
