package org.fossify.voicerecorder.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * Linear-interpolating resampler to mono PCM16, streaming (keeps its phase across blocks).
 * Good enough for speech recognition, which is all it is for.
 */
class MonoResampler(inRate: Int, private val channels: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var previous = 0
    private var hasPrevious = false

    /** [input] is interleaved PCM16 with [channels]; returns mono samples at the output rate. */
    fun process(input: ShortArray, count: Int): ShortArray {
        val frames = count / channels
        if (frames == 0) return ShortArray(0)
        // the previous block's last frame leads, so interpolation runs across the block edge
        val lead = if (hasPrevious) 1 else 0
        val arr = IntArray(frames + lead)
        if (hasPrevious) arr[0] = previous
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += input[f * channels + c]
            arr[f + lead] = sum / channels
        }
        val out = ShortArray(((arr.size - pos) / step).toInt() + 1)
        var n = 0
        while (pos + 1 < arr.size) {
            val i = pos.toInt()
            val frac = pos - i
            out[n++] = (arr[i] + (arr[i + 1] - arr[i]) * frac).toInt().toShort()
            pos += step
        }
        pos -= (arr.size - 1)
        previous = arr[arr.size - 1]
        hasPrevious = true
        return out.copyOf(n)
    }
}

/**
 * Decodes any audio file Android can read into 16 kHz mono PCM16 (little endian), streamed into
 * [out]: the input OSTT's caller-audio contract takes, for a transcription app without the file API.
 */
object PcmDecoder {
    const val RATE = 16_000

    fun decode(context: Context, uri: Uri, out: OutputStream) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var resampler: MonoResampler? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    resampler = MonoResampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT), RATE)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val r = resampler ?: MonoResampler(
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), RATE,
                        ).also { resampler = it }
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = ShortArray(info.size / 2)
                        buf.asShortBuffer().get(shorts)
                        val mono = r.process(shorts, shorts.size)
                        val bytes = ByteArray(mono.size * 2)
                        for (k in mono.indices) {
                            bytes[k * 2] = mono[k].toInt().toByte()
                            bytes[k * 2 + 1] = (mono[k].toInt() shr 8).toByte()
                        }
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }
}
