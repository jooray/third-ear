package app.thirdear.pipeline

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.Destination

/**
 * Short sounds and spoken clips (from I Hear You: a woman's voice, per language, in res/raw-<lang>)
 * so recording can be driven without looking at the phone: in a headset, in the car. Blocking:
 * call off the main thread.
 */
object Cues {

    /** How a moment is announced: not at all, a short tone, or a spoken clip. */
    enum class Mode { OFF, BEEP, VOICE }

    enum class Tone(val notes: List<Pair<Double, Int>>) {
        START(listOf(660.0 to 110, 0.0 to 40, 990.0 to 140)),
        STOP(listOf(990.0 to 110, 0.0 to 40, 660.0 to 140)),
        SENT(listOf(1320.0 to 70, 0.0 to 50, 1320.0 to 70, 0.0 to 50, 1320.0 to 70)),
        FAILED(listOf(220.0 to 420)),
        REPLY(listOf(880.0 to 90)),
        WARN(listOf(440.0 to 90, 0.0 to 60, 440.0 to 90)),

        /** Bluetooth microphone did not come up or went away: recording continues on the phone. */
        FALLBACK(listOf(880.0 to 90, 0.0 to 30, 660.0 to 90, 0.0 to 30, 440.0 to 160)),
    }

    private const val RATE = 24_000
    /** Peak of a tone (of 32767): loud enough over a car, not clipping. */
    private const val AMPLITUDE = 9_000
    /** After the last note: the track drains before it is released. */
    private const val TAIL_MS = 60

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * The microphone records now: you can speak. "Go ahead" only from a Bluetooth microphone; the
     * phone's own microphone would record the clip from the speaker into the note (and its
     * transcript), so there it is the short tone.
     */
    fun playListening(context: Context, mode: Mode, bluetooth: Boolean) = when {
        mode == Mode.OFF -> Unit
        mode == Mode.VOICE && bluetooth -> playVoice(context, R.raw.voice_go_ahead, Tone.START)
        else -> play(Tone.START)
    }

    /** After the stop, what happens next: a falling tone, or in a few words ("Saved, transcribing.", "Sending to White Noise."). */
    fun playStopped(context: Context, mode: Mode, plan: ActionPlan, failed: Boolean) = when {
        mode == Mode.OFF -> Unit
        failed -> play(Tone.FAILED)
        mode == Mode.VOICE -> playVoice(context, doneClip(plan), Tone.STOP)
        else -> play(Tone.STOP)
    }

    fun doneClip(plan: ActionPlan): Int {
        val tr = plan.transcribe
        return when (plan.action.destination) {
            Destination.SAVE, Destination.COPY_TRANSCRIPT -> if (tr) R.raw.voice_done_save_tr else R.raw.voice_done_save
            Destination.WHITE_NOISE -> if (tr) R.raw.voice_done_white_noise_tr else R.raw.voice_done_white_noise
            Destination.WEBHOOK -> if (tr) R.raw.voice_done_webhook_tr else R.raw.voice_done_webhook
            Destination.SHARE -> if (tr) R.raw.voice_done_share_tr else R.raw.voice_done_share
        }
    }

    /** The clips start with 2 s of silence, so a Bluetooth link switching mode doesn't clip the first word. */
    private fun playVoice(context: Context, res: Int, fallback: Tone?) {
        val done = CountDownLatch(1)
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                context.resources.openRawResourceFd(res).use { setDataSource(it) }
                setOnCompletionListener { done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); true }
                prepare()
            }
        }.getOrElse {
            fallback?.let { play(it) }
            return
        }
        try {
            mp.start()
            done.await(mp.duration.toLong().coerceAtLeast(0) + 1_000, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { mp.stop() }
            mp.release()
        }
    }

    fun play(tone: Tone) {
        runCatching {
            val pcm = render(tone)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(tone.notes.sumOf { it.second }.toLong() + TAIL_MS)
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    private fun render(tone: Tone): ShortArray {
        val total = tone.notes.sumOf { it.second } * RATE / 1000
        val out = ShortArray(total)
        var i = 0
        for ((freq, ms) in tone.notes) {
            val n = ms * RATE / 1000
            val fade = min(n / 4, RATE / 200)
            for (k in 0 until n) {
                val env = when {
                    k < fade -> k.toDouble() / fade
                    k > n - fade -> (n - k).toDouble() / fade
                    else -> 1.0
                }
                out[i + k] = if (freq == 0.0) 0 else (sin(2 * PI * freq * k / RATE) * env * AMPLITUDE).toInt().toShort()
            }
            i += n
        }
        return out
    }
}
