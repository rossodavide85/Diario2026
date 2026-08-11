package it.davide.diario

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/** Home-screen widget showing the current alcohol-free streak. */
class DiaryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val streaks = alcoholFreeStreaks(DiaryStore(context).load())
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_diary)
            views.setTextViewText(R.id.widget_streak, streaks.current.toString())
            views.setTextViewText(R.id.widget_record, "record ${streaks.longest}")

            val open = Intent(context, MainActivity::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getActivity(context, 0, open, flags)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }
    }

    companion object {
        /** Refresh all placed widgets (call after data changes). */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DiaryWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, DiaryWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
