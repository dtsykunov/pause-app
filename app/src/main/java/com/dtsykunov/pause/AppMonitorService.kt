package com.dtsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

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
 *
 * A confirmed session of a paused app ends only when the user genuinely leaves: via the
 * launcher (home/recents), a confirmed fullscreen replacement by another app, or a long screen
 * lock. Foreign windows riding on the session — share sheets, permission dialogs, sign-in
 * popups, invisible trampoline activities — and short locks don't count as reopening.
 */
class AppMonitorService : AccessibilityService() {

    private var overlay: InterventionOverlay? = null

    /** Last confirmed real foreground app (transient/system windows don't count). */
    private var currentApp: String? = null

    /** When the screen first went off after the user left (elapsedRealtime); consumed once
     *  the user is really back past the keyguard. */
    private var screenOffAt: Long? = null

    /** Per-app "skip the pause" deadlines, set after Open anyway or inherited from an allowed app. */
    private val allowedUntil = HashMap<String, Long>()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    private val settleDelayMs = 250L

    /** Extra polls when a check is inconclusive mid-transition: a navigated-away check that
     *  still sees the paused app on top, or a foreground evaluation that still sees the
     *  session app on screen behind a foreign window. */
    private val confirmRetries = 2

    /** An absence shorter than this is a glance away, not the end of the app session. */
    private val longLockMs = 3 * 60_000L

    private var launcherPackagesCache: Set<String> = emptySet()
    private var launcherPackagesFetchedAt = 0L
    private val launcherTtlMs = 30_000L

    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // A pause shouldn't linger on the lock screen.
                Intent.ACTION_SCREEN_OFF -> {
                    // Only the first off matters: lockscreen glances and notification wakes
                    // in between must not restart the absence clock.
                    if (screenOffAt == null) screenOffAt = SystemClock.elapsedRealtime()
                    // A pause the user never answered must re-fire on wake however short the
                    // lock — otherwise power-button + a short wait would dodge it.
                    if (overlay?.isShowing == true) currentApp = null
                    cancelPending()
                    overlay?.dismissNow()
                    overlay = null
                }
                // Waking back into an app after a long absence counts as reopening it:
                // clearing currentApp makes the same app read as a fresh foreground, so an
                // expired "Open anyway" window pauses again. A short absence is a glance away
                // and keeps the session. The absence runs from the first screen-off until the
                // user is really back — keyguard gone — so lockscreen glances and notification
                // wakes neither end it nor restart its clock. SCREEN_ON covers no-lock
                // devices; USER_PRESENT covers unlock on secure ones; a redundant double-fire
                // is harmless (the first past the keyguard consumes the mark).
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (!keyguard.isKeyguardLocked) {
                        val offFor = screenOffAt?.let { SystemClock.elapsedRealtime() - it }
                        screenOffAt = null
                        if (offFor != null && offFor >= longLockMs) currentApp = null
                    }
                    postDelayedCheck { evaluateForeground() }
                }
            }
        }
    }

    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The system can reconnect the same service instance; register only once.
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
            // may mean the user navigated away with a system gesture. But raw events are noisy
            // exactly while the paused app is still launching (launcher transitions, permission
            // prompts, trampoline activities), and dismissing on them makes the pause flicker:
            // each spurious dismiss is followed by a re-show once the app's own events settle.
            // So never dismiss on the raw event; only when the window stack confirms it.
            if (!isTransientWindow(pkg) && pkg != currentApp) {
                // Home and recents gestures land on the launcher, and our overlay covers the
                // recents view until dismissed, so latency here is very visible. When the stack
                // already agrees the user left, dismiss on the spot. Gated to the launcher
                // because short-lived trampoline/dialog windows are also momentarily topmost
                // right when their event fires — but nothing trampolines through home.
                if (pkg in launcherPackages() && navigatedAway()) {
                    dismissOverlay()
                    return
                }
                // Otherwise confirm after a settle. Keep an already-scheduled confirm instead
                // of resetting it — repeated noisy events would push the check out forever.
                if (pendingCheck == null) {
                    postDelayedCheck { confirmNavigatedAway(confirmRetries) }
                }
            }
            return
        }

        // A genuine move away from the confirmed app must not be lost to debounce coalescing —
        // otherwise a fast close+reopen of the same paused app would look unchanged once the
        // debounce settles, and the pause would silently fail to re-trigger. Only a launcher
        // window (home, recents) marks such a leave: foreign events also fire for share
        // sheets, permission dialogs, and invisible trampoline activities riding on a
        // continuing session, and a real switch to another app is confirmed at its own settle
        // and doesn't need the eager reset.
        if (pkg != currentApp && pkg in launcherPackages()) {
            currentApp = null
        }

        // Debounce, then confirm against the real active window.
        postDelayedCheck { evaluateForeground() }
    }

    /** Confirm the real foreground app and intervene if it's a newly-opened paused one. */
    private fun evaluateForeground(retriesLeft: Int = confirmRetries) {
        if (keyguard.isKeyguardLocked) return
        if (!Prefs.globalEnabled(this)) return
        val active = rootInActiveWindow?.packageName?.toString() ?: return
        if (isTransientWindow(active)) return // system UI, keyboard, our own overlay
        if (active == currentApp) return      // same app: in-app nav, keyboard toggle, dialog, back

        // A focused foreign window over a still-visible paused app is a dialog, sheet, or
        // trampoline riding on the session, not a navigation: don't end the session, or the
        // app would wrongly re-pause mid-use once its allow window expired. A genuinely
        // replaced app drops out of the interactive window list when occluded — but it can
        // still be animating out at the settle, so re-check a bounded number of times (with
        // growing delays, so a slow exit animation can't outlast them) before trusting either
        // verdict. Only paused apps have a session worth keeping: the launcher is still
        // listed behind every app-launch animation and must not delay a fresh pause. Nor does
        // another *paused* app in front ride on the session: that's the user entering it
        // (split screen, a paused share target) and it deserves its own pause — a keep here
        // would shield it forever while both stay on screen.
        val session = currentApp
        if (session != null && Prefs.isBlocked(this, session) && !Prefs.isBlocked(this, active)) {
            val retryDelay = settleDelayMs * (1L shl (confirmRetries - retriesLeft + 1))
            if (isOnScreen(session)) {
                if (retriesLeft > 0) postDelayedCheck(retryDelay) { evaluateForeground(retriesLeft - 1) }
                return
            }
            // Mid-transition a real window can briefly report no root, leaving the session
            // app unidentifiable rather than gone: don't end the session on such a read.
            if (retriesLeft > 0 && applicationWindows().any { it.root == null }) {
                postDelayedCheck(retryDelay) { evaluateForeground(retriesLeft - 1) }
                return
            }
        }

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

    /**
     * Debounced check after a foreign window event arrived while the pause was up: dismiss only
     * if a real application window other than the paused app is actually in front. During an app
     * launch the noisy foreign events settle with the paused app still on top, so the pause
     * stays. Mid-transition the stack can also still show the paused app (or nothing) even
     * though the user is navigating away, and no further event may arrive — so an inconclusive
     * read retries a bounded number of times instead of waiting for the next event.
     */
    private fun confirmNavigatedAway(retriesLeft: Int) {
        if (overlay?.isShowing != true) return
        if (navigatedAway()) {
            dismissOverlay()
            return
        }
        if (retriesLeft > 0) {
            postDelayedCheck { confirmNavigatedAway(retriesLeft - 1) }
        }
    }

    /**
     * Topmost application window's package — where the user would land. [rootInActiveWindow]
     * can't make that call while the pause is up (the focused overlay itself is the active
     * window), but our overlay is TYPE_ACCESSIBILITY_OVERLAY and doesn't count here.
     */
    private fun topApplicationPackage(): String? =
        applicationWindows()
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull()

    /** True when [pkg] still has a real application window on screen. */
    private fun isOnScreen(pkg: String): Boolean =
        applicationWindows().any { it.root?.packageName?.toString() == pkg }

    /** The real application windows on screen, topmost first. PiP doesn't count: a persistent
     *  picture-in-picture window is never where the user is — it must not keep a session
     *  alive indefinitely, nor read as the app in front. */
    private fun applicationWindows(): Sequence<AccessibilityWindowInfo> =
        windows.orEmpty().asSequence().filter { w ->
            w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !w.isInPictureInPictureMode)
        }

    /** True when the window stack shows a real app other than the paused one on top. */
    private fun navigatedAway(): Boolean {
        val top = topApplicationPackage() ?: return false
        return !isTransientWindow(top) && top != currentApp
    }

    /** Installed launcher (home) packages. Resolving them is a binder IPC and window events
     *  arrive in bursts, so cache with a short TTL — long enough to keep the event path
     *  cheap, short enough to pick up an installed or switched launcher. Matching any
     *  launcher, not just the default, covers a leave through a non-default one (and a
     *  device with no default set). */
    private fun launcherPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (launcherPackagesFetchedAt == 0L || now - launcherPackagesFetchedAt >= launcherTtlMs) {
            launcherPackagesFetchedAt = now
            launcherPackagesCache = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }
        return launcherPackagesCache
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

    /** Replace any pending check with [action], to run after the settle delay. Clearing
     *  [pendingCheck] on execution is what lets callers see whether one is still scheduled. */
    private fun postDelayedCheck(delayMs: Long = settleDelayMs, action: () -> Unit) {
        cancelPending()
        val check = Runnable {
            pendingCheck = null
            action()
        }
        pendingCheck = check
        handler.postDelayed(check, delayMs)
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
        currentApp = null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cancelPending()
        overlay?.dismissNow()
        overlay = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            receiverRegistered = false
            unregisterReceiver(powerReceiver)
        }
        super.onDestroy()
    }
}
