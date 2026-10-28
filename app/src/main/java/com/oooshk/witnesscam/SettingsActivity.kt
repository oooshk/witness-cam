package com.oooshk.witnesscam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.oooshk.witnesscam.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var finishAfterSms = false

    // Settings can remove the PIN, so they're behind the PIN too.
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) binding.root.isVisible = true else finish()
    }

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.sms_denied, Toast.LENGTH_LONG).show()
        if (finishAfterSms) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (prefs.hasPin && savedInstanceState == null) {
            binding.root.isVisible = false
            unlock.launch(PinActivity.verifyIntent(this))
        }

        binding.segmentSeconds.setText(prefs.segmentSeconds.toString())
        binding.wifiOnly.isChecked = prefs.wifiOnly
        binding.deleteAfterUpload.isChecked = prefs.deleteAfterUpload
        binding.contacts.setText(prefs.emergencyContacts)
        binding.alertMessage.setText(prefs.alertMessage)
        binding.smsOnStop.isChecked = prefs.smsOnStop
        binding.locationMinutes.setText(prefs.locationUpdateMinutes.toString())
        binding.shareLink.isChecked = prefs.shareFootageLink
        binding.providerGroup.check(if (prefs.cloudProvider == Cloud.DROPBOX) R.id.provider_dropbox else R.id.provider_google)
        binding.providerGroup.setOnCheckedChangeListener { _, id ->
            prefs.cloudProvider = if (id == R.id.provider_dropbox) Cloud.DROPBOX else Cloud.GOOGLE_DRIVE
            renderCloudStatus()
        }
        binding.dropboxSetup.setOnClickListener {
            startActivity(android.content.Intent(this, DropboxConnectActivity::class.java))
        }
        binding.storagePath.text = getString(R.string.storage_path, ChunkStore.root(this).absolutePath)
        renderPinStatus()

        binding.removePin.setOnClickListener {
            prefs.setPin("")
            renderPinStatus()
        }
        binding.sendTest.setOnClickListener {
            prefs.emergencyContacts = binding.contacts.text.toString()
            if (!hasSmsPermission()) {
                finishAfterSms = false
                smsPermission.launch(Manifest.permission.SEND_SMS)
            } else {
                val ok = SmsAlerter.sendTest(this)
                Toast.makeText(this, if (ok) R.string.test_sent else R.string.test_failed, Toast.LENGTH_SHORT).show()
            }
        }
        binding.save.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        renderCloudStatus()
    }

    private fun renderCloudStatus() {
        val dropbox = prefs.cloudProvider == Cloud.DROPBOX
        binding.driveStatus.setText(
            when {
                dropbox && Cloud.isConnected(this) -> R.string.dropbox_status_connected
                dropbox -> R.string.dropbox_status_not_connected
                Cloud.isConnected(this) -> R.string.drive_connected
                else -> R.string.drive_not_connected
            }
        )
        binding.dropboxSetup.isVisible = dropbox
    }

    private fun renderPinStatus() {
        binding.pinStatus.setText(if (prefs.hasPin) R.string.pin_status_set else R.string.pin_status_none)
        binding.removePin.isVisible = prefs.hasPin
    }

    private fun save() {
        val newPin = binding.newPin.text.toString()
        if (newPin.isNotEmpty() && newPin.length < 4) {
            binding.newPin.error = getString(R.string.pin_too_short)
            return
        }
        if (newPin.isNotEmpty()) prefs.setPin(newPin)

        prefs.segmentSeconds = binding.segmentSeconds.text.toString().toIntOrNull() ?: 10
        prefs.wifiOnly = binding.wifiOnly.isChecked
        prefs.deleteAfterUpload = binding.deleteAfterUpload.isChecked
        prefs.emergencyContacts = binding.contacts.text.toString()
        prefs.alertMessage = binding.alertMessage.text.toString()
        prefs.smsOnStop = binding.smsOnStop.isChecked
        prefs.locationUpdateMinutes = binding.locationMinutes.text.toString().toIntOrNull() ?: 0
        prefs.shareFootageLink = binding.shareLink.isChecked

        if (prefs.contactList().isNotEmpty() && !hasSmsPermission()) {
            finishAfterSms = true
            smsPermission.launch(Manifest.permission.SEND_SMS)
        } else {
            finish()
        }
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}
