package app.thirdear.whitenoise

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.thirdear.R
import app.thirdear.activities.MainActivity
import app.thirdear.extensions.config

/**
 * Keeps Third Ear's White Noise runtime connected while White Noise is on, so messages arrive
 * (and Android Auto can announce them) with the app closed. Also carries out the notification
 * actions: Reply, Mark as read, Read aloud. Foreground type remoteMessaging.
 */
class WhiteNoiseService : Service() {

    companion object {
        private const val TAG = "ThirdEarWN"
        private const val P = "app.thirdear.wn."
        const val ACTION_START = P + "START"
        const val ACTION_STOP = P + "STOP"
        const val ACTION_REPLY = P + "REPLY"
        const val ACTION_MARK_READ = P + "MARK_READ"
        const val ACTION_READ_GROUP = P + "READ_GROUP"
        const val ACTION_PLAY_MESSAGE = P + "PLAY_MESSAGE"
        const val EXTRA_GROUP = "group"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "white_noise_service"
        private const val NOTIF_ID = 4900
        private val START_RETRIES_MS = listOf(0L, 3_000L, 10_000L, 30_000L, 60_000L)

        fun intent(context: Context, action: String): Intent = Intent(context, WhiteNoiseService::class.java).setAction(action)

        fun start(context: Context) {
            WhiteNoise.checkRestored(context)
            if (!context.config.whiteNoiseEnabled || !MarmotHost.supported()) return
            runCatching { ContextCompat.startForegroundService(context, intent(context, ACTION_START)) }
                .onFailure { Log.w(TAG, "could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.startService(intent(context, ACTION_STOP)) }
        }

        fun playMessage(context: Context, messageId: String) {
            runCatching { ContextCompat.startForegroundService(context, intent(context, ACTION_PLAY_MESSAGE).putExtra(EXTRA_MESSAGE, messageId)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.te_channel_white_noise), NotificationManager.IMPORTANCE_MIN))
        MessageNotifier.createChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setContentTitle(getString(R.string.te_wn_connected))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, MainActivity.intent(this, MainActivity.OPEN_SETUP), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground refused: ${t.message}")
        }
        MarmotHost.onIncoming = { m -> scope.launch { WhiteNoise.onIncoming(this@WhiteNoiseService, m) } }
        CarWatch.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val group = intent?.getStringExtra(EXTRA_GROUP)
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    MarmotHost.stop()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_REPLY -> {
                val text = intent.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(MessageNotifier.KEY_REPLY)?.toString() }
                if (group != null && !text.isNullOrBlank()) {
                    scope.launch {
                        runCatching {
                            WhiteNoise.sendText(this@WhiteNoiseService, group, text)
                            WhiteNoise.markRead(this@WhiteNoiseService, group, IncomingStore(this@WhiteNoiseService).unread(group).map { it.id })
                        }.onFailure { Log.w(TAG, "reply failed: ${it.message}") }
                    }
                }
            }

            ACTION_MARK_READ -> if (group != null) scope.launch {
                WhiteNoise.markRead(this@WhiteNoiseService, group, IncomingStore(this@WhiteNoiseService).unread(group).map { it.id })
            }

            ACTION_READ_GROUP -> if (group != null) scope.launch { WhiteNoise.playUnread(this@WhiteNoiseService, group) }
            ACTION_PLAY_MESSAGE -> intent.getStringExtra(EXTRA_MESSAGE)?.let { id ->
                scope.launch {
                    val m = IncomingStore(this@WhiteNoiseService).get(id) ?: return@launch
                    WhiteNoise.play(this@WhiteNoiseService, m)
                    WhiteNoise.markRead(this@WhiteNoiseService, m.groupId, listOf(m.id))
                }
            }
        }
        if (!config.whiteNoiseEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            // a first start can fail (seen right after a fresh launch) where turning White Noise off
            // and on again works: try again a few times before leaving it failed
            for (wait in START_RETRIES_MS) {
                delay(wait)
                if (!config.whiteNoiseEnabled) return@launch
                if (MarmotHost.phase.value == MarmotHost.Phase.READY) return@launch
                MarmotHost.start(this@WhiteNoiseService)
                if (MarmotHost.phase.value == MarmotHost.Phase.READY) return@launch
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MarmotHost.onIncoming = null
        CarWatch.stop()
        scope.cancel()
        super.onDestroy()
    }
}
