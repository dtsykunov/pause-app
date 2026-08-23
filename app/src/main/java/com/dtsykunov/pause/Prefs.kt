package com.dtsykunov.pause

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/** All persistence: which apps are paused, the pause duration, and recent open attempts. */
object Prefs {
    private const val FILE = "pause_prefs"
    private const val KEY_GLOBAL_ENABLED = "global_enabled"
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_DURATION = "pause_seconds"
    private const val KEY_DURATION_MODE = "duration_mode"
    private const val KEY_DURATION_MIN = "duration_min"
    private const val KEY_DURATION_MAX = "duration_max"
    private const val KEY_PHRASE = "pause_phrase"
    private const val KEY_SHOW_TIMER = "show_timer"
    private const val KEY_ALLOW_MIN = "allow_minutes"
    private const val KEY_PAUSE_AGAIN = "pause_again"
    private const val KEY_STAT_PKGS = "stat_packages"
    private const val STAT_INTERRUPTIONS = "si_"
    private const val STAT_OPENS = "so_"
    private const val STAT_CANCELS = "sc_"
    private const val STAT_BREATH_MS = "bms_"
    private const val STAT_LAST_OPEN = "slo_"
    private const val ATTEMPTS_PREFIX = "attempts_"
    private const val CANCELS_WINDOW_PREFIX = "cancels24_"
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    const val DEFAULT_DURATION = 10
    const val MIN_DURATION = 3
    const val MAX_DURATION = 30
    const val MODE_FIXED = "fixed"
    const val MODE_RANDOM = "random"
    const val DEFAULT_DURATION_MIN = 5
    const val DEFAULT_DURATION_MAX = 20
    const val DEFAULT_PHRASE = "Take a moment"
    const val DEFAULT_ALLOW_MIN = 15
    const val MIN_ALLOW_MIN = 1
    const val MAX_ALLOW_MIN = 60

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun globalEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_GLOBAL_ENABLED, true)

    fun setGlobalEnabled(c: Context, on: Boolean) { sp(c).edit().putBoolean(KEY_GLOBAL_ENABLED, on).apply() }

    fun blockedPackages(c: Context): Set<String> =
        sp(c).getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun setBlockedPackages(c: Context, pkgs: Set<String>) {
        sp(c).edit().putStringSet(KEY_BLOCKED, pkgs).apply()
    }

    fun isBlocked(c: Context, pkg: String): Boolean =
        sp(c).getStringSet(KEY_BLOCKED, emptySet())?.contains(pkg) == true

    fun pauseSeconds(c: Context): Int =
        sp(c).getInt(KEY_DURATION, DEFAULT_DURATION).coerceIn(MIN_DURATION, MAX_DURATION)

    fun setPauseSeconds(c: Context, seconds: Int) {
        sp(c).edit().putInt(KEY_DURATION, seconds).apply()
    }

    /** "fixed" or "random"; falls back to "fixed" for a missing or unrecognized value so an
     *  install that has never set this (or a downgrade reading a mode it doesn't know) keeps
     *  today's single-duration behavior. */
    fun durationMode(c: Context): String =
        if (sp(c).getString(KEY_DURATION_MODE, MODE_FIXED) == MODE_RANDOM) MODE_RANDOM else MODE_FIXED

    fun setDurationMode(c: Context, mode: String) {
        sp(c).edit().putString(KEY_DURATION_MODE, mode).apply()
    }

    fun durationMin(c: Context): Int =
        sp(c).getInt(KEY_DURATION_MIN, DEFAULT_DURATION_MIN).coerceIn(MIN_DURATION, MAX_DURATION)

    fun durationMax(c: Context): Int =
        sp(c).getInt(KEY_DURATION_MAX, DEFAULT_DURATION_MAX).coerceIn(MIN_DURATION, MAX_DURATION)

    /** Stores [min]/[max] clamped to the allowed bounds, with max never below min. */
    fun setDurationRange(c: Context, min: Int, max: Int) {
        val lo = min.coerceIn(MIN_DURATION, MAX_DURATION)
        val hi = max.coerceIn(MIN_DURATION, MAX_DURATION).coerceAtLeast(lo)
        sp(c).edit().putInt(KEY_DURATION_MIN, lo).putInt(KEY_DURATION_MAX, hi).apply()
    }

    /** The pause length to use for the next pause: the fixed value, or a fresh pick within the
     *  configured range each time this is called. */
    fun resolvePauseSeconds(c: Context): Int = when (durationMode(c)) {
        MODE_RANDOM -> {
            val lo = durationMin(c)
            val hi = durationMax(c).coerceAtLeast(lo)
            if (lo == hi) lo else Random.nextInt(lo, hi + 1)
        }
        else -> pauseSeconds(c)
    }

    /** The message shown during the pause; falls back to the default when blank. */
    fun phrase(c: Context): String {
        val saved = sp(c).getString(KEY_PHRASE, null)?.trim()
        return if (saved.isNullOrEmpty()) DEFAULT_PHRASE else saved
    }

    fun setPhrase(c: Context, phrase: String) {
        sp(c).edit().putString(KEY_PHRASE, phrase).apply()
    }

    fun showTimer(c: Context): Boolean = sp(c).getBoolean(KEY_SHOW_TIMER, false)

    fun setShowTimer(c: Context, show: Boolean) {
        sp(c).edit().putBoolean(KEY_SHOW_TIMER, show).apply()
    }

    /** Minutes after "Open anyway" during which the app won't be paused again. */
    fun allowMinutes(c: Context): Int =
        sp(c).getInt(KEY_ALLOW_MIN, DEFAULT_ALLOW_MIN).coerceIn(MIN_ALLOW_MIN, MAX_ALLOW_MIN)

    fun setAllowMinutes(c: Context, minutes: Int) {
        sp(c).edit().putInt(KEY_ALLOW_MIN, minutes).apply()
    }

    /** When true, the end of an app's allow window shows the pause again, instead of only
     *  mattering the next time that app is opened. Off keeps today's behavior exactly. */
    fun pauseAgainWhenAllowEnds(c: Context): Boolean = sp(c).getBoolean(KEY_PAUSE_AGAIN, false)

    fun setPauseAgainWhenAllowEnds(c: Context, on: Boolean) {
        sp(c).edit().putBoolean(KEY_PAUSE_AGAIN, on).apply()
    }

    /** When [pkg] was last actually opened (via "Open anyway"), or null if never. */
    fun lastOpenedAt(c: Context, pkg: String): Long? =
        sp(c).getLong(STAT_LAST_OPEN + pkg, 0L).takeIf { it > 0 }

    /** Record an open attempt for [pkg] and return the number of attempts in the last 24h. */
    fun recordAttempt(c: Context, pkg: String): Int {
        val now = System.currentTimeMillis()
        val key = ATTEMPTS_PREFIX + pkg
        val kept = (sp(c).getString(key, "") ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS }
            .toMutableList()
        kept.add(now)
        sp(c).edit().putString(key, kept.joinToString(",")).apply()
        return kept.size
    }

    /** Cancel taps for [pkg] in the last 24h (read without recording). */
    fun cancelsInWindow(c: Context, pkg: String): Int {
        val now = System.currentTimeMillis()
        return (sp(c).getString(CANCELS_WINDOW_PREFIX + pkg, "") ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .count { now - it < WINDOW_MS }
    }

    /** Record a cancel tap for [pkg] in the 24h window. */
    fun recordCancel24h(c: Context, pkg: String) {
        val now = System.currentTimeMillis()
        val key = CANCELS_WINDOW_PREFIX + pkg
        val kept = (sp(c).getString(key, "") ?: "")
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS }
            .toMutableList()
        kept.add(now)
        sp(c).edit().putString(key, kept.joinToString(",")).apply()
    }

    // ---- Per-app lifetime stats ----

    data class AppStat(
        val packageName: String,
        val interruptions: Int,
        val opens: Int,
        val cancels: Int,
    )

    private fun statPackages(c: Context): Set<String> =
        sp(c).getStringSet(KEY_STAT_PKGS, emptySet())?.toSet() ?: emptySet()

    private fun inc(c: Context, prefix: String, pkg: String) {
        val key = prefix + pkg
        val pkgs = statPackages(c).toMutableSet().apply { add(pkg) }
        sp(c).edit()
            .putInt(key, sp(c).getInt(key, 0) + 1)
            .putStringSet(KEY_STAT_PKGS, pkgs)
            .apply()
    }

    fun incInterruptions(c: Context, pkg: String) = inc(c, STAT_INTERRUPTIONS, pkg)
    fun incCancels(c: Context, pkg: String) = inc(c, STAT_CANCELS, pkg)

    fun incOpens(c: Context, pkg: String) {
        inc(c, STAT_OPENS, pkg)
        sp(c).edit().putLong(STAT_LAST_OPEN + pkg, System.currentTimeMillis()).apply()
    }

    /** Add the time the pause screen was shown for [pkg], in milliseconds. */
    fun addBreathingMs(c: Context, pkg: String, ms: Long) {
        if (ms <= 0) return
        val key = STAT_BREATH_MS + pkg
        val pkgs = statPackages(c).toMutableSet().apply { add(pkg) }
        sp(c).edit()
            .putLong(key, sp(c).getLong(key, 0) + ms)
            .putStringSet(KEY_STAT_PKGS, pkgs)
            .apply()
    }

    /** Total time spent on the pause screen across all apps, in milliseconds. */
    fun totalBreathingMs(c: Context): Long =
        statPackages(c).sumOf { sp(c).getLong(STAT_BREATH_MS + it, 0L) }

    /** Total "Open anyway" taps across all apps. */
    fun totalOpens(c: Context): Int =
        statPackages(c).sumOf { sp(c).getInt(STAT_OPENS + it, 0) }

    /** Total "Cancel" taps across all apps. */
    fun totalCancels(c: Context): Int =
        statPackages(c).sumOf { sp(c).getInt(STAT_CANCELS + it, 0) }

    /** Stats for every app that has any recorded activity. */
    fun allStats(c: Context): List<AppStat> = statPackages(c).map { pkg ->
        AppStat(
            packageName = pkg,
            interruptions = sp(c).getInt(STAT_INTERRUPTIONS + pkg, 0),
            opens = sp(c).getInt(STAT_OPENS + pkg, 0),
            cancels = sp(c).getInt(STAT_CANCELS + pkg, 0),
        )
    }

    fun resetStats(c: Context) {
        val edit = sp(c).edit()
        for (pkg in statPackages(c)) {
            edit.remove(STAT_INTERRUPTIONS + pkg)
            edit.remove(STAT_OPENS + pkg)
            edit.remove(STAT_CANCELS + pkg)
            edit.remove(STAT_BREATH_MS + pkg)
            edit.remove(STAT_LAST_OPEN + pkg)
            // Also clear the 24h open/cancel history that the pause screen counts from.
            edit.remove(ATTEMPTS_PREFIX + pkg)
            edit.remove(CANCELS_WINDOW_PREFIX + pkg)
        }
        edit.remove(KEY_STAT_PKGS).apply()
    }
}
