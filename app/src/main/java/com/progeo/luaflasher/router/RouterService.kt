package com.progeo.luaflasher.router

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.progeo.luaflasher.MainActivity
import com.progeo.luaflasher.R
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a router session is open: a firmware upload and the wait for the
 * reboot take minutes, and the router's Wi-Fi is only held while the app runs. Holds a wake lock
 * while an operation runs; stops itself when the session is closed.
 */
class RouterService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Router session", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, notification("Connecting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luaflasher:router")
        wakeLock = lock

        lifecycleScope.launch {
            applicationContext.graph.router.state.collect { s ->
                if (s.phase == Phase.IDLE) {
                    stopSelf()
                    return@collect
                }
                if (s.busy != null && !lock.isHeld) lock.acquire(20 * 60 * 1000L)
                if (s.busy == null && lock.isHeld) lock.release()
                val text = s.busy?.let { b -> s.progress?.let { "$b ${(it * 100).toInt()} %" } ?: b }
                    ?: s.error
                    ?: "Connected to ${s.device?.modelName ?: "router"} ${s.serial ?: ""}".trim()
                manager.notify(ID, notification(text))
            }
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Router")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "router"
        private const val ID = 2

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RouterService::class.java)) }
        }
    }
}
