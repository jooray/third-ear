package app.thirdear.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.thirdear.R
import app.thirdear.pipeline.PcmDecoder
import app.thirdear.whitenoise.GroupSettings
import java.io.File
import java.io.OutputStream
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A voice message's shape: bar heights (0..1) and its length. */
data class Waveform(val bars: List<Float>, val durationMs: Long)

/** Waveforms decoded once per file, for as long as the app runs. */
object Waveforms {
    const val BARS = 48
    private const val WINDOW = 800 // 50 ms at 16 kHz
    private val cache = ConcurrentHashMap<String, Waveform>()

    fun cached(files: List<File>): Waveform? = cache[key(files)]

    /** Decodes [files] (one message may carry several) into one shape; null when none decodes. */
    fun of(context: Context, files: List<File>): Waveform? {
        if (files.isEmpty()) return null
        cache[key(files)]?.let { return it }
        val peaks = ArrayList<Int>()
        var samples = 0L
        for (f in files) {
            val sink = PeakSink(peaks)
            runCatching { PcmDecoder.decode(context, Uri.fromFile(f), sink) }.onFailure { return null }
            sink.flush()
            samples += sink.samples
        }
        if (peaks.isEmpty()) return null
        val top = peaks.max().coerceAtLeast(1)
        val bars = (0 until BARS).map { i ->
            val from = i * peaks.size / BARS
            val to = ((i + 1) * peaks.size / BARS).coerceAtLeast(from + 1).coerceAtMost(peaks.size)
            (peaks.subList(from, to).maxOrNull() ?: 0).toFloat() / top
        }
        return Waveform(bars, samples * 1000 / PcmDecoder.RATE).also { cache[key(files)] = it }
    }

    private fun key(files: List<File>) = files.joinToString("|") { it.path }

    /** Takes 16-bit little-endian mono PCM, keeps the peak of every 50 ms window. */
    private class PeakSink(private val peaks: MutableList<Int>) : OutputStream() {
        var samples = 0L
        private var low = -1
        private var peak = 0
        private var inWindow = 0

        override fun write(b: Int) {
            if (low < 0) {
                low = b and BYTE_MASK
                return
            }
            val v = ((b shl Byte.SIZE_BITS) or low).toShort().toInt()
            low = -1
            samples++
            peak = maxOf(peak, kotlin.math.abs(v))
            if (++inWindow == WINDOW) flush()
        }

        override fun flush() {
            if (inWindow == 0) return
            peaks += peak
            peak = 0
            inWindow = 0
        }
    }
}

/** One player for a conversation: one voice message at a time, seekable, at a chosen speed. */
class ChatPlayer(private val context: Context) {
    var current by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private var player: MediaPlayer? = null
    private var queue: List<File> = emptyList()
    private var part = 0
    private var onDone: (() -> Unit)? = null

    /** Starts [files] as message [id] (from [startMs]), or pauses/resumes it when it is the current one. */
    fun toggle(id: String, files: List<File>, startSpeed: Float, startMs: Int = 0, onFinished: () -> Unit = {}) {
        if (current == id && player != null) {
            if (playing) pause() else resume()
            return
        }
        release()
        current = id
        queue = files
        part = 0
        speed = startSpeed
        onDone = onFinished
        open(startMs)
    }

    private fun open(startMs: Int) {
        val f = queue.getOrNull(part) ?: return finish()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.path)
            mp.setOnCompletionListener {
                part++
                if (part < queue.size) {
                    it.release()
                    open(0)
                } else {
                    finish()
                }
            }
            mp.prepare()
            durationMs = mp.duration.coerceAtLeast(0)
            if (startMs > 0) mp.seekTo(startMs.coerceAtMost(durationMs))
            positionMs = startMs.coerceAtMost(durationMs)
            resume()
        } catch (ignored: Exception) {
            // a file that doesn't open ends the message like one that finished
            finish()
        }
    }

    private fun finish() {
        playing = false
        positionMs = durationMs
        player?.release()
        player = null
        onDone?.invoke()
        onDone = null
    }

    private fun resume() {
        val mp = player ?: return
        // setting a speed starts playback; at 1x a plain start does the same
        if (speed != 1f) mp.playbackParams = mp.playbackParams.setSpeed(speed) else mp.start()
        playing = true
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    /** Seeks within the current message; a message not playing yet starts there. */
    fun seek(id: String, fraction: Float, files: List<File>, startSpeed: Float) {
        if (current != id || player == null) {
            val total = Waveforms.cached(files)?.durationMs?.toInt() ?: 0
            toggle(id, files, startSpeed, (fraction * total).toInt())
            return
        }
        val mp = player ?: return
        positionMs = (fraction * durationMs).toInt().coerceIn(0, durationMs)
        runCatching { mp.seekTo(positionMs) }
    }

    /** Changes the speed of the current message; applied right away when playing. */
    fun changeSpeed(to: Float) {
        speed = to
        if (playing) player?.let { it.playbackParams = it.playbackParams.setSpeed(to) }
    }

    internal fun tick() {
        val mp = player ?: return
        if (playing) positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
    }

    fun release() {
        player?.release()
        player = null
        playing = false
        current = null
        positionMs = 0
    }
}

@Composable
fun rememberChatPlayer(): ChatPlayer {
    val context = LocalContext.current
    val player = remember { ChatPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(100)
        }
    }
    return player
}

fun speedLabel(speed: Float): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(speed) + "×"

/**
 * A voice message in a bubble: play/pause, the waveform (tap or drag to jump), elapsed and total
 * time, and the speed (tap to change). [load] fetches the files (download on first use).
 */
@Composable
fun VoicePlayer(
    id: String,
    groupSpeed: Float,
    player: ChatPlayer,
    load: suspend () -> List<File>,
    onPlayToEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Te.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember(id) { mutableStateOf<List<File>?>(null) }
    var wave by remember(id) { mutableStateOf<Waveform?>(null) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val f = loader()
            files = f
            wave = Waveforms.of(context, f)
        }
    }
    val mine = player.current == id
    val total = if (mine && player.durationMs > 0) player.durationMs.toLong() else wave?.durationMs ?: 0L
    val progress = if (mine && total > 0) player.positionMs.toFloat() / total else 0f
    // the speed this message plays at: the group's, until changed here
    var chosen by remember(id, groupSpeed) { mutableFloatStateOf(groupSpeed) }

    fun withFiles(block: (List<File>) -> Unit) {
        val f = files
        if (f != null) {
            if (f.isNotEmpty()) block(f)
            return
        }
        scope.launch { withContext(Dispatchers.IO) { loader() }.also { files = it }.takeIf { it.isNotEmpty() }?.let(block) }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(p.ink)
                .clickable { withFiles { player.toggle(id, it, chosen, onFinished = onPlayToEnd) } },
            contentAlignment = Alignment.Center,
        ) {
            PlayIcon(playing = mine && player.playing)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val bars = wave?.bars ?: List(Waveforms.BARS) { FLAT_BAR }
            val seekLabel = stringResource(R.string.te_seek)
            WaveformBars(bars, progress, seekLabel) { f -> withFiles { player.seek(id, f, it, chosen) } }
            Figures(times(if (mine) player.positionMs.toLong() else null, total), Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            speedLabel(chosen),
            style = TeType.smallStrong,
            color = p.ink,
            modifier = Modifier
                .clip(CellShape)
                .background(p.plateHi)
                .clickable {
                    chosen = nextSpeed(chosen)
                    if (mine) player.changeSpeed(chosen)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .semantics { contentDescription = context.getString(R.string.te_speed_now, speedLabel(chosen)) },
        )
    }
}

@Composable
private fun PlayIcon(playing: Boolean) {
    Icon(
        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
        stringResource(if (playing) R.string.pause_recording else R.string.te_play),
        tint = Te.colors.face,
        modifier = Modifier.size(24.dp),
    )
}

/** "0:12 / 0:48" while it plays, the length before, a dash until it is known. */
private fun times(positionMs: Long?, totalMs: Long): String = when {
    positionMs != null -> "${clockOf(positionMs)} / ${clockOf(totalMs)}"
    totalMs > 0 -> clockOf(totalMs)
    else -> "–:––"
}

/** The bars of a waveform, the played part in ink; a tap or a drag seeks to that fraction. */
@Composable
private fun WaveformBars(bars: List<Float>, progress: Float, label: String, onSeek: (Float) -> Unit) {
    val p = Te.colors
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> seek((change.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars.size - 1)) / bars.size
        bars.forEachIndexed { i, v ->
            val h = v.coerceAtLeast(MIN_BAR) * size.height
            val played = (i + 0.5f) / bars.size <= progress
            drawRoundRect(
                color = if (played) p.ink else p.inkMuted.copy(alpha = UNPLAYED_ALPHA),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

private const val FLAT_BAR = 0.15f
private const val BYTE_MASK = 0xFF
private const val MIN_BAR = 0.08f
private const val UNPLAYED_ALPHA = 0.45f
private const val SPEED_STEP_TOLERANCE = 0.01f
private const val SECONDS_PER_MINUTE = 60

/** The next of the offered speeds after [now], wrapping to the first. */
fun nextSpeed(now: Float): Float {
    val list = GroupSettings.SPEEDS
    return list.firstOrNull { it > now + SPEED_STEP_TOLERANCE } ?: list.first()
}

private fun clockOf(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
}
