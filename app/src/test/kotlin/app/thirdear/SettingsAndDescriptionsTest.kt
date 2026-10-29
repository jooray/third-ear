package app.thirdear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.thirdear.actions.Content
import app.thirdear.actions.Destination
import app.thirdear.actions.RecordAction
import app.thirdear.audio.EngineStatus
import app.thirdear.audio.RoutedInput
import app.thirdear.audio.Source
import app.thirdear.extensions.config
import app.thirdear.extensions.describe
import app.thirdear.extensions.inputLabel
import app.thirdear.helpers.Config
import app.thirdear.whitenoise.GroupSettings
import app.thirdear.whitenoise.GroupSettingsStore
import app.thirdear.whitenoise.ReadMode
import app.thirdear.whitenoise.ReplyMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [34])
class SettingsAndDescriptionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `third ear preferences persist with sensible defaults`() {
        val c = context.config
        assertFalse(c.preferBluetooth)
        assertTrue(c.spokenCues)
        assertFalse(c.pauseMedia)
        assertNull(c.transcriber)
        assertTrue(c.saveTranscriptFiles)
        assertFalse(c.whiteNoiseEnabled)
        c.preferBluetooth = true
        c.spokenCues = false
        c.transcriber = "app.offlinespeechtotext/dev.notune.transcribe.VoiceRecognitionService"
        c.whiteNoiseEnabled = true
        val fresh = Config.newInstance(context)
        assertTrue(fresh.preferBluetooth)
        assertFalse(fresh.spokenCues)
        assertEquals("app.offlinespeechtotext/dev.notune.transcribe.VoiceRecognitionService", fresh.transcriber)
        assertTrue(fresh.whiteNoiseEnabled)
    }

    @Test
    fun `per-group settings persist`() {
        val store = GroupSettingsStore(context)
        assertEquals(GroupSettings(), store.get("g1"))
        store.put("g1", GroupSettings(ReplyMode.VOICE, ReadMode.AUTO, listOf("x")))
        assertEquals(GroupSettings(ReplyMode.VOICE, ReadMode.AUTO, listOf("x")), GroupSettingsStore(context).get("g1"))
        assertEquals(GroupSettings(), store.get("g2"))
    }

    @Test
    fun `buttons say what they will do`() {
        assertEquals("Save", context.describe(RecordAction(name = "a")))
        assertEquals("Save · transcribe · copy transcript", context.describe(RecordAction(name = "a", destination = Destination.COPY_TRANSCRIPT)))
        assertEquals(
            "Save · transcribe · send to Family (audio + transcript)",
            context.describe(RecordAction(name = "a", destination = Destination.WHITE_NOISE, content = Content.AUDIO_AND_TRANSCRIPT, groupName = "Family")),
        )
        assertEquals("Save · share (audio)", context.describe(RecordAction(name = "a", destination = Destination.SHARE)))
    }

    @Test
    fun `source label tells waiting, bluetooth and phone apart`() {
        val base = EngineStatus(starting = false)
        assertEquals("Phone microphone", context.inputLabel(base))
        assertEquals("Waiting for the Bluetooth microphone…", context.inputLabel(base.copy(source = Source.BLUETOOTH, waitingForBluetooth = true)))
        assertEquals("Bluetooth: Aids", context.inputLabel(base.copy(source = Source.BLUETOOTH, input = RoutedInput(true, "Aids"))))
        assertEquals("Bluetooth requested, phone microphone records", context.inputLabel(base.copy(source = Source.BLUETOOTH)))
        assertEquals("Connecting the Bluetooth microphone…", context.inputLabel(base.copy(source = Source.BLUETOOTH, switching = true)))
    }
}
