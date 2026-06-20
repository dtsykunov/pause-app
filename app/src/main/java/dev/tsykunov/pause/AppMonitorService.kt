package dev.tsykunov.pause

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for foreground app changes. When a paused app comes to the front it shows the
 * intervention overlay (unless one is already up or the app was just allowed through).
 */
class AppMonitorService : AccessibilityService() {

    private var overlay: InterventionOverlay? = null
    private var lastPackage: String? = null
    private val allowedUntil = HashMap<String, Long>()
    private val graceMs = 20_000L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == packageName) return
        if (overlay?.isShowing == true) return
        if (pkg == lastPackage) return
        lastPackage = pkg

        if (!Prefs.isBlocked(this, pkg)) return

        val now = System.currentTimeMillis()
        if (now < (allowedUntil[pkg] ?: 0L)) return // still within the post-"open anyway" grace window

        val attempts = Prefs.recordAttempt(this, pkg)
        showOverlay(pkg, attempts, Prefs.pauseSeconds(this))
    }

    private fun showOverlay(pkg: String, attempts: Int, seconds: Int) {
        overlay = InterventionOverlay(
            service = this,
            appLabel = appLabel(pkg),
            attempts = attempts,
            seconds = seconds,
            onOpenAnyway = {
                allowedUntil[pkg] = System.currentTimeMillis() + graceMs
                overlay = null
            },
            onClose = {
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
        overlay?.dismissNow()
        overlay = null
        return super.onUnbind(intent)
    }
}
