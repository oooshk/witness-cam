package com.oooshk.witnesscam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.oooshk.witnesscam.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.sms_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.segmentSeconds.setText(prefs.segmentSeconds.toString())
        binding.wifiOnly.isChecked = prefs.wifiOnly
        binding.deleteAfterUpload.isChecked = prefs.deleteAfterUpload
        binding.contacts.setText(prefs.emergencyContacts)
        binding.alertMessage.setText(prefs.alertMessage)
        binding.driveStatus.setText(if (prefs.driveConnected) R.string.drive_connected else R.string.drive_not_connected)
        binding.storagePath.text = getString(R.string.storage_path, ChunkStore.root(this).absolutePath)

        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        prefs.segmentSeconds = binding.segmentSeconds.text.toString().toIntOrNull() ?: 10
        prefs.wifiOnly = binding.wifiOnly.isChecked
        prefs.deleteAfterUpload = binding.deleteAfterUpload.isChecked
        prefs.emergencyContacts = binding.contacts.text.toString()
        prefs.alertMessage = binding.alertMessage.text.toString()

        val needsSms = prefs.contactList().isNotEmpty() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
        if (needsSms) smsPermission.launch(Manifest.permission.SEND_SMS) else finish()
    }
}
