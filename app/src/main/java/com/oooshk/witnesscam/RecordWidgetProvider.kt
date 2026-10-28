package com.oooshk.witnesscam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen start/stop button. Starting opens the app (Android only lets a visible app start
 * the camera); stopping opens the PIN screen so nobody else can switch the recording off.
 */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val recording = RecordingService.status.value.recording
            val intent = if (recording) {
                PinActivity.stopIntent(context)
            } else {
                Intent(context, MainActivity::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(
                context, if (recording) 1 else 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_record).apply {
                setTextViewText(R.id.widget_button, context.getString(if (recording) R.string.widget_stop else R.string.widget_text))
                setInt(R.id.widget_button, "setBackgroundResource", if (recording) R.drawable.widget_bg_recording else R.drawable.widget_bg)
                setOnClickPendingIntent(R.id.widget_button, pending)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
