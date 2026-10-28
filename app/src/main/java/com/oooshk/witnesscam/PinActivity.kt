package com.oooshk.witnesscam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.oooshk.witnesscam.databinding.ActivityPinBinding

/**
 * Asks for the PIN before a protected action. Shown over the lock screen so a recording started
 * from the lock screen can't be stopped there without the PIN.
 *
 * Modes:
 *  - [MODE_STOP]: stops the recording when the PIN is correct (or immediately if no PIN is set).
 *  - [MODE_VERIFY]: returns RESULT_OK to the caller when the PIN is correct.
 */
class PinActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinBinding
    private lateinit var prefs: Prefs
    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_STOP }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        prefs = Prefs(this)

        if (savedInstanceState == null && mode == MODE_STOP && !RecordWidgetProvider.consumeArmedTap(this, intent)) {
            finish()
            return
        }
        if (!prefs.hasPin) {
            succeed()
            return
        }

        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.setText(if (mode == MODE_STOP) R.string.pin_title_stop else R.string.pin_title_verify)
        binding.ok.setOnClickListener { submit() }
        binding.cancel.setOnClickListener { finish() }
        binding.pin.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    private fun submit() {
        val now = System.currentTimeMillis()
        if (now < prefs.pinLockedUntilMs) {
            showError(getString(R.string.pin_locked, (prefs.pinLockedUntilMs - now) / 1000 + 1))
            return
        }
        if (prefs.checkPin(binding.pin.text.toString())) {
            prefs.pinFailures = 0
            succeed()
            return
        }
        binding.pin.text = null
        val failures = prefs.pinFailures + 1
        prefs.pinFailures = failures
        if (failures >= MAX_ATTEMPTS) {
            // Lockout doubles with every further batch of failures: 30s, 60s, 120s, ...
            val seconds = 30L shl ((failures / MAX_ATTEMPTS) - 1).coerceAtMost(6)
            prefs.pinLockedUntilMs = now + seconds * 1000
            showError(getString(R.string.pin_locked, seconds))
        } else {
            showError(getString(R.string.pin_wrong))
        }
    }

    private fun showError(msg: String) {
        binding.error.text = msg
        binding.error.isVisible = true
    }

    private fun succeed() {
        if (mode == MODE_STOP) RecordingService.stop(this)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_STOP = "stop"
        const val MODE_VERIFY = "verify"
        private const val MAX_ATTEMPTS = 5

        fun stopIntent(context: Context): Intent =
            Intent(context, PinActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_STOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun verifyIntent(context: Context): Intent =
            Intent(context, PinActivity::class.java).putExtra(EXTRA_MODE, MODE_VERIFY)
    }
}
