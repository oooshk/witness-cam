package com.oooshk.witnesscam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen button that opens the app and immediately starts recording. */
class RecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(RecordingService.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setOnClickPendingIntent(R.id.widget_button, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
