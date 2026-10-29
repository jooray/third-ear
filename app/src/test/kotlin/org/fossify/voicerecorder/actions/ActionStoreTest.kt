package org.fossify.voicerecorder.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ActionStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val defaults = { ActionStore.defaults("Save", "Copy") }

    @Test
    fun `defaults are stored on first use and survive a new store`() {
        val a = ActionStore(context).all(defaults)
        assertEquals(listOf("save", "transcript"), a.map { it.id })
        assertEquals(a, ActionStore(context).stored())
    }

    @Test
    fun `every field persists`() {
        val full = RecordAction(
            id = "wn", name = "Family", destination = Destination.WHITE_NOISE, content = Content.AUDIO_AND_TRANSCRIPT,
            transcribe = true, copyTranscript = true, groupId = "abcd", groupName = "Family", webhookUrl = null, webhookAuth = null,
        )
        val hook = RecordAction(id = "h", name = "Hook", destination = Destination.WEBHOOK, webhookUrl = "https://x", webhookAuth = "Bearer t")
        ActionStore(context).save(listOf(full, hook))
        assertEquals(listOf(full, hook), ActionStore(context).stored())
    }

    @Test
    fun `upsert, move, delete`() {
        val s = ActionStore(context)
        s.all(defaults)
        s.upsert(RecordAction(id = "n", name = "New"))
        assertEquals(listOf("save", "transcript", "n"), s.stored()!!.map { it.id })
        s.upsert(RecordAction(id = "save", name = "Renamed"))
        assertEquals("Renamed", s.get("save")!!.name)
        s.move("n", -2)
        assertEquals(listOf("n", "save", "transcript"), s.stored()!!.map { it.id })
        s.move("n", -1) // already first: no-op
        assertEquals("n", s.stored()!!.first().id)
        s.delete("save")
        assertEquals(listOf("n", "transcript"), s.stored()!!.map { it.id })
    }

    @Test
    fun `tiles fall back to the action at their position, and a deleted choice is forgotten`() {
        val s = ActionStore(context)
        s.all(defaults)
        assertEquals("save", s.tileAction(1)!!.id)
        assertEquals("transcript", s.tileAction(2)!!.id)
        assertNull(s.tileAction(3))
        s.upsert(RecordAction(id = "x", name = "X"))
        s.setTile(1, "x")
        assertEquals("x", s.tileAction(1)!!.id)
        s.delete("x")
        assertEquals("save", s.tileAction(1)!!.id)
    }

    @Test
    fun `widget uses the chosen action, else the first`() {
        val s = ActionStore(context)
        s.all(defaults)
        assertEquals("save", s.defaultAction()!!.id)
        s.widgetActionId = "transcript"
        assertEquals("transcript", ActionStore(context).defaultAction()!!.id)
        s.delete("transcript")
        assertEquals("save", s.defaultAction()!!.id)
    }

    @Test
    fun `corrupt json reads as empty instead of crashing`() {
        context.getSharedPreferences(ActionStore.PREFS, Context.MODE_PRIVATE).edit().putString("actions", "{not json").commit()
        assertEquals(emptyList(), ActionStore(context).stored())
    }
}
