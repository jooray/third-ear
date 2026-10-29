package app.thirdear.whitenoise

import java.io.File
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.thirdear.whitenoise.ReplyPolicy.Decision
import app.thirdear.whitenoise.ReplyPolicy.Message
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplyPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val voice = Message(text = "", hasAudio = true)
    private val text = Message(text = "Dinner is ready", hasAudio = false)
    private val both = Message(text = "Listen to this", hasAudio = true)

    private fun s(replies: ReplyMode, read: ReadMode, ignore: List<String> = listOf(GroupSettings.DEFAULT_IGNORE)) = GroupSettings(replies, read, ignore)

    @Test
    fun `off plays nothing and posts nothing`() {
        assertEquals(Decision.NOTHING, ReplyPolicy.decide(both, s(ReplyMode.OFF, ReadMode.AUTO), carConnected = false, recording = false))
    }

    @Test
    fun `voice only plays voice automatically and never reads text`() {
        val d = ReplyPolicy.decide(both, s(ReplyMode.VOICE, ReadMode.AUTO), false, false)
        assertTrue(d.notify)
        assertTrue(d.autoPlayAudio)
        assertFalse(d.autoReadText)
        assertFalse(d.offerRead)
        val t = ReplyPolicy.decide(text, s(ReplyMode.VOICE, ReadMode.AUTO), false, false)
        assertTrue(t.notify, "a text message is still shown")
        assertFalse(t.autoReadText || t.offerRead)
    }

    @Test
    fun `voice and text in automatic mode plays and reads at once`() {
        val d = ReplyPolicy.decide(both, s(ReplyMode.VOICE_AND_TEXT, ReadMode.AUTO), false, false)
        assertEquals(Decision(notify = true, autoPlayAudio = true, autoReadText = true, offerPlay = false, offerRead = false), d)
    }

    @Test
    fun `ask mode only offers`() {
        val d = ReplyPolicy.decide(both, s(ReplyMode.VOICE_AND_TEXT, ReadMode.ASK), false, false)
        assertEquals(Decision(notify = true, autoPlayAudio = false, autoReadText = false, offerPlay = true, offerRead = true), d)
        val v = ReplyPolicy.decide(voice, s(ReplyMode.VOICE_AND_TEXT, ReadMode.ASK), false, false)
        assertTrue(v.offerPlay)
        assertFalse(v.offerRead, "no text to read")
    }

    @Test
    fun `in the car automatic becomes ask`() {
        val d = ReplyPolicy.decide(both, s(ReplyMode.VOICE_AND_TEXT, ReadMode.AUTO), carConnected = true, recording = false)
        assertFalse(d.autoPlayAudio || d.autoReadText)
        assertTrue(d.offerPlay && d.offerRead && d.notify)
    }

    @Test
    fun `while recording automatic becomes ask`() {
        val d = ReplyPolicy.decide(text, s(ReplyMode.VOICE_AND_TEXT, ReadMode.AUTO), carConnected = false, recording = true)
        assertFalse(d.autoReadText)
        assertTrue(d.offerRead)
    }

    @Test
    fun `ignore list matches loosely and silences the message completely`() {
        val echo = Message(text = "🎙 Transcript of your note", hasAudio = false) // without variation selector
        val d = ReplyPolicy.decide(echo, s(ReplyMode.VOICE_AND_TEXT, ReadMode.AUTO), false, false)
        assertEquals(GroupSettings.DEFAULT_IGNORE, d.ignoredBy)
        assertFalse(d.notify || d.autoReadText || d.offerRead)
        val custom = ReplyPolicy.decide(Message("BOT: status ok", false), s(ReplyMode.VOICE_AND_TEXT, ReadMode.AUTO, listOf("bot:")), false, false)
        assertEquals("bot:", custom.ignoredBy)
        assertFalse(ReplyPolicy.containsLoosely("anything", "  "))
    }

    @Test
    fun `group settings round trip and default for new groups`() {
        val g = GroupSettings(ReplyMode.VOICE, ReadMode.AUTO, listOf("a", "🎙️"))
        assertEquals(g, GroupSettings.fromJson(JSONObject(g.toJson().toString())))
        assertEquals(GroupSettings(ReplyMode.VOICE_AND_TEXT, ReadMode.ASK, listOf(GroupSettings.DEFAULT_IGNORE)), GroupSettings.fromJson(null))
        assertEquals(emptyList(), GroupSettings.fromJson(JSONObject().put("ignore", org.json.JSONArray())).ignore, "an emptied list stays empty")
        assertEquals(listOf("x", "y"), GroupSettings.parseIgnore(" x \n\n y\n"))
    }

    @Test
    fun `incoming store keeps newest first, unread per group, and survives reload`() {
        val store = IncomingStore(File(tmp.root, "in"))
        store.add(IncomingMessage("1", "g", "G", "s", "Ann", "first", 100))
        store.add(IncomingMessage("2", "g", "G", "s", "Ann", "second", 200))
        store.add(IncomingMessage("3", "h", "H", "s", null, "other", 300))
        store.add(IncomingMessage("4", "g", "G", "s", null, "🎙️ echo", 400, ignoredBy = "🎙️"))
        assertEquals(listOf("4", "3", "2", "1"), IncomingStore(File(tmp.root, "in")).load().map { it.id })
        assertEquals(setOf("1", "2"), store.unread("g").map { it.id }.toSet())
        store.markRead(listOf("1"))
        assertEquals(listOf("2"), store.unread("g").map { it.id })
        store.add(IncomingMessage("2", "g", "G", "s", "Ann", "second", 200)) // duplicate id replaces
        assertEquals(4, store.load().size)
    }

    @Test
    fun `markdown is read as words and the voice language follows the text`() {
        assertEquals("Hello world see docs", plain("**Hello** _world_ see [docs](https://x.org)"))
        assertEquals(Locale.forLanguageTag("sk"), guessLocale("Ahoj, ako sa máš? Večera je hotová."))
        assertEquals(Locale.forLanguageTag("cs"), guessLocale("Řekni mi to"))
        assertEquals(Locale.ENGLISH, guessLocale("this is the answer"))
    }
}
