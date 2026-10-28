package org.fossify.voicerecorder.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFFileSdk30
import org.fossify.commons.extensions.getDocumentFile
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getLaunchIntent
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isPathOnSD
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.voicerecorder.BuildConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SplashActivity
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.getFormattedFilename
import org.fossify.voicerecorder.extensions.updateWidgets
import org.fossify.voicerecorder.helpers.CANCEL_RECORDING
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.GET_RECORDER_INFO
import org.fossify.voicerecorder.helpers.RECORDER_RUNNING_NOTIF_ID
import org.fossify.voicerecorder.helpers.RECORDING_PAUSED
import org.fossify.voicerecorder.helpers.RECORDING_RUNNING
import org.fossify.voicerecorder.helpers.RECORDING_STOPPED
import org.fossify.voicerecorder.helpers.STOP_AMPLITUDE_UPDATE
import org.fossify.voicerecorder.helpers.STOP_RECORDING
import org.fossify.voicerecorder.helpers.TOGGLE_PAUSE
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.recorder.MediaRecorderWrapper
import org.fossify.voicerecorder.recorder.Mp3Recorder
import org.fossify.voicerecorder.recorder.Recorder
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Timer
import java.util.TimerTask

class RecorderService : Service() {
    companion object {
        var isRunning = false

        private const val AMPLITUDE_UPDATE_MS = 75L
        private const val WATCHDOG_INTERVAL_MS = 1000L

        // How long the input may deliver nothing but digital silence before the user is warned.
        // A muted system microphone, another app holding the input and a dead route all look
        // exactly like this, and none of them raise an error from MediaRecorder.
        private const val SILENT_SECONDS_FOR_WARNING = 8

        // Hard cap so a leaked lock cannot drain the battery if the service dies badly.
        private const val WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L
        private const val WAKE_LOCK_TAG = "voicerecorder:recording"

        private const val WARNING_CHANNEL_ID = "recorder_warning"
        private const val WARNING_NOTIF_ID = 2
        private const val TAG = "RecorderService"
    }


    private var recordingPath = ""
    private var resultUri: Uri? = null

    private var duration = 0
    private var status = RECORDING_STOPPED
    private var durationTimer = Timer()
    private var amplitudeTimer = Timer()
    private var watchdogTimer = Timer()
    private var recorder: Recorder? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var silentSeconds = 0
    private var silenceWarningShown = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent.action) {
            GET_RECORDER_INFO -> broadcastRecorderInfo()
            STOP_AMPLITUDE_UPDATE -> amplitudeTimer.cancel()
            TOGGLE_PAUSE -> togglePause()
            CANCEL_RECORDING -> cancelRecording()
            STOP_RECORDING -> {
                stopRecording()
                stopSelf()
            }

            else -> startRecording()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        isRunning = false
        updateWidgets(false)
    }

    // mp4 output format with aac encoding should produce good enough m4a files according to https://stackoverflow.com/a/33054794/1967672
    @SuppressLint("DiscouragedApi")
    private fun startRecording() {
        isRunning = true
        updateWidgets(true)
        if (status == RECORDING_RUNNING) {
            return
        }

        val defaultFolder = File(config.saveRecordingsFolder)
        if (!defaultFolder.exists()) {
            defaultFolder.mkdir()
        }

        val recordingFolder = defaultFolder.absolutePath
        recordingPath = "$recordingFolder/${getFormattedFilename()}.${config.getExtension()}"
        resultUri = null

        try {
            acquireWakeLock()

            recorder = if (recordMp3()) {
                Mp3Recorder(this)
            } else {
                MediaRecorderWrapper(this) { what, extra -> onRecorderError(what, extra) }
            }

            if (isRPlus()) {
                val fileUri = createDocumentUriUsingFirstParentTreeUri(recordingPath)
                createSAFFileSdk30(recordingPath)
                resultUri = fileUri
                contentResolver.openFileDescriptor(fileUri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else if (isPathOnSD(recordingPath)) {
                var document = getDocumentFile(recordingPath.getParentPath())
                document = document?.createFile("", recordingPath.getFilenameFromPath())
                check(document != null) { "Failed to create document on SD Card" }
                resultUri = document.uri
                contentResolver.openFileDescriptor(document.uri, "w")!!
                    .use { recorder?.setOutputFile(it) }
            } else {
                recorder?.setOutputFile(recordingPath)
                resultUri = FileProvider.getUriForFile(
                    this, "${BuildConfig.APPLICATION_ID}.provider", File(recordingPath)
                )
            }

            // Show the notification before anything can fail: on Android 12+ a microphone
            // foreground service started while the app is in the background must post it
            // immediately, and a recorder that runs without one is invisible to the user.
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())

            recorder?.prepare()
            recorder?.start()
            duration = 0
            status = RECORDING_RUNNING
            broadcastRecorderInfo()

            durationTimer = Timer()
            durationTimer.scheduleAtFixedRate(getDurationUpdateTask(), 1000, 1000)

            startAmplitudeUpdates()
            startWatchdog()
        } catch (e: Exception) {
            showErrorToast(e)
            releaseRecorderQuietly()
            cancelTimers()
            status = RECORDING_STOPPED
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        cancelTimers()
        status = RECORDING_STOPPED

        recorder?.apply {
            try {
                stop()
                release()
            } catch (
                @Suppress(
                    "TooGenericExceptionCaught",
                    "SwallowedException"
                ) e: RuntimeException
            ) {
                toast(R.string.recording_too_short)
            } catch (e: Exception) {
                showErrorToast(e)
                e.printStackTrace()
            }

            ensureBackgroundThread {
                scanRecording()
                EventBus.getDefault().post(Events.RecordingCompleted())
            }
        }
        recorder = null
        releaseWakeLock()
        resetSilenceWatch()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cancelRecording() {
        cancelTimers()
        status = RECORDING_STOPPED

        releaseRecorderQuietly()
        releaseWakeLock()
        resetSilenceWatch()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (isRPlus()) {
            val recordingUri = createDocumentUriUsingFirstParentTreeUri(recordingPath)
            DocumentsContract.deleteDocument(contentResolver, recordingUri)
        } else {
            File(recordingPath).delete()
        }

        EventBus.getDefault().post(Events.RecordingCompleted())
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun cancelTimers() {
        durationTimer.cancel()
        amplitudeTimer.cancel()
        watchdogTimer.cancel()
    }

    private fun releaseRecorderQuietly() {
        recorder?.apply {
            try {
                release()
            } catch (ignored: Exception) {
            }
        }
        recorder = null
    }

    /**
     * Watches for a recording that is running but receiving pure silence. MediaRecorder never
     * reports this as an error, so without a watchdog the user only finds out when they play
     * back an empty file.
     */
    private fun startWatchdog() {
        resetSilenceWatch()
        watchdogTimer = Timer()
        watchdogTimer.scheduleAtFixedRate(getWatchdogTask(), WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS)
    }

    private fun resetSilenceWatch() {
        silentSeconds = 0
        silenceWarningShown = false
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(WARNING_NOTIF_ID)
        }
    }

    private fun getWatchdogTask() = object : TimerTask() {
        override fun run() {
            // A paused recording is legitimately silent.
            if (status != RECORDING_RUNNING) return

            val amplitude = try {
                recorder?.getMaxAmplitude() ?: 0
            } catch (ignored: Exception) {
                0
            }

            if (amplitude > 0) {
                silentSeconds = 0
                return
            }

            silentSeconds++
            if (silentSeconds >= SILENT_SECONDS_FOR_WARNING && !silenceWarningShown) {
                silenceWarningShown = true
                showSilenceWarning()
            }
        }
    }

    private fun onRecorderError(what: Int, extra: Int) {
        Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
        mainHandler.post {
            if (status == RECORDING_STOPPED) return@post
            toast(R.string.recording_failed)
            stopRecording()
            stopSelf()
        }
    }

    private fun showSilenceWarning() {
        runCatching {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            NotificationChannel(
                WARNING_CHANNEL_ID,
                getString(R.string.microphone_silent_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(null, null)
                notificationManager.createNotificationChannel(this)
            }

            val notification = NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setContentTitle(getString(R.string.microphone_silent_title))
                .setContentText(getString(R.string.microphone_silent_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.microphone_silent_text)))
                .setSmallIcon(R.drawable.ic_graphic_eq_vector)
                .setContentIntent(getOpenAppIntent())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(WARNING_NOTIF_ID, notification)
        }
    }

    private fun broadcastRecorderInfo() {
        broadcastDuration()
        broadcastStatus()
        startAmplitudeUpdates()
    }

    @SuppressLint("DiscouragedApi")
    private fun startAmplitudeUpdates() {
        amplitudeTimer.cancel()
        amplitudeTimer = Timer()
        amplitudeTimer.scheduleAtFixedRate(getAmplitudeUpdateTask(), 0, AMPLITUDE_UPDATE_MS)
    }

    @SuppressLint("NewApi")
    private fun togglePause() {
        try {
            if (status == RECORDING_RUNNING) {
                recorder?.pause()
                status = RECORDING_PAUSED
            } else if (status == RECORDING_PAUSED) {
                recorder?.resume()
                status = RECORDING_RUNNING
            }
            broadcastStatus()
            startForeground(RECORDER_RUNNING_NOTIF_ID, showNotification())
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun scanRecording() {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(recordingPath),
            arrayOf(recordingPath.getMimeType())
        ) { _, uri ->
            if (uri == null) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@scanFile
            }

            recordingSavedSuccessfully(resultUri ?: uri)
        }
    }

    private fun recordingSavedSuccessfully(savedUri: Uri) {
        toast(R.string.recording_saved_successfully)
        EventBus.getDefault().post(Events.RecordingSaved(savedUri))
    }

    private fun getDurationUpdateTask() = object : TimerTask() {
        override fun run() {
            if (status == RECORDING_RUNNING) {
                duration++
                broadcastDuration()
            }
        }
    }

    private fun getAmplitudeUpdateTask() = object : TimerTask() {
        override fun run() {
            if (recorder != null) {
                try {
                    EventBus.getDefault()
                        .post(Events.RecordingAmplitude(recorder!!.getMaxAmplitude()))
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun showNotification(): Notification {
        val channelId = "simple_recorder"
        val label = getString(R.string.app_name)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        NotificationChannel(channelId, label, NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }

        val icon = R.drawable.ic_graphic_eq_vector
        val title = label
        val visibility = NotificationCompat.VISIBILITY_PUBLIC
        var text = getString(R.string.recording)
        if (status == RECORDING_PAUSED) {
            text += " (${getString(R.string.paused)})"
        }
        val pauseLabelId = if (status == RECORDING_PAUSED) R.string.resume_recording else R.string.pause_recording

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(getOpenAppIntent())
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(visibility)
            .setSound(null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(0, getString(pauseLabelId), getServiceActionIntent(TOGGLE_PAUSE, 1))
            .addAction(0, getString(R.string.stop_recording), getServiceActionIntent(STOP_RECORDING, 2))

        return builder.build()
    }

    private fun getServiceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = getLaunchIntent() ?: Intent(this, SplashActivity::class.java)
        return PendingIntent.getActivity(
            this,
            RECORDER_RUNNING_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcastDuration() {
        EventBus.getDefault().post(Events.RecordingDuration(duration))
    }

    private fun broadcastStatus() {
        EventBus.getDefault().post(Events.RecordingStatus(status))
    }

    private fun recordMp3(): Boolean {
        return config.extension == EXTENSION_MP3
    }
}
