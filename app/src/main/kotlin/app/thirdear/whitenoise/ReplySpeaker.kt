package app.thirdear.whitenoise

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import app.thirdear.pipeline.Cues

/**
 * Plays White Noise messages (from I Hear You): voice notes as they are, text through the phone's
 * text-to-speech engine. Other audio is paused for the length of a message (transient focus) and
 * messages queue one after another.
 */
class ReplySpeaker(private val context: Context) {

    companion object {
        private const val ENGINE_START_TIMEOUT_MS = 10_000L

        // holds the application context only
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var shared: ReplySpeaker? = null
        fun get(context: Context): ReplySpeaker = shared ?: synchronized(this) {
            shared ?: ReplySpeaker(context.applicationContext).also { shared = it }
        }
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = Mutex()
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var skip = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Stops what is playing now. */
    fun skipCurrent(): Boolean {
        val p = player
        val speaking = tts?.isSpeaking == true
        if (p == null && !speaking) return false
        skip = true
        runCatching { p?.stop() }
        tts?.stop()
        return true
    }

    suspend fun speak(text: String) = queue.withLock {
        val clean = plain(text)
        if (clean.isBlank()) return@withLock
        val engine = engine() ?: error("no text-to-speech engine")
        withFocus {
            Cues.play(Cues.Tone.REPLY)
            for (chunk in chunks(clean)) {
                if (skip) break
                speakOne(engine, chunk)
            }
        }
    }

    suspend fun play(file: File, speed: Float = 1f) = queue.withLock {
        withFocus {
            Cues.play(Cues.Tone.REPLY)
            suspendCancellableCoroutine { cont ->
                val p = MediaPlayer()
                player = p
                fun done() {
                    player = null
                    runCatching { p.release() }
                    if (cont.isActive) cont.resume(Unit)
                }
                try {
                    p.setAudioAttributes(attrs)
                    p.setDataSource(file.path)
                    p.setOnCompletionListener { done() }
                    p.setOnErrorListener { _, _, _ -> done(); true }
                    p.prepare()
                    // setting a speed starts playback, so it comes with the start
                    if (speed != 1f) p.playbackParams = p.playbackParams.setSpeed(speed) else p.start()
                } catch (ignored: Throwable) {
                    // a file the player can't open ends like one that finished: on to the next
                    done()
                }
                cont.invokeOnCancellation { runCatching { p.stop() }; player = null; runCatching { p.release() } }
            }
        }
    }

    private suspend fun engine(): TextToSpeech? {
        tts?.let { if (ttsReady?.await() == true) return it }
        val ready = CompletableDeferred<Boolean>()
        ttsReady = ready
        val t = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        tts = t
        if (withTimeoutOrNull(ENGINE_START_TIMEOUT_MS) { ready.await() } != true) {
            t.shutdown()
            tts = null
            return null
        }
        t.setAudioAttributes(attrs)
        return t
    }

    private suspend fun speakOne(engine: TextToSpeech, text: String) = suspendCancellableCoroutine { cont ->
        val id = UUID.randomUUID().toString()
        engine.language = guessLocale(text)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // only the end of the utterance matters
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        cont.invokeOnCancellation { engine.stop() }
    }

    private suspend fun withFocus(block: suspend () -> Unit) {
        skip = false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(request)
        try {
            block()
        } finally {
            audio.abandonAudioFocusRequest(request)
        }
    }

    private fun chunks(text: String): List<String> {
        val max = minOf(TextToSpeech.getMaxSpeechInputLength(), 3_000)
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max).takeIf { it > max / 3 } ?: max
            out += rest.substring(0, cut + 1)
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest
        return out
    }
}

/** Agent replies are often markdown; read the words, not the markup. */
internal fun plain(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), " ")
    .replace(Regex("[*_`#>|~]+"), " ")
    .replace(Regex("^\\s*[-+]\\s+", RegexOption.MULTILINE), "")
    .replace(Regex("[ \\t]+"), " ")
    .trim()

/** The voice language from the text itself, among the app's spoken languages; else the phone's. */
internal fun guessLocale(text: String): Locale {
    val t = text.lowercase()
    fun has(vararg c: Char) = c.count { t.indexOf(it) >= 0 }
    return when {
        has('ľ', 'ĺ', 'ŕ', 'ô') > 0 -> Locale.forLanguageTag("sk")
        has('ř', 'ů', 'ě') > 0 -> Locale.forLanguageTag("cs")
        has('č', 'š', 'ž', 'ý', 'á', 'í', 'é', 'ť', 'ď', 'ň') >= 2 -> Locale.forLanguageTag("sk")
        has('ä', 'ö', 'ü', 'ß') > 0 -> Locale.GERMAN
        has('ñ', '¿', '¡') > 0 -> Locale.forLanguageTag("es")
        Regex("\\b(the|and|you|is|are|this|that)\\b").containsMatchIn(t) -> Locale.ENGLISH
        else -> Locale.getDefault()
    }
}
