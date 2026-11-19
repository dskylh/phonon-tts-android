package ai.gradium.phonon.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicLong

/** Sentences synthesized per engine since the process started; the listening test reads the difference. */
object SpeechStats {
    val npu = AtomicLong()
    val cpu = AtomicLong()
}

/**
 * Keeps the app's process in the foreground while the listening test runs in the background or with the screen off,
 * the way a reader app's playback service would. It does no work itself.
 */
class ListenTestService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("benchmark", "Benchmark", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "benchmark") else Notification.Builder(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, BenchmarkActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Listening test running")
            .setContentText("Keep the screen as you set it until it finishes.").setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(2, notification)
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ListenTestService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, ListenTestService::class.java)) }
    }
}
