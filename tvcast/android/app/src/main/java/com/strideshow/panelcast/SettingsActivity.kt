package com.strideshow.panelcast

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.strideshow.panelcast.databinding.ActivitySettingsBinding

/**
 * On-device settings so a wall-mounted panel never needs reinstalling just to
 * point at a different signaling server.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.editUrl.setText(prefs.signalingUrl)
        binding.switchDiag.isChecked = prefs.showDiagnostics
        binding.txtDefault.text = getString(R.string.settings_default, BuildConfig.DEFAULT_SIGNALING_URL)

        // Overscan stepper. Buttons rather than a slider because they are far
        // easier to hit precisely with a TV remote's D-pad.
        renderOverscan()
        binding.btnOverscanDown.setOnClickListener {
            prefs.overscanPercent = prefs.overscanPercent - 1
            renderOverscan()
        }
        binding.btnOverscanUp.setOnClickListener {
            prefs.overscanPercent = prefs.overscanPercent + 1
            renderOverscan()
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnReset.setOnClickListener {
            prefs.resetUrl()
            binding.editUrl.setText(prefs.signalingUrl)
            binding.txtError.visibility = View.GONE
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
        }
        binding.btnBack.setOnClickListener { finish() }

        binding.switchDiag.setOnCheckedChangeListener { _, checked ->
            prefs.showDiagnostics = checked
        }
    }

    /** Show the current value and inset the guide box to match it live. */
    private fun renderOverscan() {
        val pct = prefs.overscanPercent
        binding.txtOverscan.text = getString(R.string.settings_overscan_value, pct)

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        (binding.overscanPreview.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.leftMargin = w * pct / 100
            lp.rightMargin = w * pct / 100
            lp.topMargin = (12 * resources.displayMetrics.density).toInt() + (h * pct / 100)
            binding.overscanPreview.layoutParams = lp
        }
    }

    private fun save() {
        val url = binding.editUrl.text.toString().trim()
        val error = prefs.validateUrl(url)
        if (error != null) {
            binding.txtError.text = error
            binding.txtError.visibility = View.VISIBLE
            return
        }
        binding.txtError.visibility = View.GONE
        prefs.signalingUrl = url
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
