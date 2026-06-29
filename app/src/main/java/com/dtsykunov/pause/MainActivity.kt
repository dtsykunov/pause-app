package com.dtsykunov.pause

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dtsykunov.pause.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.masterRow.setOnClickListener {
            if (!isServiceEnabled()) return@setOnClickListener
            val on = !binding.masterSwitch.isChecked
            binding.masterSwitch.isChecked = on
            Prefs.setGlobalEnabled(this, on)
            updateMasterSubtitle(on)
        }

        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.accessHelp.setOnClickListener {
            showHint(R.string.access_help_title, R.string.access_help_body)
        }

        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.batteryHelp.setOnClickListener { showBatteryHelp() }

        binding.statsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.pausedAppsRow.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        binding.lengthHelp.setOnClickListener {
            showHint(R.string.section_length, R.string.help_length_body)
        }
        binding.allowHelp.setOnClickListener {
            showHint(R.string.section_allow, R.string.help_allow_body)
        }

        val initial = Prefs.pauseSeconds(this).coerceIn(Prefs.MIN_DURATION, Prefs.MAX_DURATION)
        binding.durationSlider.value = initial.toFloat()
        binding.durationLabel.text = getString(R.string.duration_value, initial)
        binding.durationSlider.addOnChangeListener { _, value, _ ->
            val seconds = value.toInt()
            binding.durationLabel.text = getString(R.string.duration_value, seconds)
            Prefs.setPauseSeconds(this, seconds)
        }

        binding.showTimerSwitch.isChecked = Prefs.showTimer(this)
        binding.showTimerRow.setOnClickListener {
            val show = !binding.showTimerSwitch.isChecked
            binding.showTimerSwitch.isChecked = show
            Prefs.setShowTimer(this, show)
        }

        binding.phraseInput.setText(Prefs.phrase(this))
        binding.phraseInput.doAfterTextChanged {
            Prefs.setPhrase(this, it?.toString().orEmpty())
        }
        binding.saveMessageButton.setOnClickListener {
            Prefs.setPhrase(this, binding.phraseInput.text?.toString().orEmpty())
            binding.phraseInput.clearFocus()
            hideKeyboard()
            Toast.makeText(this, R.string.message_saved, Toast.LENGTH_SHORT).show()
        }

        val allow = Prefs.allowMinutes(this).coerceIn(Prefs.MIN_ALLOW_MIN, Prefs.MAX_ALLOW_MIN)
        binding.allowSlider.value = allow.toFloat()
        binding.allowLabel.text = getString(R.string.allow_value, allow)
        binding.allowSlider.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            binding.allowLabel.text = getString(R.string.allow_value, minutes)
            Prefs.setAllowMinutes(this, minutes)
        }
    }

    private fun showHint(titleRes: Int, bodyRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.phraseInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()

        binding.masterRow.isEnabled = enabled
        binding.masterSwitch.isEnabled = enabled
        val on = enabled && Prefs.globalEnabled(this)
        binding.masterSwitch.isChecked = on
        binding.masterTitle.alpha = if (enabled) 1f else 0.5f
        binding.masterSubtitle.text = getString(
            if (!enabled) R.string.master_needs_access else if (on) R.string.master_on else R.string.master_off
        )

        binding.statusText.text =
            getString(if (enabled) R.string.status_on else R.string.status_off)
        binding.accessButton.visibility = if (enabled) View.GONE else View.VISIBLE
        applyStatusCard(binding.accessCard, binding.statusText, enabled)

        // Background-kill mitigation: if Pause isn't battery-optimization exempt the OS can kill
        // the accessibility service and it silently stops pausing apps until re-toggled.
        val exempt = isIgnoringBatteryOptimizations()
        binding.batteryStatusText.text =
            getString(if (exempt) R.string.battery_on else R.string.battery_off)
        binding.batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
        applyStatusCard(binding.batteryCard, binding.batteryStatusText, exempt)

        val count = Prefs.blockedPackages(this).size
        binding.pausedAppsCount.text = resources.getQuantityString(R.plurals.paused_count, count, count)
    }

    /** Tint a status card green when the thing it tracks is ready, rose when it needs attention. */
    private fun applyStatusCard(card: MaterialCardView, label: TextView, ok: Boolean) {
        card.setCardBackgroundColor(
            getColor(if (ok) R.color.status_ok_container else R.color.status_warn_container)
        )
        card.setStrokeColor(
            getColor(if (ok) R.color.status_ok_outline else R.color.status_warn_outline)
        )
        label.setTextColor(getColor(if (ok) R.color.status_ok else R.color.status_warn))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Ask the OS to stop battery-optimizing Pause. The direct dialog needs the
     *  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission; fall back to the settings list otherwise. */
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    /** Manufacturer-specific steps for the OEM "autostart / never sleep" settings that the
     *  battery exemption alone doesn't cover. No AOSP API exists for these, so we guide by text. */
    private fun showBatteryHelp() {
        val body = when (Build.MANUFACTURER.lowercase()) {
            "samsung" -> R.string.battery_help_samsung
            "xiaomi", "redmi", "poco" -> R.string.battery_help_xiaomi
            "oppo", "realme", "oneplus" -> R.string.battery_help_oppo
            "vivo", "iqoo" -> R.string.battery_help_vivo
            "huawei", "honor" -> R.string.battery_help_huawei
            else -> R.string.battery_help_generic
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_help_title)
            .setMessage(body)
            .setPositiveButton(R.string.got_it, null)
            .setNeutralButton(R.string.battery_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.battery_more_help) { _, _ -> openDontKillMyApp() }
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    private fun openDontKillMyApp() {
        val slug = Build.MANUFACTURER.lowercase().replace(' ', '-')
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$slug")))
        } catch (_: Exception) {
        }
    }

    private fun updateMasterSubtitle(on: Boolean) {
        binding.masterSubtitle.text = getString(if (on) R.string.master_on else R.string.master_off)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, AppMonitorService::class.java).flattenToString()
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
