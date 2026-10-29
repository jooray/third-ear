package app.thirdear.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "ThirdEarMic"

fun isBluetoothInput(d: AudioDeviceInfo?) =
    d?.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

/**
 * Bluetooth microphones on Android are call microphones: they exist only while the headset is
 * the communication device (SCO, or LE Audio's conversational context). Hearing aids, LE Audio
 * headsets, classic headsets and a car's hands-free microphone (HFP/SCO, also under Android Auto)
 * all come through here.
 */
object BluetoothMics {
    /** Communication devices with a microphone, best first: LE Audio, then classic SCO. */
    fun candidates(audio: AudioManager): List<AudioDeviceInfo> {
        val comm = audio.availableCommunicationDevices
        return (comm.filter { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } +
            comm.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }).distinctBy { it.id }
    }

    fun available(context: Context): Boolean =
        runCatching { candidates(context.getSystemService(AudioManager::class.java)).isNotEmpty() }.getOrDefault(false)

    fun name(context: Context): String? = runCatching {
        candidates(context.getSystemService(AudioManager::class.java)).firstOrNull()?.productName?.toString()
    }.getOrNull()
}

/** Opens the phone microphone or a Bluetooth microphone through the communication route. */
class AndroidSources(private val context: Context, private val phoneAudioSource: () -> Int) : PcmSourceFactory {

    override fun open(kind: Source, sampleRate: Int, events: (SourceEvent) -> Unit): PcmSource? = when (kind) {
        Source.PHONE -> AudioRecordSource(context, Source.PHONE, sampleRate, phoneSource(), null, null, events)
        Source.BLUETOOTH -> openBluetooth(sampleRate, events)
    }

    private fun phoneSource(): Int = phoneAudioSource().let {
        // VOICE_COMMUNICATION follows the communication device; on the phone it would only add
        // call processing, and it is what a Bluetooth route uses, so keep the phone on MIC then
        if (it == MediaRecorder.AudioSource.DEFAULT) MediaRecorder.AudioSource.MIC else it
    }

    private fun openBluetooth(sampleRate: Int, events: (SourceEvent) -> Unit): PcmSource? {
        val audio = context.getSystemService(AudioManager::class.java)
        val route = BluetoothRoute(context, audio)
        val device = route.acquire() ?: return null
        val input = route.inputFor(device)
        return try {
            AudioRecordSource(
                context, Source.BLUETOOTH, sampleRate, MediaRecorder.AudioSource.VOICE_COMMUNICATION, input, route, events,
                prime = device.type == AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        } catch (t: Throwable) {
            route.release()
            throw t
        }
    }
}

/** Makes a Bluetooth headset the communication device and waits until it is (plus a settle delay). */
class BluetoothRoute(private val context: Context, private val audio: AudioManager) {
    companion object {
        private const val ROUTE_TIMEOUT_MS = 3_000L
        /** The platform reports the device before the LE Audio link has reconfigured. */
        private const val ROUTE_SETTLE_MS = 500L
    }

    private var set = false
    var device: AudioDeviceInfo? = null
        private set

    /** Blocks up to ~3.5 s. Returns the device, or null when no Bluetooth microphone is offered. */
    fun acquire(): AudioDeviceInfo? {
        val t0 = SystemClock.elapsedRealtime()
        for (target in BluetoothMics.candidates(audio)) {
            if (audio.communicationDevice?.id == target.id) {
                device = target
                return target
            }
            val reached = CountDownLatch(1)
            val listener = AudioManager.OnCommunicationDeviceChangedListener { d -> if (d?.id == target.id) reached.countDown() }
            audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
            try {
                if (!runCatching { audio.setCommunicationDevice(target) }.getOrDefault(false)) continue
                set = true
                val confirmed = audio.communicationDevice?.id == target.id || reached.await(ROUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                Thread.sleep(ROUTE_SETTLE_MS)
                Log.i(TAG, "route to type ${target.type}: ${if (confirmed) "confirmed" else "timeout"} in ${SystemClock.elapsedRealtime() - t0} ms")
                device = target
                return target
            } finally {
                audio.removeOnCommunicationDeviceChangedListener(listener)
            }
        }
        return null
    }

    /** The input side of the communication device; a binaural set can expose another address. */
    fun inputFor(comm: AudioDeviceInfo): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.type == comm.type && it.address.equals(comm.address, ignoreCase = true) }
            ?: inputs.firstOrNull { it.type == comm.type }
    }

    /** Leaves call mode; forgetting this keeps the headset's audio flat for other apps. */
    fun release() {
        if (set) runCatching { audio.clearCommunicationDevice() }
        set = false
        device = null
    }
}

/** An AudioRecord at the recording's rate, with routing and silencing watched. */
@SuppressLint("MissingPermission")
class AudioRecordSource(
    private val context: Context,
    override val kind: Source,
    sampleRate: Int,
    audioSource: Int,
    preferred: AudioDeviceInfo?,
    private val route: BluetoothRoute?,
    private val events: (SourceEvent) -> Unit,
    prime: Boolean = false,
) : PcmSource {

    companion object {
        /**
         * Silence played on the call route while an LE Audio input comes up: Pixel's audio HAL
         * skips its "BLE restart" when an LE input starts with no LE output active, and the input
         * then delivers only digital zeros for the whole recording.
         */
        private const val PRIME_MS = 3_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val record: AudioRecord
    private var routing: AudioRouting.OnRoutingChangedListener? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    @Volatile private var open = true

    init {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // a second of headroom so a slow encoder or a busy CPU never drops audio
            .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRate * 2))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("microphone unavailable")
        }
        if (preferred != null) r.preferredDevice = preferred
        try {
            r.startRecording()
        } catch (t: Throwable) {
            r.release()
            throw t
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            error("microphone refused (another app has it, or no permission)")
        }
        record = r
        watch(r)
        if (prime) Thread(::primer, "third-ear-primer").start()
        Log.i(TAG, "opened ${kind.name.lowercase()} source $audioSource at $sampleRate Hz, routed type ${r.routedDevice?.type}")
    }

    override fun read(buffer: ShortArray, count: Int): Int = record.read(buffer, 0, count)

    override fun routed(): RoutedInput {
        val d = record.routedDevice
        return if (isBluetoothInput(d)) RoutedInput(true, d?.productName?.toString()) else RoutedInput.PHONE
    }

    private fun watch(r: AudioRecord) {
        var last: Int? = r.routedDevice?.id
        val l = AudioRouting.OnRoutingChangedListener { router ->
            val d = router.routedDevice
            if (d?.id != last) {
                last = d?.id
                Log.i(TAG, "input routed to type ${d?.type}")
                events(SourceEvent.Routed(if (isBluetoothInput(d)) RoutedInput(true, d?.productName?.toString()) else RoutedInput.PHONE))
            }
        }
        r.addOnRoutingChangedListener(l, main)
        routing = l
        var silenced = false
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                if (mine.isClientSilenced != silenced) {
                    silenced = mine.isClientSilenced
                    events(SourceEvent.Silenced(silenced))
                }
            }
        }
        r.registerAudioRecordingCallback(context.mainExecutor, cb)
        recordingCallback = cb
    }

    private fun primer() {
        val rate = 16_000
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(rate / 5)
                .build()
        }.getOrNull() ?: return
        try {
            track.play()
            val silence = ShortArray(rate / 50)
            val until = SystemClock.elapsedRealtime() + PRIME_MS
            // blocking writes pace the loop at real time
            while (open && SystemClock.elapsedRealtime() < until) {
                if (track.write(silence, 0, silence.size) < 0) break
            }
        } catch (_: RuntimeException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    override fun close() {
        open = false
        routing?.let { runCatching { record.removeOnRoutingChangedListener(it) } }
        recordingCallback?.let { runCatching { record.unregisterAudioRecordingCallback(it) } }
        routing = null
        recordingCallback = null
        runCatching { record.stop() }
        runCatching { record.release() }
        route?.release()
    }
}
