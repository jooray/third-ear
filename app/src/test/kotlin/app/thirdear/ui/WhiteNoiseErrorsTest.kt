package app.thirdear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WhiteNoiseErrorsTest {
    private class GroupCatchingUp(override val message: String?) : Exception()

    @Test
    fun `an id-only message gives the error's name instead`() {
        assertEquals("Group Catching Up", readableMessage(GroupCatchingUp("groupIdHex=9f3a0c")))
        assertEquals("Group Catching Up", readableMessage(GroupCatchingUp("groupIdHex=9f3a, memberIdHex=11")))
        assertEquals("Group Catching Up", readableMessage(GroupCatchingUp("")))
    }

    @Test
    fun `a real message is kept`() {
        assertEquals("relay refused the event", readableMessage(GroupCatchingUp("relay refused the event")))
    }
}
