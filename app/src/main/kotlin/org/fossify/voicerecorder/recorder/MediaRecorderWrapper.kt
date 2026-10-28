package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.fossify.voicerecorder.extensions.config

class MediaRecorderWrapper(
    val context: Context,
    private val onError: ((what: Int, extra: Int) -> Unit)? = null,
) : Recorder {

    @Suppress("DEPRECATION")
    private var recorder = MediaRecorder().apply {
        setAudioSource(context.config.microphoneMode)
        setOutputFormat(context.config.getOutputFormat())
        setAudioEncoder(context.config.getAudioEncoder())
        setAudioEncodingBitRate(context.config.bitrate)
        setAudioSamplingRate(context.config.samplingRate)
    }

    private var duplicatedOutput: ParcelFileDescriptor? = null

    init {
        // Without these, a dead encoder is indistinguishable from a healthy recording:
        // the duration timer keeps ticking while nothing reaches the file.
        recorder.setOnErrorListener { _, what, extra ->
            onError?.invoke(what, extra)
            true
        }
    }

    override fun setOutputFile(path: String) {
        recorder.setOutputFile(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        val pFD = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
        duplicatedOutput?.close()
        duplicatedOutput = pFD
        recorder.setOutputFile(pFD.fileDescriptor)
    }

    override fun prepare() {
        recorder.prepare()
    }

    override fun start() {
        recorder.start()
    }

    override fun stop() {
        recorder.stop()
    }

    @SuppressLint("NewApi")
    override fun pause() {
        recorder.pause()
    }

    @SuppressLint("NewApi")
    override fun resume() {
        recorder.resume()
    }

    override fun release() {
        recorder.release()
        duplicatedOutput?.close()
        duplicatedOutput = null
    }

    override fun getMaxAmplitude(): Int {
        return recorder.maxAmplitude
    }
}
