package com.oooshk.witnesscam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews

/**
 * Home-screen start/stop button. Starting opens the app (Android only lets a visible app start
 * the camera); stopping opens the PIN screen so nobody else can switch the recording off.
 *
 * With "double-tap" on (the default), the first tap only arms the widget ("TAP AGAIN") for a few
 * seconds and the second tap acts, so a pocket or stray press can't start or stop a recording.
 */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_ARM) return
        Prefs(context).widgetArmedUntilMs = System.currentTimeMillis() + ARM_WINDOW_MS
        updateAll(context)
        // Keep the receiver alive until the window closes, then put the widget back to normal.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            updateAll(context)
            pending.finish()
        }, ARM_WINDOW_MS + 100)
    }

    companion object {
        private const val ACTION_ARM = "com.oooshk.witnesscam.action.WIDGET_ARM"
        private const val ARM_WINDOW_MS = 3_000L

        /** Extra on intents fired by the armed widget, so a stale tap can be recognized. */
        const val EXTRA_ARMED = "widget_armed"

        /**
         * True if an intent from the armed widget arrived in time. Consumes the armed state so a
         * second, late intent can't act. Intents not from the armed widget always pass.
         */
        fun consumeArmedTap(context: Context, intent: Intent?): Boolean {
            if (intent?.getBooleanExtra(EXTRA_ARMED, false) != true) return true
            val prefs = Prefs(context)
            // Small grace period for the time it takes the activity to open.
            val ok = System.currentTimeMillis() <= prefs.widgetArmedUntilMs + 2_000
            prefs.widgetArmedUntilMs = 0
            updateAll(context)
            return ok
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = Prefs(context)
            val recording = RecordingService.status.value.recording
            val doubleTap = prefs.widgetDoubleTap
            val armed = doubleTap && System.currentTimeMillis() < prefs.widgetArmedUntilMs

            val action = if (recording) {
                PinActivity.stopIntent(context)
            } else {
                Intent(context, MainActivity::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = if (doubleTap && !armed) {
                PendingIntent.getBroadcast(
                    context, 2,
                    Intent(context, RecordWidgetProvider::class.java).setAction(ACTION_ARM),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getActivity(
                    context, if (recording) 1 else 0, action.putExtra(EXTRA_ARMED, doubleTap),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

            val text = when {
                armed && recording -> context.getString(R.string.widget_armed_stop)
                armed -> context.getString(R.string.widget_armed_record)
                recording -> context.getString(R.string.widget_stop)
                else -> context.getString(R.string.widget_text)
            }
            val background = when {
                armed -> R.drawable.widget_bg_armed
                recording -> R.drawable.widget_bg_recording
                else -> R.drawable.widget_bg
            }
            val views = RemoteViews(context.packageName, R.layout.widget_record).apply {
                setTextViewText(R.id.widget_button, text)
                setInt(R.id.widget_button, "setBackgroundResource", background)
                setOnClickPendingIntent(R.id.widget_button, pending)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
