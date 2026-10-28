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
import androidx.camera.core.CompositionSettings
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

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlay: TimestampOverlay? = null
    private var recording: Recording? = null
    private var segmentIndex = 0
    private var segmentJob: Job? = null
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
        if (_status.value.recording) return
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
                SmsAlerter.sendStartAlert(this@RecordingService, location.last)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _status.value = RecorderStatus(error = e.message ?: e.toString())
                stopEverything()
            }
        }
    }

    /** Binds front+back together when the device supports it, otherwise just the back camera. */
    private suspend fun bindCameras(): Boolean {
        val provider = ProcessCameraProvider.getInstance(this).await().also { provider = it }
        provider.unbindAll()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            )
            .setTargetVideoEncodingBitRate(VIDEO_BITRATE)
            .build()
        val capture = VideoCapture.withOutput(recorder).also { videoCapture = it }
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        preview.setSurfaceProvider(previewSurfaceProvider)
        previewUseCase = preview

        val overlay = TimestampOverlay(overlayHandler, location) {
            if (_status.value.dualCamera) "WitnessCam  front+rear" else "WitnessCam"
        }.also { this.overlay = it }
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(overlay.effect)
            .build()

        val pair = provider.availableConcurrentCameraInfos.firstOrNull { infos ->
            infos.any { it.isBack() } && infos.any { it.isFront() }
        }
        if (pair != null) {
            try {
                val back = pair.first { it.isBack() }
                val front = pair.first { it.isFront() }
                // Rear camera fills the frame; front camera is a picture-in-picture in the corner.
                val primary = SingleCameraConfig(
                    back.selector(), group,
                    CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build(),
                    this,
                )
                val secondary = SingleCameraConfig(
                    front.selector(), group,
                    CompositionSettings.Builder().setAlpha(1f).setOffset(0.62f, 0.55f).setScale(0.33f, 0.33f).build(),
                    this,
                )
                provider.bindToLifecycle(listOf(primary, secondary))
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Concurrent cameras failed, falling back to rear camera only", e)
                provider.unbindAll()
            }
        }

        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        provider.bindToLifecycle(this, selector, group)
        return false
    }

    private fun CameraInfo.selector(): CameraSelector = cameraSelector
    private fun CameraInfo.isBack() = CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(this)).isNotEmpty()
    private fun CameraInfo.isFront() = CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(this)).isNotEmpty()

    @SuppressLint("MissingPermission")
    private fun startSegment(dir: File) {
        val capture = videoCapture ?: return
        segmentIndex++
        val part = ChunkStore.partFile(dir, segmentIndex)
        val startedAt = System.currentTimeMillis()
        ChunkStore.writeMeta(part, JSONObject().apply {
            put("startedAtMs", startedAt)
            put("startedAt", STAMP_FORMAT.format(Date(startedAt)))
            location.last?.let { put("location", "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude)) }
        })

        recording = capture.output
            .prepareRecording(this, FileOutputOptions.Builder(part).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) onSegmentFinalized(part, event)
            }
    }

    private fun onSegmentFinalized(part: File, event: VideoRecordEvent.Finalize) {
        val usable = !event.hasError() || (event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA &&
            event.error != VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS)
        if (event.hasError()) Log.w(TAG, "Segment ${part.name} finalized with error ${event.error}", event.cause)

        if (usable && part.exists() && part.length() > 0) {
            val done = File(part.parentFile, part.name.removeSuffix(".part"))
            part.renameTo(done)
            _status.update { it.copy(segmentsRecorded = it.segmentsRecorded + 1) }
            UploadWorker.enqueue(this)
        } else {
            part.delete()
            ChunkStore.metaFile(part).delete()
        }
        notifyUploadProgress(this)
    }

    private suspend fun rotateSegments(dir: File) {
        while (lifecycleScope.isActive) {
            delay(prefs.segmentSeconds * 1000L)
            if (!_status.value.recording) break
            // Recorder queues the next recording until the previous one finishes writing, so the
            // gap between chunks is only the time it takes to finalize the file.
            recording?.stop()
            try {
                startSegment(dir)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Next segment not ready, retrying", e)
                delay(200)
                runCatching { startSegment(dir) }
            }
        }
    }

    private fun stopEverything() {
        segmentJob?.cancel()
        segmentJob = null
        recording?.stop()
        recording = null
        _status.update { it.copy(recording = false) }
        currentSessionDir = null
        location.stop()
        // Give the last segment a moment to finalize before the camera is released.
        lifecycleScope.launch {
            delay(1500)
            provider?.unbindAll()
            overlay?.close()
            overlay = null
            previewUseCase = null
            UploadWorker.enqueue(this@RecordingService)
            wakeLock?.takeIf { it.isHeld }?.release()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        recording?.stop()
        overlayThread.quitSafely()
        wakeLock?.takeIf { it.isHeld }?.release()
        _status.update { it.copy(recording = false) }
        super.onDestroy()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.oooshk.witnesscam.action.START"
        const val ACTION_STOP = "com.oooshk.witnesscam.action.STOP"
        private const val VIDEO_BITRATE = 3_000_000

        private val SESSION_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        private val STAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)

        private val _status = MutableStateFlow(RecorderStatus())
        val status: StateFlow<RecorderStatus> = _status.asStateFlow()

        @Volatile
        var currentSessionDir: File? = null
            private set

        private var previewUseCase: Preview? = null
        private var previewSurfaceProvider: Preview.SurfaceProvider? = null

        /** Called by the activity to show (or hide, with null) the live preview. Main thread only. */
        fun setPreviewSurface(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            previewUseCase?.setSurfaceProvider(provider)
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
