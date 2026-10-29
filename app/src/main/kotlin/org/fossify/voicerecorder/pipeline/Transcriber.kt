package org.fossify.voicerecorder.pipeline

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * On-device transcription of a finished recording through Offline Speech to Text (OSTT),
 * headless (works with the screen locked), through one of two contracts:
 *
 * - **file API** (Juraj's OSTT fork): `SpeechRecognizer` with `app.offlinespeechtotext.extra.AUDIO_URI`,
 *   a `content:` Uri the provider package was granted; OSTT decodes the file itself, any length;
 * - **caller audio** (Android 13+, a stock OSTT): the file decoded here to 16 kHz mono PCM16 and
 *   streamed through `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
 *
 * A provider that is not known to take the file API never gets `AUDIO_URI`: one that ignores it
 * would start listening to the microphone instead.
 */
class Transcriber(private val context: Context) {

    companion object {
        const val OSTT_PACKAGE = "app.offlinespeechtotext"
        const val ACTION_TRANSCRIBE_AUDIO = "app.offlinespeechtotext.action.TRANSCRIBE_AUDIO"
        const val EXTRA_AUDIO_URI = "app.offlinespeechtotext.extra.AUDIO_URI"
        const val EXTRA_OUTPUT_URI = "app.offlinespeechtotext.extra.OUTPUT_URI"

        /** OSTT answers a request it can't take now (one job at a time) with these. */
        val BUSY = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
        private val PREFERRED = listOf(OSTT_PACKAGE, "$OSTT_PACKAGE.validation", "dev.notune.transcribe")
    }

    /** [busy]: the provider was in use or dropped us; worth retrying. [noSpeech]: ran, found no words. */
    class Failed(message: String, val busy: Boolean = false, val noSpeech: Boolean = false) : Exception(message)

    enum class Contract { FILE, CALLER_AUDIO }

    /** Recognition services on the phone, OSTT first. */
    fun providers(): List<ComponentName> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) PREFERRED.size else it } }
    }.getOrDefault(emptyList())

    /** [flattened]: the user's choice; null or gone picks the best one found. */
    fun resolve(flattened: String?): ComponentName? {
        val all = providers()
        return flattened?.let { ComponentName.unflattenFromString(it) }?.takeIf { it in all } ?: all.firstOrNull()
    }

    /** OSTT's file API ships with its TRANSCRIBE_AUDIO activity; their presence is the capability probe. */
    fun supportsFileApi(component: ComponentName): Boolean = runCatching {
        context.packageManager.queryIntentActivities(Intent(ACTION_TRANSCRIBE_AUDIO).setPackage(component.packageName), 0).isNotEmpty()
    }.getOrDefault(false)

    fun contractFor(component: ComponentName): Contract? = when {
        supportsFileApi(component) -> Contract.FILE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Contract.CALLER_AUDIO
        else -> null
    }

    fun available(flattened: String?): Boolean = resolve(flattened)?.let { contractFor(it) != null } == true

    /** [audio] is a `content:` Uri of this app's FileProvider. Main-thread work is posted internally. */
    suspend fun transcribe(audio: Uri, component: ComponentName, durationMs: Long): String {
        val contract = contractFor(component) ?: throw Failed("needs Android 13 or OSTT with the file API")
        // inference runs above realtime on a phone; allow slow models and long recordings
        val timeout = 180_000L + durationMs * 2
        return try {
            withTimeout(timeout) { run(audio, component, contract) }
        } catch (e: TimeoutCancellationException) {
            // a newer recognition request (another app) can retire ours without an answer
            throw Failed("no answer in ${timeout / 1000} s", busy = true)
        }
    }

    private suspend fun run(audio: Uri, component: ComponentName, contract: Contract): String = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var feeder: Thread? = null

        fun cleanup() {
            main.post {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            pipe?.forEach { runCatching { it.close() } }
            feeder?.interrupt()
            if (contract == Contract.FILE) runCatching { context.revokeUriPermission(component.packageName, audio, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        fun finish(result: Result<String>) {
            cleanup()
            if (cont.isActive) cont.resumeWith(result)
        }

        main.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(context, component)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (text.isNullOrBlank()) finish(Result.failure(Failed("no speech recognised", noSpeech = true)))
                        else finish(Result.success(text))
                    }

                    override fun onError(error: Int) {
                        finish(Result.failure(Failed(errorName(error), busy = error in BUSY, noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH)))
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                when (contract) {
                    Contract.FILE -> {
                        context.grantUriPermission(component.packageName, audio, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.putExtra(EXTRA_AUDIO_URI, audio)
                        r.startListening(intent)
                    }

                    Contract.CALLER_AUDIO -> {
                        val p = ParcelFileDescriptor.createPipe()
                        pipe = p
                        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmDecoder.RATE)
                        r.startListening(intent)
                        feeder = Thread({
                            // whole frames, then EOF: OSTT finalises on the closed writer
                            try {
                                ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { out -> PcmDecoder.decode(context, audio, out) }
                            } catch (_: Exception) {
                                // the reader went away; the listener reports why
                            }
                        }, "third-ear-transcribe-feed").apply { start() }
                    }
                }
            } catch (t: Throwable) {
                finish(Result.failure(Failed(t.message ?: t.javaClass.simpleName)))
            }
        }
        cont.invokeOnCancellation {
            main.post { runCatching { recognizer?.cancel() } }
            cleanup()
        }
    }

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognised"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_AUDIO -> "audio error (the transcription app may be busy)"
        SpeechRecognizer.ERROR_CLIENT -> "client error (the transcription app could not read the file)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions (open Third Ear once)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the transcription app is busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "the transcription app disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "no speech model installed in the transcription app"
        else -> "recognition error $error"
    }
}
