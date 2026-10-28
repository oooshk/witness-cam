package com.oooshk.witnesscam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oooshk.witnesscam.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // Settings can remove the PIN, so they're behind the PIN too.
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) binding.root.isVisible = true else finish()
    }

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        renderSmsStatus()
        // Android 13+ silently blocks SMS for apps installed from a file ("restricted settings"),
        // so a denial usually needs the user to unblock it in system settings.
        if (!granted) showSmsHelp()
    }

    // Picking a phone number grants access to just that contact, so no contacts permission is needed.
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) addContact(c.getString(0).orEmpty(), c.getString(1).orEmpty())
        }
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
        binding.dualCamera.isChecked = prefs.dualCamera
        if (prefs.dualCamera) prefs.dualDisabledAfterCrash = false
        binding.widgetDoubleTap.isChecked = prefs.widgetDoubleTap
        binding.wifiOnly.isChecked = prefs.wifiOnly
        binding.deleteAfterUpload.isChecked = prefs.deleteAfterUpload
        renderContacts()
        binding.addFromContacts.setOnClickListener {
            try {
                pickContact.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_contacts_app, Toast.LENGTH_LONG).show()
            }
        }
        binding.addNumber.setOnClickListener { promptForNumber() }
        binding.smsAllow.setOnClickListener { smsPermission.launch(Manifest.permission.SEND_SMS) }
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
            startActivity(Intent(this, DropboxConnectActivity::class.java))
        }
        binding.storagePath.text = getString(R.string.storage_path, ChunkStore.root(this).absolutePath)
        renderPinStatus()

        binding.removePin.setOnClickListener {
            prefs.setPin("")
            renderPinStatus()
        }
        binding.sendTest.setOnClickListener {
            if (!hasSmsPermission()) {
                smsPermission.launch(Manifest.permission.SEND_SMS)
            } else {
                val ok = SmsAlerter.sendTest(this)
                Toast.makeText(this, if (ok) R.string.test_sent else R.string.test_failed, Toast.LENGTH_SHORT).show()
            }
        }
        binding.save.setOnClickListener { save() }
        binding.shareDiagnostics.setOnClickListener { shareDiagnostics(this) }
    }

    override fun onResume() {
        super.onResume()
        renderCloudStatus()
        renderSmsStatus()
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
        prefs.dualCamera = binding.dualCamera.isChecked
        prefs.widgetDoubleTap = binding.widgetDoubleTap.isChecked
        RecordWidgetProvider.updateAll(this)
        prefs.wifiOnly = binding.wifiOnly.isChecked
        prefs.deleteAfterUpload = binding.deleteAfterUpload.isChecked
        prefs.alertMessage = binding.alertMessage.text.toString()
        prefs.smsOnStop = binding.smsOnStop.isChecked
        prefs.locationUpdateMinutes = binding.locationMinutes.text.toString().toIntOrNull() ?: 0
        prefs.shareFootageLink = binding.shareLink.isChecked

        finish()
    }

    private fun addContact(name: String, number: String) {
        val clean = number.trim()
        if (clean.isEmpty()) return
        val digits = clean.filter { it.isDigit() }
        val current = prefs.contacts
        if (current.none { it.number.filter(Char::isDigit) == digits }) {
            prefs.contacts = current + Prefs.Contact(name.trim(), clean)
        }
        renderContacts()
        if (!hasSmsPermission()) smsPermission.launch(Manifest.permission.SEND_SMS)
    }

    private fun promptForNumber() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.phone_number)
        }
        val frame = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_number)
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ -> addContact("", input.text.toString()) }
            .show()
    }

    private fun renderContacts() {
        val group = binding.contactsGroup
        group.removeAllViews()
        val contacts = prefs.contacts
        binding.noContacts.isVisible = contacts.isEmpty()
        for (contact in contacts) {
            group.addView(Chip(this).apply {
                text = if (contact.name.isBlank()) contact.number else "${contact.name} · ${contact.number}"
                isCloseIconVisible = true
                closeIconContentDescription = getString(R.string.remove_contact)
                setOnCloseIconClickListener {
                    prefs.contacts = prefs.contacts.filterNot { it == contact }
                    renderContacts()
                }
            })
        }
    }

    private fun renderSmsStatus() {
        val granted = hasSmsPermission()
        binding.smsStatus.setText(if (granted) R.string.sms_status_granted else R.string.sms_status_missing)
        binding.smsAllow.isVisible = !granted
    }

    private fun showSmsHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sms_help_title)
            .setMessage(R.string.sms_help_steps)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.open_app_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .show()
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}
