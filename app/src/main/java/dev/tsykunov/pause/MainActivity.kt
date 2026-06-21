package dev.tsykunov.pause

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import dev.tsykunov.pause.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.accessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.statsButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        binding.pausedAppsRow.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.phraseInput.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        binding.statusText.text =
            getString(if (enabled) R.string.status_on else R.string.status_off)
        binding.statusText.setTextColor(
            getColor(if (enabled) R.color.breath else R.color.text_dim)
        )
        binding.accessButton.visibility = if (enabled) View.GONE else View.VISIBLE

        val count = Prefs.blockedPackages(this).size
        binding.pausedAppsCount.text = resources.getQuantityString(R.plurals.paused_count, count, count)
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
