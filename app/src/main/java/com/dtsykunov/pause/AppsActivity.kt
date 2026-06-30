package com.dtsykunov.pause

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.dtsykunov.pause.databinding.ActivityAppsBinding
import kotlin.concurrent.thread

/** The full list of launchable apps, searchable, where you choose which to pause. */
class AppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsBinding
    private lateinit var adapter: AppListAdapter
    private var allEntries: List<AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppListAdapter(emptyList()) { entry, blockedNow ->
            val current = Prefs.blockedPackages(this).toMutableSet()
            if (blockedNow) current.add(entry.packageName) else current.remove(entry.packageName)
            Prefs.setBlockedPackages(this, current)
        }
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = adapter

        binding.searchInput.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        loadApps()
    }

    private fun loadApps() {
        thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val blocked = Prefs.blockedPackages(this)
            val entries = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppEntry(
                            packageName = pkg,
                            label = pm.getApplicationLabel(info).toString(),
                            icon = pm.getApplicationIcon(info),
                            blocked = blocked.contains(pkg),
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allEntries = entries
                binding.loadingText.visibility = View.GONE
                applyFilter(binding.searchInput.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allEntries
        else allEntries.filter { it.label.contains(q, ignoreCase = true) }
        adapter.update(filtered)
        binding.emptyText.visibility =
            if (filtered.isEmpty() && allEntries.isNotEmpty()) View.VISIBLE else View.GONE
    }
}
