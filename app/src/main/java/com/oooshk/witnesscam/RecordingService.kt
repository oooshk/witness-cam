package com.oooshk.witnesscam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.concurrent.futures.await
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderStatus(
    val recording: Boolean = false,
    val dualCamera: Boolean = false,
    val startedAtMs: Long = 0,
    val segmentsRecorded: Int = 0,
    val pendingUploads: Int = 0,
    val error: String? = null,
)

/**
 * Foreground service that owns the camera. Recording continues with the screen off or the app in
 * the background. Video is cut into short chunks; each finished chunk is queued for upload.
 */
class RecordingService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var location: LocationTracker
    private val overlayThread = HandlerThread("overlay").apply { start() }
    private val overlayHandler by lazy { android.os.Handler(overlayThread.looper) }

    /** One camera being recorded to its own series of files. */
    private class CameraOutput(
        val tag: String,
        val capture: VideoCapture<Recorder>,
        val overlay: TimestampOverlay,
        val withAudio: Boolean,
    ) {
        var recording: Recording? = null
    }

    private var provider: ProcessCameraProvider? = null
    private val outputs = mutableListOf<CameraOutput>()
    private var segmentIndex = 0
    private var segmentJob: Job? = null
    private var alertsJob: Job? = null
    private var starting = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        location = LocationTracker(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            else -> startEverything()
        }
        // The camera can't be reopened from the background, so don't ask to be restarted.
        return START_NOT_STICKY
    }

    private fun startEverything() {
        if (_status.value.recording || starting) return
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            _status.update { it.copy(error = getString(R.string.error_permissions)) }
            stopSelf()
            return
        }

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (location.hasPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, Notifications.RECORDING_ID, Notifications.recording(this, 0), types)
        } catch (e: Exception) {
            // Android refuses camera/mic foreground services started from the background.
            Log.e(TAG, "startForeground failed", e)
            _status.update { it.copy(error = getString(R.string.error_start_background)) }
            stopSelf()
            return
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WitnessCam:recording")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        location.start()

        starting = true
        lifecycleScope.launch {
            try {
                val dual = bindCameras()
                val now = System.currentTimeMillis()
                val dir = ChunkStore.newSessionDir(this@RecordingService, SESSION_FORMAT.format(Date(now)))
                currentSessionDir = dir
                ChunkStore.writeSessionInfo(dir, now, dual)
                segmentIndex = 0
                _status.value = RecorderStatus(
                    recording = true, dualCamera = dual, startedAtMs = now,
                    pendingUploads = ChunkStore.pendingCount(this@RecordingService),
                )
                startSegment(dir)
                segmentJob = launch { rotateSegments(dir) }
                RecordWidgetProvider.updateAll(this@RecordingService)
                alertsJob = launch { sendAlerts(dir, now) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _status.value = RecorderStatus(error = e.message ?: e.toString())
                stopEverything()
            } finally {
                starting = false
            }
        }
    }

    /**
     * Binds the cameras. When the phone supports it and the user hasn't turned it off, the front
     * camera records to its own files alongside the rear camera; otherwise only the rear camera
     * records. Returns true in dual-camera mode.
     */
    private suspend fun bindCameras(): Boolean {
        val provider = ProcessCameraProvider.getInstance(this).await().also { provider = it }
        provider.unbindAll()
        outputs.clear()

        val pair = if (prefs.dualCamera) {
            provider.availableConcurrentCameraInfos.firstOrNull { infos ->
                infos.any { it.isBack() } && infos.any { it.isFront() }
            }
        } else {
            null
        }
        if (pair != null) {
            try {
                val rear = newOutput(TAG_REAR, VIDEO_BITRATE, withAudio = true)
                val front = newOutput(TAG_FRONT, FRONT_VIDEO_BITRATE, withAudio = false)
                val rearPreview = newPreview().also { rearPreviewUseCase = it; it.setSurfaceProvider(rearSurfaceProvider) }
                val frontPreview = newPreview().also { frontPreviewUseCase = it; it.setSurfaceProvider(frontSurfaceProvider) }
                // Separate use cases per camera ("non-composition" mode): each camera records its
                // own full-frame file with its own timestamp overlay.
                provider.bindToLifecycle(
                    listOf(
                        SingleCameraConfig(pair.first { it.isBack() }.cameraSelector, rear.group(rearPreview), this),
                        SingleCameraConfig(pair.first { it.isFront() }.cameraSelector, front.group(frontPreview), this),
                    )
                )
                outputs += rear
                outputs += front
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Concurrent cameras failed, falling back to one camera", e)
                provider.unbindAll()
                outputs.forEach { it.overlay.close() }
                outputs.clear()
                frontPreviewUseCase = null
            }
        }

        val hasBack = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val output = newOutput(if (hasBack) TAG_REAR else TAG_FRONT, VIDEO_BITRATE, withAudio = true)
        val preview = newPreview().also { rearPreviewUseCase = it; it.setSurfaceProvider(rearSurfaceProvider) }
        provider.bindToLifecycle(
            this,
            if (hasBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA,
            output.group(preview),
        )
        outputs += output
        return false
    }

    private fun newOutput(tag: String, bitrate: Int, withAudio: Boolean): CameraOutput {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        val label = if (tag == TAG_FRONT) "WitnessCam  FRONT" else "WitnessCam  REAR"
        val overlay = TimestampOverlay(overlayHandler, location) { label }
        return CameraOutput(tag, VideoCapture.withOutput(recorder), overlay, withAudio)
    }

    private fun newPreview(): Preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
        )
        .build()

    private fun CameraOutput.group(preview: Preview): UseCaseGroup = UseCaseGroup.Builder()
        .addUseCase(preview)
        .addUseCase(capture)
        .addEffect(overlay.effect)
        .build()

    private fun CameraInfo.isBack() = CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(this)).isNotEmpty()
    private fun CameraInfo.isFront() = CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(this)).isNotEmpty()

    /** Starts the next numbered chunk on every camera. */
    private fun startSegment(dir: File) {
        segmentIndex++
        val index = segmentIndex
        val startedAt = System.currentTimeMillis()
        for (output in outputs) {
            try {
                startOutput(dir, output, index, startedAt)
            } catch (e: IllegalStateException) {
                // The previous chunk is still finalizing; try this camera again shortly.
                Log.w(TAG, "${output.tag} chunk $index not ready, retrying", e)
                lifecycleScope.launch {
                    delay(200)
                    runCatching { startOutput(dir, output, index, startedAt) }
                        .onFailure { Log.e(TAG, "${output.tag} chunk $index failed to start", it) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOutput(dir: File, output: CameraOutput, index: Int, startedAt: Long) {
        val part = ChunkStore.partFile(dir, index, output.tag)
        ChunkStore.writeMeta(part, JSONObject().apply {
            put("startedAtMs", startedAt)
            put("startedAt", STAMP_FORMAT.format(Date(startedAt)))
            put("camera", output.tag)
            location.last?.let { put("location", "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude)) }
        })
        var pending = output.capture.output.prepareRecording(this, FileOutputOptions.Builder(part).build())
        if (output.withAudio) pending = pending.withAudioEnabled()
        output.recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) onSegmentFinalized(part, event, countIt = output.withAudio)
        }
    }

    private fun onSegmentFinalized(part: File, event: VideoRecordEvent.Finalize, countIt: Boolean) {
        val usable = !event.hasError() || (event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA &&
            event.error != VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS)
        if (event.hasError()) Log.w(TAG, "Segment ${part.name} finalized with error ${event.error}", event.cause)

        if (usable && part.exists() && part.length() > 0) {
            val done = File(part.parentFile, part.name.removeSuffix(".part"))
            part.renameTo(done)
            if (countIt) _status.update { it.copy(segmentsRecorded = it.segmentsRecorded + 1) }
            UploadWorker.enqueue(this)
        } else {
            part.delete()
            ChunkStore.metaFile(part).delete()
        }
        notifyUploadProgress(this)
    }

    private fun stopRecordings() {
        outputs.forEach { it.recording?.stop(); it.recording = null }
    }

    private suspend fun rotateSegments(dir: File) {
        while (lifecycleScope.isActive) {
            delay(prefs.segmentSeconds * 1000L)
            if (!_status.value.recording) break
            // Recorder queues the next recording until the previous one finishes writing, so the
            // gap between chunks is only the time it takes to finalize the file.
            stopRecordings()
            startSegment(dir)
        }
    }

    /** Texts emergency contacts: start alert, optional live footage link, periodic location. */
    private suspend fun sendAlerts(session: File, startedAtMs: Long) {
        if (prefs.contactList().isEmpty()) return
        // Give GPS a few seconds for a first fix so the start text includes a location.
        repeat(10) { if (location.last == null && location.hasPermission()) delay(500) }
        SmsAlerter.sendStartAlert(this, location.last)

        if (prefs.shareFootageLink && Cloud.isConnected(this)) {
            lifecycleScope.launch(Dispatchers.IO) { shareFootageLink(session) }
        }

        val every = prefs.locationUpdateMinutes
        if (every > 0) {
            while (true) {
                delay(every * 60_000L)
                if (!_status.value.recording) break
                SmsAlerter.sendLocationUpdate(this, location.last, startedAtMs)
            }
        }
    }

    private suspend fun shareFootageLink(session: File) {
        for (attempt in 0 until 10) {
            try {
                val link = Cloud.uploader(this).shareSessionLink(session)
                SmsAlerter.sendFootageLink(this, link)
                return
            } catch (e: CloudAuthRequiredException) {
                Log.w(TAG, "Can't share footage link: cloud not connected", e)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Sharing footage link failed (attempt ${attempt + 1})", e)
                delay(15_000)
            }
        }
    }

    private fun stopEverything() {
        val wasRecording = _status.value.recording
        val startedAt = _status.value.startedAtMs
        segmentJob?.cancel()
        segmentJob = null
        alertsJob?.cancel()
        alertsJob = null
        stopRecordings()
        _status.update { it.copy(recording = false) }
        RecordWidgetProvider.updateAll(this)
        if (wasRecording && prefs.smsOnStop) SmsAlerter.sendStopAlert(this, location.last, startedAt)
        currentSessionDir = null
        location.stop()
        // Give the last segment a moment to finalize before the camera is released.
        lifecycleScope.launch {
            delay(1500)
            provider?.unbindAll()
            outputs.forEach { it.overlay.close() }
            outputs.clear()
            rearPreviewUseCase = null
            frontPreviewUseCase = null
            UploadWorker.enqueue(this@RecordingService)
            wakeLock?.takeIf { it.isHeld }?.release()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopRecordings()
        overlayThread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.update { it.copy(recording = false) }
        RecordWidgetProvider.updateAll(this)
        super.onDestroy()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.oooshk.witnesscam.action.START"
        const val ACTION_STOP = "com.oooshk.witnesscam.action.STOP"
        private const val VIDEO_BITRATE = 3_000_000
        private const val FRONT_VIDEO_BITRATE = 1_500_000
        const val TAG_REAR = "rear"
        const val TAG_FRONT = "front"

        private val SESSION_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val STAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)

        private val _status = MutableStateFlow(RecorderStatus())
        val status: StateFlow<RecorderStatus> = _status.asStateFlow()

        @Volatile
        var currentSessionDir: File? = null
            private set

        private var rearPreviewUseCase: Preview? = null
        private var frontPreviewUseCase: Preview? = null
        private var rearSurfaceProvider: Preview.SurfaceProvider? = null
        private var frontSurfaceProvider: Preview.SurfaceProvider? = null

        /** Called by the activity to show (or hide, with nulls) the live previews. Main thread only. */
        fun setPreviewSurfaces(rear: Preview.SurfaceProvider?, front: Preview.SurfaceProvider?) {
            rearSurfaceProvider = rear
            frontSurfaceProvider = front
            rearPreviewUseCase?.setSurfaceProvider(rear)
            frontPreviewUseCase?.setSurfaceProvider(front)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }

        fun notifyUploadProgress(context: Context) {
            val pending = ChunkStore.pendingCount(context)
            _status.update { it.copy(pendingUploads = pending) }
            if (_status.value.recording) Notifications.updateRecording(context, pending)
        }
    }
}
