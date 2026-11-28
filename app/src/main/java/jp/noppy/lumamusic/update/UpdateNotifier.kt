package jp.noppy.lumamusic.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import jp.noppy.lumamusic.AurioActivity
import jp.noppy.lumamusic.R

internal object UpdateNotifier {
    private const val Channel = "aurio_software_updates"
    private const val Notification = 1500

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(Channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission") // allowed() checks permission before posting.
    fun show(context: Context, version: String): Boolean {
        if (!allowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, context.getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, AurioActivity::class.java).setAction(OpenAurioUpdate)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val destination = PendingIntent.getActivity(context, Notification, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_aurio_notification)
            .setContentTitle(context.getString(R.string.update_notification_title, version))
            .setContentText(context.getString(R.string.update_notification_open))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(destination)
            .build()
        return try {
            manager.notify(Notification, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(Notification)
    }
}
