package app.thirdear.whitenoise

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject
import org.junit.Test

/** The per-group mention (#13, #16) is off unless chosen, and only a pubkey hex is ever stored. */
class GroupMentionTest {
    private val hex = "ab".repeat(32)

    @Test
    fun `no mention by default, and old settings stay without one`() {
        assertNull(GroupSettings().mention)
        assertNull(GroupSettings.fromJson(JSONObject("""{"replies":"VOICE","read":"ASK"}""")).mention)
    }

    @Test
    fun `a chosen member survives a round trip`() {
        val s = GroupSettings(mention = hex, mentionName = "Hermes")
        assertEquals(s, GroupSettings.fromJson(s.toJson()))
    }

    @Test
    fun `anything that is not a pubkey hex is dropped`() {
        assertNull(GroupSettings.fromJson(JSONObject().put("mention", "npub1xyz")).mention)
        assertNull(GroupSettings.fromJson(JSONObject().put("mention", hex.uppercase())).mention)
    }
}
