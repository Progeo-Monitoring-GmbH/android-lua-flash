package com.progeo.luaflasher.flash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.progeo.luaflasher.MainActivity
import com.progeo.luaflasher.R
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch

/**
 * Keeps the process and CPU alive while a flash runs, so a short trip to another app does
 * not break the job (PLAN.md section 4). Stops itself when the job ends.
 */
class FlashService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Flashing", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, notification("Preparing"), type)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luaflasher:flash")
            .apply { acquire(30 * 60 * 1000L) }

        val session = applicationContext.graph.flash
        lifecycleScope.launch {
            session.state.collect { state ->
                val text = when (state) {
                    FlashState.Idle -> null
                    FlashState.AwaitBootMode -> "Waiting: set the jumper and press RST"
                    FlashState.Syncing -> "Connecting to the ESP8266"
                    is FlashState.Writing -> "Writing firmware ${((state.progress?.fraction ?: 0f) * 100).toInt()} %"
                    is FlashState.AwaitNormalMode -> "Waiting: remove the jumper and press RST"
                    is FlashState.Installing -> state.progress.step.label
                    is FlashState.Done -> if (state.result.success) "Done" else "Finished, boot not confirmed"
                    is FlashState.Failed -> "Failed: ${state.message}"
                }
                if (text == null || state is FlashState.Done) {
                    stopSelf()
                } else {
                    manager.notify(ID, notification(text))
                }
            }
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("LUA Flasher")
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
        private const val CHANNEL = "flash"
        private const val ID = 1
    }
}
