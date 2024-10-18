package dev.farukh.discord_demo

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class StreamService : Service(), IBinder by Binder() {

    override fun onCreate() {
        super.onCreate()
        createNotification()
    }

    private fun createNotification() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder("asd", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("asd")
                .build()
        )
        val notification = NotificationCompat.Builder(this, "asd").build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent): IBinder {
        return this
    }
}