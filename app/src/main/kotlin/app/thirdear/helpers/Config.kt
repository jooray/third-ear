package app.thirdear.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.core.content.edit
import org.fossify.commons.helpers.BaseConfig
import app.thirdear.R
import app.thirdear.extensions.getDefaultRecordingsFolder
import app.thirdear.pipeline.Cues

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var saveRecordingsFolder: String
        get() = prefs.getString(SAVE_RECORDINGS, context.getDefaultRecordingsFolder())!!
        set(saveRecordingsFolder) = prefs.edit().putString(SAVE_RECORDINGS, saveRecordingsFolder)
            .apply()

    var extension: Int
        get() = prefs.getInt(EXTENSION, EXTENSION_M4A)
        set(extension) = prefs.edit().putInt(EXTENSION, extension).apply()

    var microphoneMode: Int
        get() = prefs.getInt(MICROPHONE_MODE, MediaRecorder.AudioSource.DEFAULT)
        set(audioSource) = prefs.edit { putInt(MICROPHONE_MODE, audioSource) }

    fun getMicrophoneModeText(mode: Int) = context.getString(
        when (mode) {
            MediaRecorder.AudioSource.CAMCORDER -> R.string.microphone_mode_camcorder
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> R.string.microphone_mode_voice_communication
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> R.string.microphone_mode_voice_performance
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> R.string.microphone_mode_voice_recognition
            MediaRecorder.AudioSource.UNPROCESSED -> R.string.microphone_mode_unprocessed
            else -> org.fossify.commons.R.string.system_default
        }
    )

    var bitrate: Int
        get() = prefs.getInt(BITRATE, DEFAULT_BITRATE)
        set(bitrate) = prefs.edit().putInt(BITRATE, bitrate).apply()

    var samplingRate: Int
        get() = prefs.getInt(SAMPLING_RATE, DEFAULT_SAMPLING_RATE)
        set(samplingRate) = prefs.edit().putInt(SAMPLING_RATE, samplingRate).apply()

    var recordAfterLaunch: Boolean
        get() = prefs.getBoolean(RECORD_AFTER_LAUNCH, false)
        set(recordAfterLaunch) = prefs.edit().putBoolean(RECORD_AFTER_LAUNCH, recordAfterLaunch)
            .apply()

    fun getExtensionText() = context.getString(
        when (extension) {
            EXTENSION_M4A -> R.string.m4a
            EXTENSION_OGG -> R.string.ogg_opus
            else -> R.string.mp3_experimental
        }
    )

    fun getExtension() = context.getString(
        when (extension) {
            EXTENSION_M4A -> R.string.m4a
            EXTENSION_OGG -> R.string.ogg
            else -> R.string.mp3
        }
    )

    @SuppressLint("InlinedApi")
    fun getOutputFormat() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.OutputFormat.OGG
        else -> MediaRecorder.OutputFormat.MPEG_4
    }

    @SuppressLint("InlinedApi")
    fun getAudioEncoder() = when (extension) {
        EXTENSION_OGG -> MediaRecorder.AudioEncoder.OPUS
        else -> MediaRecorder.AudioEncoder.AAC
    }

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, true)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEEP_SCREEN_ON, true)
        set(keepScreenOn) = prefs.edit().putBoolean(KEEP_SCREEN_ON, keepScreenOn).apply()

    var wasMicModeWarningShown: Boolean
        get() = prefs.getBoolean(WAS_MIC_MODE_WARNING_SHOWN, false)
        set(wasMicModeWarningShown) = prefs.edit {
            putBoolean(WAS_MIC_MODE_WARNING_SHOWN, wasMicModeWarningShown)
        }

    var filenamePattern: String
        get() = prefs.getString(FILENAME_PATTERN, DEFAULT_FILENAME_PATTERN)!!
        set(filenamePattern) = prefs.edit { putString(FILENAME_PATTERN, filenamePattern) }

    // Third Ear

    /** Record from a connected Bluetooth microphone (headset, hearing aids, car) when there is one. */
    var preferBluetooth: Boolean
        get() = prefs.getBoolean(PREFER_BLUETOOTH, false)
        set(v) = prefs.edit { putBoolean(PREFER_BLUETOOTH, v) }

    /** When the microphone really records (a Bluetooth link takes seconds): a tone or "Go ahead". */
    var listeningCue: Cues.Mode
        get() = cueMode(LISTENING_CUE)
        set(v) = prefs.edit { putString(LISTENING_CUE, v.name) }

    /** After the stop and through delivery: what happens next, then delivered or failed. */
    var progressCue: Cues.Mode
        get() = cueMode(PROGRESS_CUE)
        set(v) = prefs.edit { putString(PROGRESS_CUE, v.name) }

    /** The npub whose nsec was last shown (to back it up); the reminder stays until it matches the identity in use. */
    var nsecShownFor: String?
        get() = prefs.getString(NSEC_SHOWN_FOR, null)
        set(v) = prefs.edit { putString(NSEC_SHOWN_FOR, v) }

    /** How fast voice messages play, unless their group says otherwise. */
    var playbackSpeed: Float
        get() = prefs.getFloat(PLAYBACK_SPEED, 1f)
        set(v) = prefs.edit { putFloat(PLAYBACK_SPEED, v) }

    /** What the record key in a Messages conversation sends: the transcript (true) or the audio. */
    var chatSendsTranscript: Boolean
        get() = prefs.getBoolean(CHAT_SENDS_TRANSCRIPT, false)
        set(v) = prefs.edit { putBoolean(CHAT_SENDS_TRANSCRIPT, v) }

    /** Before the two settings there was one "Spoken cues" switch; turned off, it turned both off. */
    private fun cueMode(key: String): Cues.Mode =
        prefs.getString(key, null)?.let { runCatching { Cues.Mode.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean(SPOKEN_CUES, true)) Cues.Mode.VOICE else Cues.Mode.OFF

    /** Pause other apps' playback while recording (needs notification access). */
    var pauseMedia: Boolean
        get() = prefs.getBoolean(PAUSE_MEDIA, false)
        set(v) = prefs.edit { putBoolean(PAUSE_MEDIA, v) }

    /** Flattened ComponentName of the recognition service; null picks OSTT or the first one. */
    var transcriber: String?
        get() = prefs.getString(TRANSCRIBER, null)
        set(v) = prefs.edit { putString(TRANSCRIBER, v) }

    /** Also save each transcript as a .txt next to its recording. */
    var saveTranscriptFiles: Boolean
        get() = prefs.getBoolean(SAVE_TRANSCRIPT_FILES, true)
        set(v) = prefs.edit { putBoolean(SAVE_TRANSCRIPT_FILES, v) }

    /** White Noise (own identity through MarmotKit) is set up and should run. */
    var whiteNoiseEnabled: Boolean
        get() = prefs.getBoolean(WHITE_NOISE_ENABLED, false)
        set(v) = prefs.edit { putBoolean(WHITE_NOISE_ENABLED, v) }

    /** White Noise was on in a restored backup, but its identity (never backed up) is not here. */
    var whiteNoiseRestored: Boolean
        get() = prefs.getBoolean(WHITE_NOISE_RESTORED, false)
        set(v) = prefs.edit { putBoolean(WHITE_NOISE_RESTORED, v) }
}
