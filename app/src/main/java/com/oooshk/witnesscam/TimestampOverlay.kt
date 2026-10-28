package com.oooshk.witnesscam

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Burns the date, time, GPS position and session id into every recorded frame (and the preview),
 * so the stamp can't be separated from the footage.
 */
class TimestampOverlay(
    handler: Handler,
    private val location: LocationTracker,
    private val label: () -> String,
) {
    val effect = OverlayEffect(
        CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
        0,
        handler,
    ) { t -> Log.e(TAG, "Overlay effect failed", t) }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val bgPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val box = RectF()

    init {
        effect.setOnDrawListener { frame -> draw(frame); true }
    }

    private fun draw(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // The buffer is in sensor orientation; rotate the canvas so the text is upright in the
        // final (rotated) video.
        val crop = frame.cropRect
        val rotation = frame.rotationDegrees
        val bw = crop.width().toFloat()
        val bh = crop.height().toFloat()
        val (w, h) = if (rotation % 180 == 0) bw to bh else bh to bw

        canvas.save()
        when (rotation) {
            90 -> canvas.translate(crop.left.toFloat(), crop.top + bh)
            180 -> canvas.translate(crop.left + bw, crop.top + bh)
            270 -> canvas.translate(crop.left + bw, crop.top.toFloat())
            else -> canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        }
        canvas.rotate(-rotation.toFloat())
        if (frame.isMirroring) {
            canvas.translate(w, 0f)
            canvas.scale(-1f, 1f)
        }

        val lines = buildList {
            add(dateFormat.format(Date()))
            location.last?.let {
                add(String.format(Locale.US, "%.6f, %.6f  ±%.0fm", it.latitude, it.longitude, it.accuracy))
            }
            add(label())
        }

        textPaint.textSize = minOf(w, h) / 22f
        val pad = textPaint.textSize * 0.4f
        val lineHeight = textPaint.fontSpacing
        val textWidth = lines.maxOf { textPaint.measureText(it) }
        val top = h - pad * 3 - lineHeight * lines.size
        box.set(pad, top, pad * 3 + textWidth, h - pad)
        canvas.drawRect(box, bgPaint)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, pad * 2, top + pad + lineHeight * (i + 1) - textPaint.descent(), textPaint)
        }
        canvas.restore()
    }

    fun close() = effect.close()

    companion object {
        private const val TAG = "TimestampOverlay"
    }
}
