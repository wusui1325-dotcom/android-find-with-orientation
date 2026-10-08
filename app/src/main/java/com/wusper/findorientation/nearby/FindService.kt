package com.wusper.findorientation.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wusper.findorientation.FindApp
import com.wusper.findorientation.R

class FindService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            41,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        (application as FindApp).repository.start()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as FindApp).repository.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.channel_desc)
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在 10 米範圍內廣播並尋找其他客戶端")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "find_orientation"
    }
}
