package dev.tsykunov.pause

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.tsykunov.pause.databinding.ActivityStatsBinding
import dev.tsykunov.pause.databinding.ItemStatBinding

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.resetButton.setOnClickListener {
            Prefs.resetStats(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        binding.breathingTotal.text =
            getString(R.string.breathing_total, formatDuration(Prefs.totalBreathingMs(this)))

        val container = binding.statsContainer
        container.removeAllViews()

        val stats = Prefs.allStats(this).sortedByDescending { it.interruptions }
        if (stats.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.stats_empty)
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(getColor(R.color.text_dim))
                setPadding(0, 64, 0, 0)
            }
            container.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (stat in stats) {
            val row = ItemStatBinding.inflate(inflater, container, false)
            row.statAppLabel.text = appLabel(stat.packageName)
            row.statDetails.text = getString(
                R.string.stat_details, stat.interruptions, stat.opens, stat.cancels
            )
            row.statIcon.setImageDrawable(appIcon(stat.packageName))
            container.addView(row.root)
        }
    }

    /** Compact human duration: "2h 5m", "12m", or "45s". */
    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun appIcon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        null
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
