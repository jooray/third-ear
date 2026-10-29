package org.fossify.voicerecorder.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import androidx.core.content.edit
import org.fossify.commons.helpers.BaseConfig
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.extensions.getDefaultRecordingsFolder

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

    /** "Go ahead" when a Bluetooth microphone is ready, and what happens after the stop. */
    var spokenCues: Boolean
        get() = prefs.getBoolean(SPOKEN_CUES, true)
        set(v) = prefs.edit { putBoolean(SPOKEN_CUES, v) }

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
}
