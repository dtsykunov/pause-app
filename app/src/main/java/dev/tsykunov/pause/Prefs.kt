package dev.tsykunov.pause

import android.content.Context
import android.content.SharedPreferences

/** All persistence: which apps are paused, the pause duration, and recent open attempts. */
object Prefs {
    private const val FILE = "pause_prefs"
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_DURATION = "pause_seconds"
    private const val ATTEMPTS_PREFIX = "attempts_"
    private const val WINDOW_MS = 24L * 60 * 60 * 1000

    const val DEFAULT_DURATION = 8
    const val MIN_DURATION = 3
    const val MAX_DURATION = 30

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun blockedPackages(c: Context): Set<String> =
        sp(c).getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun setBlockedPackages(c: Context, pkgs: Set<String>) {
        sp(c).edit().putStringSet(KEY_BLOCKED, pkgs).apply()
    }

    fun isBlocked(c: Context, pkg: String): Boolean =
        sp(c).getStringSet(KEY_BLOCKED, emptySet())?.contains(pkg) == true

    fun pauseSeconds(c: Context): Int =
        sp(c).getInt(KEY_DURATION, DEFAULT_DURATION)

    fun setPauseSeconds(c: Context, seconds: Int) {
        sp(c).edit().putInt(KEY_DURATION, seconds).apply()
    }

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
}
