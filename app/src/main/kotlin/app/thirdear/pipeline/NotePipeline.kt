package app.thirdear.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFFileSdk30
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import app.thirdear.BuildConfig
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.Destination
import app.thirdear.activities.MainActivity
import app.thirdear.activities.SplashActivity
import app.thirdear.audio.Finaliser
import app.thirdear.extensions.config
import app.thirdear.models.Events
import app.thirdear.services.RecorderService
import app.thirdear.whitenoise.WhiteNoise
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject

/**
 * What happens after a stop, one note at a time: finish the file, save it to the recordings
 * folder, transcribe, copy, deliver. Every step is persisted in the [Note], so a note the process
 * died on picks up where it was, and a failed delivery keeps the saved recording with a Retry.
 * An ongoing silent notification names the step (saving, transcribing, waiting for the
 * transcription app, sending…) and how many notes wait behind it.
 */
class NotePipeline(private val context: Context, private val store: NoteStore = NoteStore(context)) {

    companion object {
        private const val TAG = "ThirdEarPipeline"
        const val CHANNEL = "pipeline"
        const val NOTIF_PROGRESS = 44
        const val NOTIF_RESULT = 42
        private const val COPIED_CHANNEL = "pipeline_result"
    }

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val lock = Mutex()
    private val waiting = AtomicInteger()
    private val transcriber = Transcriber(context)
    @Volatile private var stage: Pair<String, Note>? = null

    /** True while a note is in the pipeline or waits for it. */
    val busy: Boolean get() = lock.isLocked || waiting.get() > 0

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.te_channel_pipeline), NotificationManager.IMPORTANCE_LOW),
        )
        notifications.createNotificationChannel(
            NotificationChannel(COPIED_CHANNEL, context.getString(R.string.te_channel_results), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null) },
        )
    }

    suspend fun process(id: String) {
        waiting.incrementAndGet()
        stage?.let { (title, n) -> progress(title, n) }
        lock.withLock {
            waiting.decrementAndGet()
            try {
                val note = store.get(id) ?: return@withLock
                run(note)
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline failed", t)
                store.get(id)?.let { store.put(it.copy(state = NoteState.FAILED, step = null, error = t.message ?: t.javaClass.simpleName)) }
            } finally {
                stage = null
                notifications.cancel(NOTIF_PROGRESS)
            }
        }
    }

    /** Notes the process died on: finish their files and save them; recovered recordings are never sent. */
    suspend fun recover(liveId: String?) {
        for (n in store.unfinished()) {
            if (n.id == liveId) continue
            val note = if (n.state == NoteState.RECORDING) {
                val work = store.work(n)
                if (!work.exists() || work.length() < 64) {
                    store.delete(n.id)
                    continue
                }
                n.copy(state = NoteState.PROCESSING, recovered = true).also { store.put(it) }
            } else {
                n
            }
            Log.i(TAG, "recovering ${note.id}")
            process(note.id)
        }
    }

    private suspend fun run(start: Note) {
        var note = start.copy(state = NoteState.PROCESSING, error = null)
        val plan = ActionPlan.of(note.action)
        note = step(note, NoteStep.SAVING)

        // 1. the playable file
        val audio = store.audio(note)
        if (!audio.exists() || audio.length() == 0L) {
            try {
                val result = Finaliser.finalise(store.work(note), audio, note.container)
                if (note.durationMs == 0L) note = note.copy(durationMs = result.durationMs)
            } catch (t: Throwable) {
                fail(note, context.getString(R.string.te_error_finalise, t.message ?: t.javaClass.simpleName))
                return
            }
        }

        // 2. saved to the recordings folder, before anything that can fail
        if (note.savedUri == null) {
            try {
                val (uri, name) = export(audio, note.fileName, note.container.mime)
                note = note.copy(savedUri = uri.toString(), savedName = name)
                store.put(note)
                EventBus.getDefault().post(Events.RecordingCompleted())
                EventBus.getDefault().post(Events.RecordingSaved(uri))
            } catch (t: Throwable) {
                // the working copy stays in app storage; delivery still works from it
                Log.w(TAG, "saving failed", t)
                note = note.copy(error = context.getString(R.string.te_error_save, t.message ?: t.javaClass.simpleName))
                store.put(note)
            }
        }

        // 3. transcript
        if (plan.transcribe && !note.transcript && !note.recovered) note = transcribe(note, audio)
        val transcript = store.transcript(note.id)

        // 4. clipboard
        if (plan.copyTranscript && transcript != null) copyToClipboard(transcript)

        // 5. delivery
        if (!note.recovered) {
            try {
                deliver(note, plan, audio, transcript)
            } catch (t: Throwable) {
                val raw = t.message ?: t.javaClass.simpleName
                val why = if (raw.contains("EPERM") || raw.contains("UnknownHost")) context.getString(R.string.te_error_network) else raw
                fail(note, why)
                return
            }
        }
        val failedSave = note.savedUri == null
        note = note.copy(state = if (failedSave) NoteState.FAILED else NoteState.DONE, step = null)
        store.put(note)
        if (failedSave) {
            failureNotification(note, note.error ?: context.getString(R.string.te_error_save, "?"))
        } else {
            // the user's copy exists; the working copies only cost space (a share target may
            // still be reading the shared copy, so that one stays until the note is pruned)
            if (plan.action.destination != Destination.SHARE) store.dropAudio(note)
            if (note.recovered) simpleNotification(context.getString(R.string.te_recovered_title), context.getString(R.string.te_recovered_body, note.savedName))
            else if (plan.action.destination != Destination.SAVE && plan.action.destination != Destination.COPY_TRANSCRIPT && plan.action.destination != Destination.SHARE) {
                if (context.config.progressCue != Cues.Mode.OFF) Cues.play(Cues.Tone.SENT)
            }
        }
    }

    private suspend fun transcribe(start: Note, audio: File): Note {
        var note = start
        val component = transcriber.resolve(context.config.transcriber)
        if (component == null) {
            return note.copy(transcriptError = context.getString(R.string.te_error_no_transcriber)).also { store.put(it) }
        }
        note = step(note, NoteStep.TRANSCRIBING)
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", audio)
        return try {
            val text = Backoff().run(
                retryable = { it is Transcriber.Failed && it.busy },
                onWait = { attempt, wait, cause ->
                    Log.i(TAG, "transcription busy (${cause.message}), retry ${attempt + 1} in ${wait / 1000} s")
                    note = step(note, NoteStep.WAITING_FOR_TRANSCRIBER)
                },
                sleep = { delay(it) },
            ) {
                transcriber.transcribe(uri, component, note.durationMs).also { note = step(note, NoteStep.TRANSCRIBING) }
            }
            store.transcriptFile(note.id).writeText(text)
            note = note.copy(transcript = true, transcriptError = null)
            store.put(note)
            if (context.config.saveTranscriptFiles && note.savedUri != null) {
                runCatching { export(store.transcriptFile(note.id), note.baseName + ".txt", "text/plain") }
                    .onFailure { Log.w(TAG, "saving the transcript file failed: ${it.message}") }
            }
            note
        } catch (t: Throwable) {
            val why = (t as? Transcriber.Failed)?.let { if (it.noSpeech) context.getString(R.string.te_error_no_speech) else it.message } ?: t.message ?: t.javaClass.simpleName
            note.copy(transcriptError = why).also { store.put(it) }
        }
    }

    private suspend fun deliver(start: Note, plan: ActionPlan, audio: File, transcript: String?) {
        var note = start
        val delivery = plan.delivery(transcript)
        if (delivery == ActionPlan.Delivery.Nothing) return
        val action = plan.action
        note = step(note, NoteStep.SENDING)
        val name = note.fileName
        when (action.destination) {
            Destination.SHARE -> share(audio, note.container.mime, delivery)
            Destination.WEBHOOK -> {
                val url = action.webhookUrl?.takeIf { ActionPlan.isHttpUrl(it) } ?: error(context.getString(R.string.te_error_no_webhook))
                val meta = JSONObject()
                    .put("app", "third-ear")
                    .put("id", note.id)
                    .put("action", action.name)
                    .put("recorded_at", Instant.ofEpochMilli(note.startedAt).toString())
                    .put("duration_seconds", note.durationMs / 1000.0)
                    .put("microphone", note.mic)
                    .put("content", if (delivery is ActionPlan.Delivery.Text) "text" else if ((delivery as ActionPlan.Delivery.Audio).caption != null) "audio+text" else "audio")
                when (delivery) {
                    is ActionPlan.Delivery.Text -> Webhook.post(url, action.webhookAuth, meta, delivery.text, null, name, note.container.mime)
                    is ActionPlan.Delivery.Audio -> Webhook.post(url, action.webhookAuth, meta, delivery.caption, audio, name, note.container.mime)
                    else -> {}
                }
            }

            Destination.WHITE_NOISE -> {
                val group = action.groupId ?: error(context.getString(R.string.te_error_no_group))
                when (delivery) {
                    is ActionPlan.Delivery.Text -> WhiteNoise.sendText(context, group, delivery.text)
                    is ActionPlan.Delivery.Audio -> WhiteNoise.sendVoice(context, group, audio, name, note.container.mime, delivery.caption)
                    else -> {}
                }
            }

            Destination.SAVE, Destination.COPY_TRANSCRIPT -> {}
        }
    }

    private fun fail(note: Note, why: String) {
        val failed = note.copy(state = NoteState.FAILED, step = null, error = why)
        store.put(failed)
        if (context.config.progressCue != Cues.Mode.OFF) Cues.play(Cues.Tone.FAILED)
        failureNotification(failed, why)
    }

    /**
     * Copies [source] into the recordings folder under [fileName] (numbered when taken), the same
     * way Fossify writes recordings (SAF tree of the first parent folder). Returns the Uri and name.
     */
    fun export(source: File, fileName: String, mime: String): Pair<Uri, String> {
        val folder = context.config.saveRecordingsFolder
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var name = fileName
        var i = 2
        while (context.getDoesFilePathExistSdk30("$folder/$name")) name = "$base (${i++})$ext"
        val path = "$folder/$name"
        val uri = context.createDocumentUriUsingFirstParentTreeUri(path)
        check(context.createSAFFileSdk30(path)) { context.getString(R.string.te_error_folder_access) }
        (context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot write $name")).use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime), null)
        return uri to name
    }

    /** Writing the clipboard is allowed from the background (only reading is not). */
    fun copyToClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val clip = ClipData.newPlainText(context.getString(R.string.te_transcript), text)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
        }
    }

    private fun share(audio: File, mime: String, delivery: ActionPlan.Delivery) {
        val send = Intent(Intent.ACTION_SEND).apply {
            when (delivery) {
                is ActionPlan.Delivery.Text -> {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, delivery.text)
                }

                is ActionPlan.Delivery.Audio -> {
                    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", audio)
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(audio.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    delivery.caption?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }

                else -> return
            }
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.te_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.visible) {
            context.startActivity(chooser)
            return
        }
        // Android blocks activity starts from the background (and on the lock screen): wait behind a notification
        val pi = PendingIntent.getActivity(context, 7, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifications.notify(
            NOTIF_RESULT,
            NotificationCompat.Builder(context, COPIED_CHANNEL)
                .setSmallIcon(R.drawable.ic_graphic_eq_vector)
                .setContentTitle(context.getString(R.string.te_share_ready_title))
                .setContentText(context.getString(R.string.te_share_ready_body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun step(note: Note, step: NoteStep): Note {
        val n = note.copy(step = step)
        store.put(n)
        progress(stepTitle(n), n)
        return n
    }

    private fun stepTitle(note: Note): String = when (note.step) {
        NoteStep.SAVING -> context.getString(R.string.te_step_saving)
        NoteStep.TRANSCRIBING -> context.getString(R.string.te_step_transcribing)
        NoteStep.WAITING_FOR_TRANSCRIBER -> context.getString(R.string.te_step_waiting_transcriber)
        NoteStep.SENDING -> context.getString(R.string.te_step_sending, note.action.name)
        null -> ""
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Ongoing, silent: the title is the step; the body names the note and what waits behind it. */
    private fun progress(title: String, note: Note) {
        stage = title to note
        val length = note.durationMs / 1000
        var body = context.getString(R.string.te_step_body, note.action.name, length / 60, length % 60)
        val queued = waiting.get()
        if (queued > 0) body += " · " + context.resources.getQuantityString(R.plurals.te_step_queued, queued, queued)
        notifications.notify(
            NOTIF_PROGRESS,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_graphic_eq_vector)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    private fun simpleNotification(title: String, body: String) {
        notifications.notify(
            NOTIF_RESULT,
            NotificationCompat.Builder(context, COPIED_CHANNEL)
                .setSmallIcon(R.drawable.ic_graphic_eq_vector)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    private fun failureNotification(note: Note, why: String) {
        val body = context.getString(R.string.te_failed_body, why, note.savedName ?: note.fileName)
        val retry = PendingIntent.getService(
            context, note.id.hashCode(),
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_RETRY).putExtra(RecorderService.EXTRA_NOTE_ID, note.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.notify(
            NOTIF_RESULT,
            NotificationCompat.Builder(context, COPIED_CHANNEL)
                .setSmallIcon(R.drawable.ic_graphic_eq_vector)
                .setContentTitle(context.getString(R.string.te_failed_title, note.action.name))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .addAction(0, context.getString(R.string.te_retry), retry)
                .build(),
        )
    }
}
