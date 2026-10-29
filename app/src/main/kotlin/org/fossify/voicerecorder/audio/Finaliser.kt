package org.fossify.voicerecorder.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** The container side of finalising, behind an interface so the journal walk is testable. */
interface Muxer {
    fun addTrack(format: JournalFormat): Int
    fun start()
    fun write(track: Int, data: ByteArray, ptsUs: Long, flags: Int)
    fun stop()
    fun release()
}

enum class Container(val extension: String, val mime: String) {
    MPEG_4("m4a", "audio/mp4"),
    OGG("ogg", "audio/ogg"),
    MP3("mp3", "audio/mpeg"),
}

/**
 * Turns a recording's working file into the playable file. One code path for a normal stop and
 * for a recording whose process died: the journal is read to its last complete frame and muxed.
 * MP3 needs no container (LAME's frames are the file).
 */
object Finaliser {

    class Result(val file: File, val durationMs: Long, val frames: Long)

    fun finalise(journal: File, out: File, container: Container, muxerFactory: (File, Container) -> Muxer = ::AndroidMuxer): Result {
        if (container == Container.MP3) {
            if (journal != out) journal.copyTo(out, overwrite = true)
            return Result(out, Mp3Duration.estimate(out), 0)
        }
        out.delete()
        var muxer: Muxer? = null
        var track = -1
        var started = false
        var lastPts = 0L
        var firstPts = -1L
        var sampleRate = 0
        val frames = FrameJournal.read(
            journal,
            onFormat = { format ->
                if (muxer == null) {
                    sampleRate = format.sampleRate
                    muxer = muxerFactory(out, container).also { m ->
                        track = m.addTrack(format)
                        m.start()
                        started = true
                    }
                }
            },
            onSample = { s ->
                val m = muxer ?: return@read
                if (s.data.isEmpty()) return@read
                // a crash can leave pts out of order only at the tail; the muxer rejects that
                if (s.ptsUs < lastPts) return@read
                if (firstPts < 0) firstPts = s.ptsUs
                m.write(track, s.data, s.ptsUs, s.flags)
                lastPts = s.ptsUs
            },
        )
        val m = muxer ?: error("the recording has no audio")
        try {
            if (started) m.stop()
        } finally {
            m.release()
        }
        // one AAC frame is 1024 samples; good enough for the list until the file is scanned
        val frameMs = if (sampleRate > 0) 1024_000L / sampleRate else 0
        return Result(out, (lastPts - maxOf(firstPts, 0)) / 1000 + frameMs, frames)
    }
}

/** MediaMuxer, MPEG-4 for AAC and Ogg for Opus. */
class AndroidMuxer(out: File, container: Container) : Muxer {
    private val muxer = MediaMuxer(
        out.path,
        when (container) {
            Container.OGG -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        },
    )
    private val info = MediaCodec.BufferInfo()

    override fun addTrack(format: JournalFormat): Int {
        val f = MediaFormat.createAudioFormat(format.mime, format.sampleRate, format.channels)
        if (format.bitrate > 0) f.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
        format.csd.forEachIndexed { i, bytes -> f.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
        return muxer.addTrack(f)
    }

    override fun start() = muxer.start()

    override fun write(track: Int, data: ByteArray, ptsUs: Long, flags: Int) {
        info.set(0, data.size, ptsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
    }

    override fun stop() = muxer.stop()

    override fun release() = muxer.release()
}

/** Duration of a constant-bitrate MP3 from its first frame header and the file size. */
object Mp3Duration {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    fun estimate(file: File): Long {
        val head = ByteArray(4096)
        val n = file.inputStream().use { it.read(head) }
        for (i in 0 until n - 3) {
            val b1 = head[i].toInt() and 0xFF
            val b2 = head[i + 1].toInt() and 0xFF
            if (b1 != 0xFF || (b2 and 0xE0) != 0xE0) continue
            val version = (b2 shr 3) and 0x3 // 3 = MPEG1
            val layer = (b2 shr 1) and 0x3 // 1 = layer III
            if (layer != 1) continue
            val index = ((head[i + 2].toInt() and 0xFF) shr 4) and 0xF
            if (index == 0 || index == 15) continue
            val kbps = if (version == 3) BITRATES_V1_L3[index] else BITRATES_V2_L3[index]
            return file.length() * 8 / kbps
        }
        return 0
    }
}
