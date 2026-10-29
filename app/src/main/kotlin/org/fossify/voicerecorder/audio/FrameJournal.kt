package org.fossify.voicerecorder.audio

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** The encoder's output format, enough to rebuild a MediaFormat for the muxer. */
data class JournalFormat(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    /** Codec-specific data: csd-0, csd-1, … (AAC: AudioSpecificConfig; Opus: OpusHead, pre-skip, seek pre-roll). */
    val csd: List<ByteArray>,
) {
    fun toJson(): String = JSONObject()
        .put("mime", mime)
        .put("rate", sampleRate)
        .put("channels", channels)
        .put("bitrate", bitrate)
        .put("csd", JSONArray(csd.map { Base64.getEncoder().encodeToString(it) }))
        .toString()

    override fun equals(other: Any?): Boolean = other is JournalFormat && other.mime == mime &&
        other.sampleRate == sampleRate && other.channels == channels && other.bitrate == bitrate &&
        other.csd.size == csd.size && other.csd.indices.all { other.csd[it].contentEquals(csd[it]) }

    override fun hashCode(): Int = mime.hashCode() * 31 + sampleRate

    companion object {
        fun fromJson(s: String): JournalFormat {
            val o = JSONObject(s)
            val csd = o.optJSONArray("csd") ?: JSONArray()
            return JournalFormat(
                mime = o.getString("mime"),
                sampleRate = o.getInt("rate"),
                channels = o.getInt("channels"),
                bitrate = o.optInt("bitrate"),
                csd = (0 until csd.length()).map { Base64.getDecoder().decode(csd.getString(it)) },
            )
        }
    }
}

class JournalSample(val ptsUs: Long, val flags: Int, val data: ByteArray)

/**
 * Append-only file of encoded audio frames, written while recording. A container (MP4, Ogg) is
 * only valid once it is closed, so a killed process would leave an unplayable file; this journal
 * stays readable up to its last complete record, and the same [Finaliser] that writes the
 * container at a normal stop recovers it after a crash.
 *
 * Layout: magic `TEJ1`, then records `[type:1][length:4][payload]`; type `F` = format (JSON),
 * type `S` = sample (`pts:8 flags:4 bytes`).
 */
object FrameJournal {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'E'.code.toByte(), 'J'.code.toByte(), '1'.code.toByte())
    private const val TYPE_FORMAT = 'F'.code
    private const val TYPE_SAMPLE = 'S'.code
    private const val FLUSH_INTERVAL_MS = 1_000L

    class Writer(file: File, private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
        private val fos = FileOutputStream(file, false)
        private val out = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
        private var lastFlush = clock()
        var samples = 0L
            private set
        var lastPtsUs = 0L
            private set

        init {
            out.write(MAGIC)
            out.flush()
        }

        fun format(format: JournalFormat) {
            val bytes = format.toJson().toByteArray(Charsets.UTF_8)
            out.writeByte(TYPE_FORMAT)
            out.writeInt(bytes.size)
            out.write(bytes)
            flush()
        }

        fun sample(data: ByteArray, offset: Int, length: Int, ptsUs: Long, flags: Int) {
            out.writeByte(TYPE_SAMPLE)
            out.writeInt(length + 12)
            out.writeLong(ptsUs)
            out.writeInt(flags)
            out.write(data, offset, length)
            samples++
            lastPtsUs = ptsUs
            // the page cache survives a killed process: handing data to the OS every second is
            // what bounds the loss; fsync only matters for power loss and costs battery
            if (clock() - lastFlush >= FLUSH_INTERVAL_MS) flush()
        }

        fun flush() {
            out.flush()
            lastFlush = clock()
        }

        override fun close() {
            runCatching { out.flush() }
            runCatching { fos.fd.sync() }
            out.close()
        }
    }

    /**
     * Reads [file] up to its last complete record. [onSample] gets every sample in order; returns
     * the format (null when the encoder never produced one) and the sample count.
     */
    fun read(file: File, onFormat: (JournalFormat) -> Unit, onSample: (JournalSample) -> Unit): Long {
        var count = 0L
        DataInputStream(file.inputStream().buffered(64 * 1024)).use { input ->
            val magic = ByteArray(4)
            try {
                input.readFully(magic)
            } catch (_: EOFException) {
                return 0
            }
            require(magic.contentEquals(MAGIC)) { "not a Third Ear journal" }
            while (true) {
                val type = input.read()
                if (type < 0) break
                val length = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (length < 0 || length > 16 * 1024 * 1024) break
                val payload = ByteArray(length)
                try {
                    input.readFully(payload)
                } catch (_: EOFException) {
                    break // the record being written when the process died
                }
                when (type) {
                    TYPE_FORMAT -> onFormat(JournalFormat.fromJson(String(payload, Charsets.UTF_8)))
                    TYPE_SAMPLE -> {
                        if (length < 12) break
                        val pts = java.nio.ByteBuffer.wrap(payload, 0, 8).long
                        val flags = java.nio.ByteBuffer.wrap(payload, 8, 4).int
                        onSample(JournalSample(pts, flags, payload.copyOfRange(12, length)))
                        count++
                    }

                    else -> break
                }
            }
        }
        return count
    }

    fun isJournal(file: File): Boolean = runCatching {
        file.inputStream().use { val m = ByteArray(4); it.read(m) == 4 && m.contentEquals(MAGIC) }
    }.getOrDefault(false)
}
