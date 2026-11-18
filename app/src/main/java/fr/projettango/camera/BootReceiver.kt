package fr.projettango.camera

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Restarts streaming after a reboot or an app update.
 *
 * Android does not let a background app open the camera, so the receiver opens the activity
 * (allowed when "display over other apps" is granted), and the activity starts the service.
 * Without that permission it posts a notification: one tap restarts everything.
 * Running the app as the home screen (launcher mode) is the most robust option for a phone
 * dedicated to this job: Android then opens it by itself at every boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.startOnBoot || !prefs.streaming) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                if (Build.VERSION.SDK_INT < 29) {
                    // no background restrictions on camera services before Android 10
                    StreamService.start(context)
                } else if (Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_FROM_BOOT, true))
                } else {
                    notifyTapToStart(context)
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "restart"

        fun notifyTapToStart(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_restart), NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_camera)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.tap_to_restart))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(2, n) }
        }
    }
}
