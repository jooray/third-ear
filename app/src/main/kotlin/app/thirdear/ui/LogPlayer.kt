package app.thirdear.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import app.thirdear.models.Recording
import kotlinx.coroutines.delay

/** One MediaPlayer for the Log: play a recording, seek, skip; pauses when headphones drop. */
class LogPlayer(private val context: Context) {
    var current by mutableStateOf<Recording?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null
    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = pause()
    }

    fun play(r: Recording) {
        current = r
        positionMs = 0
        durationMs = r.duration * 1000
        val mp = player ?: MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setOnCompletionListener {
                playing = false
                positionMs = durationMs
                unregisterNoisy()
            }
            setOnPreparedListener {
                durationMs = it.duration.coerceAtLeast(0)
                start()
            }
        }.also { player = it }
        try {
            mp.reset()
            mp.setDataSource(context, r.path.toUri())
            mp.prepareAsync()
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            playing = false
        }
    }

    private fun start() {
        player?.start()
        playing = true
        registerNoisy()
    }

    fun toggle() {
        val r = current ?: return
        when {
            playing -> pause()
            player == null || (positionMs >= durationMs && durationMs > 0) -> play(r)
            else -> start()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
        unregisterNoisy()
    }

    fun seekTo(ms: Int) {
        val mp = player ?: return
        positionMs = ms.coerceIn(0, durationMs)
        runCatching { mp.seekTo(positionMs) }
    }

    fun skip(byMs: Int) = seekTo(positionMs + byMs)

    fun stopIf(r: Recording) {
        if (current?.id == r.id) {
            pause()
            current = null
        }
    }

    internal fun tick() {
        val mp = player ?: return
        if (playing) positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
    }

    fun release() {
        unregisterNoisy()
        player?.release()
        player = null
        playing = false
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
    }
}

@Composable
fun rememberLogPlayer(): LogPlayer {
    val context = LocalContext.current
    val player = remember { LogPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(250)
        }
    }
    return player
}
