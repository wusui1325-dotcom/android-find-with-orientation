package com.wusper.findorientation.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wusper.findorientation.FindApp
import com.wusper.findorientation.MainActivity
import com.wusper.findorientation.R
import com.wusper.findorientation.model.FindState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FindService : LifecycleService() {
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val repo = (application as FindApp).repository
        startInForeground(repo.state.value)
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                repo.state
                    .map { Triple(it.seeking, it.resident, it.incomingPing != null) }
                    .distinctUntilChanged()
                    .collect { updateForeground(repo.state.value) }
            }
        }
        when (intent?.action) {
            ACTION_STOP_PING -> repo.stopIncomingPing()
            else -> repo.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(PING_NOTICE)
        (application as FindApp).repository.stop()
        super.onDestroy()
    }

    private fun startInForeground(state: FindState) {
        ServiceCompat.startForeground(
            this,
            NOTICE,
            buildForeground(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun updateForeground(state: FindState) {
        startInForeground(state)
        val ping = state.incomingPing
        val manager = NotificationManagerCompat.from(this)
        if (ping == null) {
            manager.cancel(PING_NOTICE)
        } else if (canNotify()) {
            manager.notify(PING_NOTICE, buildPing(ping.fromName))
        }
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val idle = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        idle.description = getString(R.string.channel_desc)
        manager.createNotificationChannel(idle)
        val alert = NotificationChannel(PING_CHANNEL, getString(R.string.ping_channel_name), NotificationManager.IMPORTANCE_HIGH)
        alert.description = getString(R.string.ping_channel_desc)
        alert.setSound(null, null)
        manager.createNotificationChannel(alert)
    }

    private fun launchIntent(): PendingIntent {
        val launch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPingIntent(): PendingIntent {
        val stop = Intent(this, FindService::class.java).setAction(ACTION_STOP_PING)
        return PendingIntent.getService(
            this,
            1,
            stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildForeground(state: FindState): Notification {
        val text = when {
            state.seeking && state.visible -> "正在尋找附近的裝置，同時可被發現"
            state.seeking -> "正在尋找附近的裝置"
            state.resident || state.visible -> "常駐廣播中。10 米內的客戶端可以尋找這台裝置"
            else -> "服務運行中"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildPing(fromName: String): Notification {
        return NotificationCompat.Builder(this, PING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("有人正在呼叫這部手機")
            .setContentText(if (fromName.isBlank()) "點一下可停止鈴聲" else "$fromName 正在呼叫。點一下可停止鈴聲")
            .setContentIntent(launchIntent())
            .addAction(0, "停止鈴聲", stopPingIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun canNotify(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_STOP_PING = "com.wusper.findorientation.STOP_PING"
        private const val CHANNEL = "find_orientation"
        private const val PING_CHANNEL = "find_orientation_ping"
        private const val NOTICE = 41
        private const val PING_NOTICE = 42
    }
}
