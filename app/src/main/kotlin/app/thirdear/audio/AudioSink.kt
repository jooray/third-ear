package app.thirdear.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream

/** What a recording is encoded as; fixed for the whole recording (a source switch keeps it). */
data class EncoderSettings(val container: Container, val sampleRate: Int, val bitrate: Int)

/**
 * Takes PCM from the engine thread and writes the recording's crash-safe working file. All calls
 * come from the engine thread.
 */
interface AudioSink {
    /** Samples consumed so far, which is the recording's length (pause drops samples, not time). */
    val samplesWritten: Long

    fun write(pcm: ShortArray, count: Int)

    /** Drains the encoder and closes the working file; the [Finaliser] then makes the playable file. */
    fun finish()

    /** Releases everything without draining (a failed start). */
    fun abort()
}

fun interface AudioSinkFactory {
    fun create(settings: EncoderSettings, workingFile: File): AudioSink
}

object DefaultSinks : AudioSinkFactory {
    override fun create(settings: EncoderSettings, workingFile: File): AudioSink = when (settings.container) {
        Container.MP3 -> Mp3Sink(settings, workingFile)
        Container.MPEG_4 -> MediaCodecSink(MediaFormat.MIMETYPE_AUDIO_AAC, settings, workingFile)
        Container.OGG -> MediaCodecSink(MediaFormat.MIMETYPE_AUDIO_OPUS, settings, workingFile)
    }
}

/** AAC-LC or Opus through MediaCodec, frames into a [FrameJournal]. */
class MediaCodecSink(private val mime: String, private val settings: EncoderSettings, file: File) : AudioSink {
    private val codec: MediaCodec
    private val journal: FrameJournal.Writer
    private val info = MediaCodec.BufferInfo()
    private var formatWritten = false
    private var chunk = ByteArray(0)

    override var samplesWritten = 0L
        private set

    init {
        val format = MediaFormat.createAudioFormat(mime, settings.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        codec = MediaCodec.createEncoderByType(mime)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (t: Throwable) {
            codec.release()
            throw t
        }
        journal = FrameJournal.Writer(file)
    }

    override fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val n = minOf(count - offset, buffer.remaining() / 2)
            if (chunk.size < n * 2) chunk = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = pcm[offset + i].toInt()
                chunk[i * 2] = v.toByte()
                chunk[i * 2 + 1] = (v shr 8).toByte()
            }
            buffer.put(chunk, 0, n * 2)
            codec.queueInputBuffer(index, 0, n * 2, samplesWritten * 1_000_000 / settings.sampleRate, 0)
            samplesWritten += n
            offset += n
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                // at the end, give the encoder up to 2 s to hand out its last frames
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++waits > 200) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writeFormat(codec.outputFormat)
                o >= 0 -> {
                    val buffer = codec.getOutputBuffer(o)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        journal.sample(bytes, 0, bytes.size, info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writeFormat(f: MediaFormat) {
        if (formatWritten) return
        formatWritten = true
        val csd = (0..2).mapNotNull { i ->
            if (!f.containsKey("csd-$i")) return@mapNotNull null
            val b = f.getByteBuffer("csd-$i")!!.duplicate()
            ByteArray(b.remaining()).also { b.get(it) }
        }
        journal.format(
            JournalFormat(
                mime = f.getString(MediaFormat.KEY_MIME) ?: mime,
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                bitrate = settings.bitrate,
                csd = csd,
            ),
        )
    }

    override fun finish() {
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, samplesWritten * 1_000_000 / settings.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            journal.close()
        }
    }

    override fun abort() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { journal.close() }
    }
}

/** MP3 through LAME. MP3 frames stand alone, so the working file is the recording itself. */
class Mp3Sink(private val settings: EncoderSettings, file: File) : AudioSink {
    private val out = FileOutputStream(file)
    private val lame: AndroidLame = LameBuilder()
        .setInSampleRate(settings.sampleRate)
        .setOutSampleRate(settings.sampleRate)
        .setOutBitrate(settings.bitrate / 1000)
        .setOutChannels(1)
        .build()
    private var mp3 = ByteArray(0)

    override var samplesWritten = 0L
        private set

    override fun write(pcm: ShortArray, count: Int) {
        val need = (7200 + count * 1.25).toInt()
        if (mp3.size < need) mp3 = ByteArray(need)
        val n = lame.encode(pcm, pcm, count, mp3)
        if (n > 0) out.write(mp3, 0, n)
        samplesWritten += count
    }

    override fun finish() {
        try {
            if (mp3.size < 7200) mp3 = ByteArray(7200)
            val n = lame.flush(mp3)
            if (n > 0) out.write(mp3, 0, n)
        } finally {
            lame.close()
            runCatching { out.fd.sync() }
            out.close()
        }
    }

    override fun abort() {
        runCatching { lame.close() }
        runCatching { out.close() }
    }
}
