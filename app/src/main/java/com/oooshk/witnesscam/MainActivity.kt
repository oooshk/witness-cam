package com.oooshk.witnesscam

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.identity.Identity
import com.oooshk.witnesscam.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opens the share sheet with the diagnostics report (for emailing/texting it to whoever helps). */
fun shareDiagnostics(activity: android.app.Activity) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "WitnessCam diagnostics")
        .putExtra(Intent.EXTRA_TEXT, Diagnostics.report(activity))
    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_details)))
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var startWhenPermitted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (startWhenPermitted && hasCorePermissions()) {
                startWhenPermitted = false
                RecordingService.start(this)
            } else if (!hasCorePermissions()) {
                Toast.makeText(this, R.string.error_permissions, Toast.LENGTH_LONG).show()
            }
        }

    private val driveConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            try {
                val data = result.data ?: throw IllegalStateException("cancelled")
                val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
                onDriveAuthorized(auth.accessToken != null)
            } catch (e: Exception) {
                onDriveAuthorized(false, e.message)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Allow starting a recording from the lock screen (e.g. via the Quick Settings tile).
        setShowWhenLocked(true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.recordButton.setOnClickListener {
            if (RecordingService.status.value.recording) {
                startActivity(PinActivity.stopIntent(this))
            } else {
                startRecording()
            }
        }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.reviewButton.setOnClickListener { startActivity(Intent(this, ReviewActivity::class.java)) }
        binding.driveButton.setOnClickListener {
            if (Cloud.provider(this) == Cloud.DROPBOX) {
                startActivity(Intent(this, DropboxConnectActivity::class.java))
            } else {
                connectDrive()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecordingService.status.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderElapsed()
                    delay(1000)
                }
            }
        }

        requestCorePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == RecordingService.ACTION_START) {
            intent.action = null
            // A tap on a widget that was armed too long ago just opens the app.
            if (RecordWidgetProvider.consumeArmedTap(this, intent)) startRecording()
        }
    }

    override fun onStart() {
        super.onStart()
        RecordingService.setPreviewSurfaces(binding.preview.surfaceProvider, binding.frontPreview.surfaceProvider)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        binding.settingsButton.isVisible = !locked
        binding.reviewButton.isVisible = !locked
        binding.driveButton.isVisible = !locked && !Cloud.isConnected(this)
        binding.driveButton.setText(
            if (Cloud.provider(this) == Cloud.DROPBOX) R.string.connect_dropbox else R.string.connect_drive
        )
        RecordingService.notifyUploadProgress(this)
        showCrashNoticeIfAny()
    }

    /** After a crash (or an automatic switch to one camera), explain and offer to share details. */
    private fun showCrashNoticeIfAny() {
        val crashed = Diagnostics.crashFile(this).exists()
        val dualOff = prefs.dualDisabledAfterCrash
        if (!crashed && !dualOff) return
        val message = buildString {
            append(getString(R.string.crash_message))
            if (dualOff) append("\n\n").append(getString(R.string.crash_dual_off))
        }
        fun dismiss() {
            Diagnostics.crashFile(this).delete()
            prefs.dualDisabledAfterCrash = false
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.share_details) { _, _ ->
                shareDiagnostics(this)
                dismiss()
            }
            .setNegativeButton(R.string.close) { _, _ -> dismiss() }
            .show()
    }

    override fun onStop() {
        RecordingService.setPreviewSurfaces(null, null)
        super.onStop()
    }

    private fun startRecording() {
        if (!hasCorePermissions()) {
            startWhenPermitted = true
            requestCorePermissions()
            return
        }
        if (!Cloud.isConnected(this)) {
            Toast.makeText(this, R.string.warn_cloud_not_connected, Toast.LENGTH_LONG).show()
        }
        RecordingService.start(this)
    }

    private fun render(status: RecorderStatus) {
        binding.recordButton.setImageResource(if (status.recording) R.drawable.ic_stop else R.drawable.ic_record)
        binding.recordButton.contentDescription = getString(if (status.recording) R.string.stop else R.string.record)
        binding.recordingDot.isVisible = status.recording
        binding.frontPreviewFrame.isVisible = status.recording && status.dualCamera
        binding.idleHint.isVisible = !status.recording
        if (status.recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val cams = getString(if (status.dualCamera) R.string.cams_dual else R.string.cams_single)
        val uploads = if (status.pendingUploads == 0) {
            getString(R.string.status_uploaded)
        } else {
            resources.getQuantityString(R.plurals.notif_pending, status.pendingUploads, status.pendingUploads)
        }
        binding.statusText.text = if (status.recording) "$cams · $uploads" else uploads
        status.error?.let { binding.statusText.text = getString(R.string.error_prefix, it) }
    }

    private fun renderElapsed() {
        val status = RecordingService.status.value
        binding.elapsed.visibility = if (status.recording) View.VISIBLE else View.INVISIBLE
        if (status.recording) {
            val secs = (System.currentTimeMillis() - status.startedAtMs) / 1000
            binding.elapsed.text = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
        }
    }

    private fun connectDrive() {
        Identity.getAuthorizationClient(this).authorize(DriveAuth.request())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    driveConsentLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else {
                    onDriveAuthorized(true)
                }
            }
            .addOnFailureListener { e -> onDriveAuthorized(false, e.message) }
    }

    private fun onDriveAuthorized(ok: Boolean, error: String? = null) {
        prefs.driveConnected = ok
        binding.driveButton.isVisible = !ok
        if (ok) {
            Toast.makeText(this, R.string.drive_connected, Toast.LENGTH_SHORT).show()
            UploadWorker.enqueue(this)
        } else {
            Toast.makeText(this, getString(R.string.drive_failed, error ?: "cancelled"), Toast.LENGTH_LONG).show()
        }
    }

    private fun corePermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (prefs.contactList().isNotEmpty()) add(Manifest.permission.SEND_SMS)
    }

    private fun hasCorePermissions() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCorePermissions() {
        val missing = corePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
