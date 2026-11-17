package app.kaizen.commons

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.kaizen.R

/**
 * Kaizen: servicio en primer plano mientras se usa el proxy, para que Android no mate la app
 * (y corte el vídeo) cuando el reproductor externo está delante. Se para solo tras
 * [IDLE_STOP_MS] sin peticiones.
 */
class KaizenProxyService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - KaizenProxy.lastActivity > IDLE_STOP_MS) {
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Reproductor externo", NotificationManager.IMPORTANCE_MIN))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_player_white)
            .setContentTitle("KAIZEN")
            .setContentText("Sirviendo vídeo al reproductor externo")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, CHECK_INTERVAL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "kaizen_proxy"
        private const val NOTIFICATION_ID = 7420
        private const val IDLE_STOP_MS = 30 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KaizenProxyService::class.java))
            } catch (e: Exception) {
                // Sin el servicio el proxy funciona igual; solo es más fácil que Android cierre la app.
                logError(e)
            }
        }
    }
}
