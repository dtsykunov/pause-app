package dev.tsykunov.pause

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import dev.tsykunov.pause.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val initial = Prefs.pauseSeconds(this).coerceIn(Prefs.MIN_DURATION, Prefs.MAX_DURATION)
        binding.durationSlider.value = initial.toFloat()
        binding.durationLabel.text = getString(R.string.pause_duration, initial)
        binding.durationSlider.addOnChangeListener { _, value, _ ->
            val seconds = value.toInt()
            binding.durationLabel.text = getString(R.string.pause_duration, seconds)
            Prefs.setPauseSeconds(this, seconds)
        }

        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        binding.statusText.text =
            if (isServiceEnabled()) getString(R.string.status_enabled)
            else getString(R.string.status_disabled)
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
                binding.loadingText.visibility = android.view.View.GONE
                binding.appsRecycler.adapter = AppListAdapter(entries) { entry, blockedNow ->
                    val current = Prefs.blockedPackages(this).toMutableSet()
                    if (blockedNow) current.add(entry.packageName) else current.remove(entry.packageName)
                    Prefs.setBlockedPackages(this, current)
                }
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/$packageName.AppMonitorService"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
