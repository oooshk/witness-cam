package com.oooshk.witnesscam

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.oooshk.witnesscam.databinding.ActivityDropboxConnectBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Walks the user through connecting Dropbox: app key → approve in browser → paste code. */
class DropboxConnectActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDropboxConnectBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDropboxConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // With a key built into the app, users skip creating their own Dropbox app.
        val builtIn = BuildConfig.DROPBOX_APP_KEY
        binding.appKey.setText(prefs.dropboxAppKey ?: builtIn)
        if (builtIn.isNotEmpty() && (prefs.dropboxAppKey == null || prefs.dropboxAppKey == builtIn)) {
            binding.step1.isVisible = false
            binding.appKeyLayout.isVisible = false
        }
        render()

        binding.openDropbox.setOnClickListener { openDropbox() }
        binding.pasteCode.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.let { binding.code.setText(it.trim()) }
        }
        binding.connect.setOnClickListener { connect() }
        binding.disconnect.setOnClickListener {
            DropboxAuth.signOut(this)
            render()
        }
    }

    private fun appKey(): String? = binding.appKey.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun openDropbox() {
        val key = appKey() ?: run {
            binding.appKey.error = getString(R.string.dropbox_key_required)
            return
        }
        prefs.dropboxAppKey = key
        try {
            startActivity(Intent(Intent.ACTION_VIEW, DropboxAuth.authorizeUrl(this, key)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun connect() {
        val key = appKey() ?: run {
            binding.appKey.error = getString(R.string.dropbox_key_required)
            return
        }
        val code = binding.code.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            binding.code.error = getString(R.string.dropbox_code_required)
            return
        }
        binding.connect.isEnabled = false
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { DropboxAuth.finishSignIn(this@DropboxConnectActivity, key, code) }.exceptionOrNull()
            }
            binding.connect.isEnabled = true
            if (error == null) {
                prefs.cloudProvider = Cloud.DROPBOX
                Toast.makeText(this@DropboxConnectActivity, R.string.dropbox_connected, Toast.LENGTH_SHORT).show()
                UploadWorker.enqueue(this@DropboxConnectActivity)
                finish()
            } else {
                binding.code.error = getString(R.string.dropbox_failed, error.message ?: error.toString())
            }
        }
    }

    private fun render() {
        val connected = prefs.dropboxRefreshToken != null
        binding.status.setText(if (connected) R.string.dropbox_status_connected else R.string.dropbox_status_not_connected)
        binding.disconnect.isVisible = connected
    }
}
