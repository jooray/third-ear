package app.thirdear.ui

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.ActionPlan.Delivery
import app.thirdear.actions.RecordAction
import org.json.JSONObject
import org.junit.Test

class ChatActionTest {

    @Test
    fun `the audio key sends only the recording`() {
        val plan = ActionPlan.of(chatAction("ab12cd34ef56aa", "Family", transcript = false))
        assertEquals(false, plan.transcribe)
        assertEquals(Delivery.Audio(null), plan.delivery(null))
        assertTrue(plan.problems(transcriberAvailable = false).isEmpty())
    }

    @Test
    fun `the transcript key sends only the text, and the audio when there are no words`() {
        val plan = ActionPlan.of(chatAction("ab12cd34ef56aa", "Family", transcript = true))
        assertTrue(plan.transcribe)
        assertEquals(Delivery.Text("hello"), plan.delivery("hello"))
        assertEquals(Delivery.Audio(null), plan.delivery(""))
    }

    @Test
    fun `it survives the trip through the service intent and is told apart from lines`() {
        val a = chatAction("ab12cd34ef56aa", "Family", transcript = true)
        assertEquals(a, RecordAction.fromJson(JSONObject(a.toJson().toString())))
        assertTrue(a.id.startsWith(CHAT_ACTION_PREFIX))
        assertNotEquals(a.id, chatAction("ab12cd34ef56aa", "Family", transcript = false).id)
    }
}
