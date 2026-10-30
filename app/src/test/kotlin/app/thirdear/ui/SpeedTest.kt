package app.thirdear.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import app.thirdear.whitenoise.GroupSettings
import org.junit.Test

class SpeedTest {

    @Test
    fun `a group's speed survives storage, and no speed means the app default`() {
        assertEquals(1.5f, GroupSettings.fromJson(GroupSettings(speed = 1.5f).toJson()).speed)
        assertNull(GroupSettings.fromJson(GroupSettings().toJson()).speed)
        // settings saved before speeds existed
        assertNull(GroupSettings.fromJson(org.json.JSONObject("""{"replies":"VOICE","read":"ASK"}""")).speed)
    }

    @Test
    fun `the speed key steps through the offered speeds and wraps`() {
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(2f, nextSpeed(1.75f))
        assertEquals(1f, nextSpeed(2f))
        // a speed not on the list moves to the next one above it
        assertEquals(1.5f, nextSpeed(1.3f))
    }
}
