package app.thirdear.services

import androidx.compose.ui.graphics.toArgb
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.toast
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.ActionStore
import app.thirdear.actions.RecordAction
import app.thirdear.activities.SplashActivity
import app.thirdear.audio.AndroidSources
import app.thirdear.audio.BluetoothMics
import app.thirdear.audio.Container
import app.thirdear.audio.DefaultSinks
import app.thirdear.audio.EncoderSettings
import app.thirdear.audio.EngineStatus
import app.thirdear.audio.RecordingEngine
import app.thirdear.audio.Source
import app.thirdear.audio.SourcePolicy
import app.thirdear.extensions.config
import app.thirdear.extensions.defaultActions
import app.thirdear.extensions.getFormattedFilename
import app.thirdear.extensions.inputLabel
import app.thirdear.extensions.updateWidgets
import app.thirdear.helpers.CANCEL_RECORDING
import app.thirdear.helpers.EXTENSION_MP3
import app.thirdear.helpers.EXTENSION_OGG
import app.thirdear.helpers.GET_RECORDER_INFO
import app.thirdear.helpers.RECORDER_RUNNING_NOTIF_ID
import app.thirdear.helpers.RECORDING_STOPPED
import app.thirdear.helpers.STOP_AMPLITUDE_UPDATE
import app.thirdear.helpers.STOP_RECORDING
import app.thirdear.helpers.TOGGLE_PAUSE
import app.thirdear.models.Events
import app.thirdear.pipeline.Cues
import app.thirdear.pipeline.MediaPauser
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NotePipeline
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStore
import org.greenrobot.eventbus.EventBus
import app.thirdear.tiles.ActionTileService

/**
 * Records (through [RecordingEngine]) and then runs the [NotePipeline] for each finished
 * recording. One foreground service for both, typed microphone|dataSync: started while the app
 * is visible it may keep the microphone for the transcription that follows (the recognition
 * service checks our microphone access), and the pipeline keeps the process alive.
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "ThirdEarRecorder"
        private const val P = "app.thirdear.action."
        const val ACTION_START = P + "START"
        const val ACTION_STOP = P + "STOP"
        const val ACTION_DISCARD = P + "DISCARD"
        const val ACTION_SWITCH_SOURCE = P + "SWITCH_SOURCE"
        const val ACTION_RETRY = P + "RETRY"
        const val ACTION_RECOVER = P + "RECOVER"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_ACTION_JSON = "action_json"
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_SOURCE = "source"

        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val WARNING_CHANNEL_ID = "recorder_warning"
        private const val WARNING_NOTIF_ID = 2
        private const val RECORDING_CHANNEL = "simple_recorder"
        /** After the stop confirmation, before paused players resume: a headset leaves call mode first. */
        private const val RESUME_DELAY_MS = 1_500L

        /** Fossify's flag: a recording is running (or starting). */
        @Volatile
        var isRunning = false

        /** The recording in progress, for the recorder screen, tiles, widget and car screen. */
        data class Live(val noteId: String, val action: RecordAction, val startedAt: Long, val status: EngineStatus)

        private val liveState = MutableStateFlow<Live?>(null)
        val live: StateFlow<Live?> get() = liveState

        private val refusedState = MutableStateFlow(0L)

        /** When Android last refused to start the microphone (Third Ear not opened on the phone since boot or update). */
        val refusedAt: StateFlow<Long> get() = refusedState

        fun intent(context: Context, action: String): Intent = Intent(context, RecorderService::class.java).setAction(action)

        /** From a visible activity (Android lets only a visible app start a microphone service). */
        fun start(context: Context, actionId: String?) = launch(context, intent(context, ACTION_START).putExtra(EXTRA_ACTION_ID, actionId))

        /** Records for an action that is not a line (a White Noise conversation's record key); stop with a null id. */
        fun start(context: Context, action: RecordAction) =
            launch(context, intent(context, ACTION_START).putExtra(EXTRA_ACTION_JSON, action.toJson().toString()))

        /** Ends the recording and runs [actionId]'s action (null: the one it was started with). */
        fun stop(context: Context, actionId: String?) = control(context, intent(context, ACTION_STOP).putExtra(EXTRA_ACTION_ID, actionId))

        fun discard(context: Context) = control(context, intent(context, ACTION_DISCARD))

        fun togglePause(context: Context) = control(context, intent(context, TOGGLE_PAUSE))

        fun switchSource(context: Context, source: Source) =
            control(context, intent(context, ACTION_SWITCH_SOURCE).putExtra(EXTRA_SOURCE, source.name))

        /**
         * Commands for a recording in progress: the service already runs in the foreground, so a
         * plain start reaches it from anywhere (a tile, the car), where a foreground start from
         * the background could be refused.
         */
        private fun control(context: Context, intent: Intent): Boolean {
            if (!isRunning) return false
            return try {
                context.startService(intent)
                true
            } catch (ignored: Throwable) {
                // the process was cached and may not start a background service: go through the foreground path
                launch(context, intent)
            }
        }

        fun retry(context: Context, noteId: String) = launch(context, intent(context, ACTION_RETRY).putExtra(EXTRA_NOTE_ID, noteId))

        fun recover(context: Context) = launch(context, intent(context, ACTION_RECOVER))

        private fun launch(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not start the service: ${t.message}")
            false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: NoteStore
    private lateinit var actions: ActionStore
    private lateinit var pipeline: NotePipeline
    private var engine: RecordingEngine? = null
    private var stopAction: RecordAction? = null
    private var discarding = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pausedPlayers: List<String> = emptyList()
    private var jobs = 0
    private var lastNotified: EngineStatus? = null
    private var silenceShown = false
    @Volatile private var goAheadPlayed = false
    private var foregroundId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = NoteStore(this)
        actions = ActionStore(this)
        pipeline = NotePipeline(this, store)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, STOP_RECORDING -> stopRecording(intent.getStringExtra(EXTRA_ACTION_ID))
            ACTION_DISCARD, CANCEL_RECORDING -> discardRecording()
            TOGGLE_PAUSE -> togglePause()
            ACTION_SWITCH_SOURCE -> intent.getStringExtra(EXTRA_SOURCE)
                ?.let { s -> Source.entries.firstOrNull { it.name == s } }
                ?.let { engine?.switchTo(it) }

            ACTION_RETRY -> intent.getStringExtra(EXTRA_NOTE_ID)?.let { runPipeline(it, retry = true) }
            ACTION_RECOVER -> recover()
            GET_RECORDER_INFO, STOP_AMPLITUDE_UPDATE -> {}
            else -> startRecording(intent?.getStringExtra(EXTRA_ACTION_ID), intent?.getStringExtra(EXTRA_ACTION_JSON))
        }
        // a foreground-service start must reach startForeground, even when there is nothing to do
        if (foregroundId == 0) {
            goForeground(NotePipeline.NOTIF_PROGRESS, idleNotification(), microphone = false)
            maybeStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        releaseWakeLock()
        isRunning = false
        liveState.value = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ recording

    private fun startRecording(actionId: String?, actionJson: String? = null) {
        if (engine != null) return
        val all = actions.all { defaultActions() }
        val adHoc = actionJson?.let { runCatching { RecordAction.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        val action = adHoc ?: all.firstOrNull { it.id == actionId } ?: actions.defaultAction() ?: all.first()
        val container = when (config.extension) {
            EXTENSION_MP3 -> Container.MP3
            EXTENSION_OGG -> Container.OGG
            else -> Container.MPEG_4
        }
        val note = Note(
            id = UUID.randomUUID().toString().take(8) + "-" + System.currentTimeMillis(),
            startedAt = System.currentTimeMillis(),
            action = action,
            container = container,
            baseName = getFormattedFilename(),
        )
        val live = Live(note.id, action, note.startedAt, EngineStatus(sampleRate = config.samplingRate))
        if (!goForeground(RECORDER_RUNNING_NOTIF_ID, recordingNotification(live), microphone = true)) {
            // Android 14+: the microphone service starts only while Third Ear is visible
            toast(R.string.te_error_background_start)
            refusedState.value = System.currentTimeMillis()
            maybeStop()
            return
        }
        try {
            store.put(note)
            val sink = DefaultSinks.create(EncoderSettings(container, config.samplingRate, config.bitrate), store.work(note))
            stopAction = null
            discarding = false
            silenceShown = false
            goAheadPlayed = false
            lastNotified = null
            isRunning = true
            liveState.value = live
            acquireWakeLock()
            if (config.pauseMedia) pausedPlayers = (pausedPlayers + runCatching { MediaPauser.pausePlaying() }.getOrDefault(emptyList())).distinct()
            val e = RecordingEngine(
                sources = AndroidSources(this) { config.microphoneMode },
                sink = sink,
                sampleRate = config.samplingRate,
                preferBluetooth = config.preferBluetooth,
                bluetoothAvailable = { BluetoothMics.available(this) },
                listener = EngineListener(note.id),
            )
            engine = e
            e.start()
            updateWidgets(true)
            ActionTileService.refreshAll(this)
            Log.i(TAG, "recording ${note.id} with action ${action.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "could not start recording", t)
            toast(getString(R.string.te_error_start, t.message ?: t.javaClass.simpleName))
            store.delete(note.id)
            engine = null
            isRunning = false
            liveState.value = null
            releaseWakeLock()
            broadcastStopped()
            notifications().cancel(RECORDER_RUNNING_NOTIF_ID)
            maybeStop()
        }
    }

    private inner class EngineListener(private val noteId: String) : RecordingEngine.Listener {
        override fun onStatus(status: EngineStatus) {
            val l = liveState.value ?: return
            if (l.noteId != noteId) return
            liveState.value = l.copy(status = status)
            main.post { onStatusMain(status) }
        }

        override fun onCue(cue: SourcePolicy.Cue) {
            // always audible: in a headset or a car nobody watches the screen; it also says "speak now"
            goAheadPlayed = true
            Thread({ Cues.play(Cues.Tone.FALLBACK) }, "third-ear-cue").start()
        }

        override fun onLog(message: String) {
            Log.i(TAG, message)
        }

        override fun onFinished(samples: Long, failure: String?) {
            main.post { finished(noteId, samples, failure) }
        }
    }

    private fun onStatusMain(status: EngineStatus) {
        val l = liveState.value ?: return
        val prev = lastNotified
        if (prev == null || prev.paused != status.paused || prev.source != status.source || prev.input != status.input ||
            prev.waitingForBluetooth != status.waitingForBluetooth || prev.switching != status.switching || prev.starting != status.starting
        ) {
            lastNotified = status
            notifications().notify(RECORDER_RUNNING_NOTIF_ID, recordingNotification(l))
        }
        if (!goAheadPlayed && !status.waitingForBluetooth && !status.switching && !status.starting) {
            // the microphone delivers real audio now (a Bluetooth link takes seconds): speak (it can be talked over)
            goAheadPlayed = true
            val mode = config.listeningCue
            val bluetooth = status.source == Source.BLUETOOTH && status.input.bluetooth
            Thread({ Cues.playListening(this, mode, bluetooth) }, "third-ear-cue").start()
        }
        val silent = status.silent && !status.paused
        if (silent && !silenceShown) {
            silenceShown = true
            showSilenceWarning()
        } else if (!silent && silenceShown) {
            silenceShown = false
            notifications().cancel(WARNING_NOTIF_ID)
        }
    }

    private fun stopRecording(actionId: String?) {
        val e = engine ?: return
        stopAction = actions.get(actionId)
        e.stop()
    }

    private fun discardRecording() {
        val e = engine ?: return
        discarding = true
        e.stop(discardAudio = true)
    }

    private fun togglePause() {
        val e = engine ?: return
        e.setPaused(!(liveState.value?.status?.paused ?: false))
    }

    private fun finished(noteId: String, samples: Long, failure: String?) {
        val l = liveState.value
        engine = null
        isRunning = false
        liveState.value = null
        releaseWakeLock()
        notifications().cancel(WARNING_NOTIF_ID)
        updateWidgets(false)
        ActionTileService.refreshAll(this)
        broadcastStopped()
        val toResume = pausedPlayers.also { pausedPlayers = emptyList() }
        val note = store.get(noteId)
        if (note == null || discarding) {
            store.delete(noteId)
            discarding = false
            resumeMedia(toResume)
            notifications().cancel(RECORDER_RUNNING_NOTIF_ID)
            maybeStop()
            return
        }
        val action = stopAction ?: note.action
        val status = l?.status
        val done = note.copy(
            action = action,
            durationMs = samples * 1000 / config.samplingRate,
            mic = status?.let { if (it.input.bluetooth) "bluetooth" + (it.input.name?.let { n -> ": $n" } ?: "") else "phone" },
            state = NoteState.PROCESSING,
        )
        store.put(done)
        Log.i(TAG, "stopped ${note.id} after ${done.durationMs / 1000} s -> ${action.name}${failure?.let { " (failed: $it)" } ?: ""}")
        if (failure != null) toast(getString(R.string.te_error_mic_failed, failure))
        // the progress notification takes over as the service's foreground notification
        goForeground(NotePipeline.NOTIF_PROGRESS, idleNotification(), microphone = true)
        notifications().cancel(RECORDER_RUNNING_NOTIF_ID)
        scope.launch {
            Cues.playStopped(this@RecorderService, config.progressCue, ActionPlan.of(action), failed = failure != null)
            resumeMediaNow(toResume)
        }
        runPipeline(done.id, retry = false)
    }

    private fun resumeMedia(list: List<String>) {
        if (list.isEmpty()) return
        scope.launch { resumeMediaNow(list) }
    }

    private suspend fun resumeMediaNow(list: List<String>) {
        if (list.isEmpty()) return
        delay(RESUME_DELAY_MS)
        main.post {
            // a new recording started meanwhile: its stop resumes these too
            if (engine != null) pausedPlayers = (pausedPlayers + list).distinct() else runCatching { MediaPauser.resume(list) }
        }
    }

    // ------------------------------------------------------------------ pipeline

    private fun runPipeline(noteId: String, retry: Boolean) {
        if (retry) {
            notifications().cancel(NotePipeline.NOTIF_RESULT)
            if (foregroundId == 0) goForeground(NotePipeline.NOTIF_PROGRESS, idleNotification(), microphone = true)
        }
        jobs++
        scope.launch {
            try {
                pipeline.process(noteId)
            } finally {
                main.post {
                    jobs--
                    maybeStop()
                }
            }
        }
    }

    private fun recover() {
        jobs++
        scope.launch {
            try {
                pipeline.recover(liveState.value?.noteId)
            } finally {
                main.post {
                    jobs--
                    maybeStop()
                }
            }
        }
    }

    private fun maybeStop() {
        if (engine != null || jobs > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundId = 0
        stopSelf()
    }

    // ------------------------------------------------------------------ foreground, wake lock, notifications

    /**
     * Microphone|dataSync when allowed (started from a visible app), else dataSync only (a retry
     * from a notification: transcription may then be refused, the rest works). Returns whether
     * the service runs with what was asked for.
     */
    private fun goForeground(id: Int, notification: Notification, microphone: Boolean): Boolean {
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val types = if (microphone) listOf(mic, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        for (type in types) {
            try {
                startForeground(id, notification, type)
                foregroundId = id
                return !microphone || type == mic
            } catch (t: Throwable) {
                Log.w(TAG, "startForeground(type $type) refused: ${t.message}")
            }
        }
        return false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thirdear:recording").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun broadcastStopped() {
        EventBus.getDefault().post(Events.RecordingStatus(RECORDING_STOPPED))
        EventBus.getDefault().post(Events.RecordingDuration(0))
    }

    private fun notifications() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val nm = notifications()
        nm.createNotificationChannel(
            NotificationChannel(RECORDING_CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) },
        )
        nm.createNotificationChannel(
            NotificationChannel(WARNING_CHANNEL_ID, getString(R.string.microphone_silent_title), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null) },
        )
        nm.createNotificationChannel(
            NotificationChannel(NotePipeline.CHANNEL, getString(R.string.te_channel_pipeline), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, RECORDER_RUNNING_NOTIF_ID,
        Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(action: String, requestCode: Int, actionId: String? = null): PendingIntent = PendingIntent.getService(
        this, requestCode, intent(this, action).putExtra(EXTRA_ACTION_ID, actionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The line's cord colour, so the notification carries the same identity as its key. */
    private fun lineColor(l: Live): Int {
        val all = actions.all { defaultActions() }
        val i = all.indexOfFirst { it.id == l.action.id }.coerceAtLeast(0)
        return app.thirdear.ui.Cords.color(app.thirdear.ui.cordOf(l.action, i)).toArgb()
    }

    private fun recordingNotification(l: Live): Notification {
        val s = l.status
        var text = inputLabel(s)
        if (s.paused) text = getString(R.string.paused) + " · " + text
        val pauseLabel = if (s.paused) R.string.resume_recording else R.string.pause_recording
        return NotificationCompat.Builder(this, RECORDING_CHANNEL)
            .setContentTitle(getString(R.string.te_notif_recording, l.action.name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setColor(lineColor(l))
            .setSubText(app.thirdear.actions.LineCode.of(l.action))
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setUsesChronometer(!s.paused)
            .setWhen(System.currentTimeMillis() - s.durationMs)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, getString(pauseLabel), serviceAction(TOGGLE_PAUSE, 1))
            .addAction(0, getString(R.string.te_notif_stop_action, l.action.name), serviceAction(ACTION_STOP, 2, l.action.id))
            .build()
    }

    private fun idleNotification(): Notification = NotificationCompat.Builder(this, NotePipeline.CHANNEL)
        .setContentTitle(getString(R.string.te_step_saving))
        .setSmallIcon(R.drawable.ic_graphic_eq_vector)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .build()

    private fun showSilenceWarning() {
        val notification = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
            .setContentTitle(getString(R.string.microphone_silent_title))
            .setContentText(getString(R.string.microphone_silent_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.microphone_silent_text)))
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notifications().notify(WARNING_NOTIF_ID, notification)
        if (config.listeningCue != Cues.Mode.OFF) Thread({ Cues.play(Cues.Tone.WARN) }, "third-ear-cue").start()
    }
}
