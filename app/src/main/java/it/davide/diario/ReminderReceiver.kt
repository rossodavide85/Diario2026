package it.davide.diario

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** Evening reminder to log the day. Fires (approximately) every day at 23:30. */
object Reminder {
    const val CHANNEL_ID = "diario_reminder"
    const val ACTION_FIRE = "it.davide.diario.REMINDER_FIRE"
    private const val REQUEST_CODE = 4231
    private const val HOUR = 23
    private const val MINUTE = 30
    private const val NOTIF_ID = 1001

    private fun piFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Promemoria diario",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                ch.description = "Promemoria serale per registrare la giornata"
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, piFlags())

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        // Inexact daily repeat: no exact-alarm permission needed; fine for a reminder.
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi
        )
    }

    fun notifyNow(context: Context) {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, 0, open, piFlags())
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Diario 2026")
            .setContentText("Hai segnato la giornata di oggi? 🍺 🏃")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): silently skip.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminder.ensureChannel(context)
            Reminder.schedule(context)
            return
        }
        Reminder.notifyNow(context)
    }
}
