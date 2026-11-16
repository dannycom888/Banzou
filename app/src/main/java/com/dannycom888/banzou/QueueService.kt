package com.dannycom888.banzou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the separation queue alive with the
 * screen off. Shows one ongoing notification with overall progress.
 */
class QueueService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "分轨处理", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("准备中", 0f))
        acquireWakeLock()
        val job = QueueManager.runLoop(applicationContext, scope)
        scope.launch {
            QueueManager.state.collectLatest { state ->
                val current = state.entries.firstOrNull { it.status == EntryStatus.RUNNING }
                val text = when {
                    current != null -> "${current.item.name} · ${current.stage}"
                    state.entries.isNotEmpty() -> "已完成 ${state.doneCount}/${state.entries.size}"
                    else -> "处理中"
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text, state.overallProgress))
            }
        }
        scope.launch {
            job.join()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, progress: Float): Notification {
        val launchIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_ff)
            .setContentTitle("分轨伴奏 · 正在做伴奏")
            .setContentText(text)
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "banzou:separation").apply {
            // Safety cap: 6 hours. Songs are queued in small batches.
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "separation"
        private const val NOTIFICATION_ID = 42
    }
}
