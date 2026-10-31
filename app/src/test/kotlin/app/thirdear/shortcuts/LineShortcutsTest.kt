package app.thirdear.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import app.thirdear.actions.ActionStore
import app.thirdear.actions.RecordAction
import app.thirdear.activities.BackgroundRecordActivity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineShortcutsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ActionStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `line ids round-trip through shortcut ids`() {
        assertEquals("ab12cd34", LineShortcuts.lineIdOf(LineShortcuts.lineShortcutId("ab12cd34")))
        assertNull(LineShortcuts.lineIdOf(LineShortcuts.STOP_ID))
        assertNull(LineShortcuts.lineIdOf("line_"))
    }

    @Test
    fun `only intents carrying the install's token are accepted`() {
        val ours = LineShortcuts.lineIntent(context, "save")
        assertTrue(LineShortcuts.isOurs(context, ours))
        assertTrue(LineShortcuts.isOurs(context, LineShortcuts.stopIntent(context)))

        val forged = Intent(ours).putExtra(LineShortcuts.EXTRA_TOKEN, "0".repeat(32))
        assertFalse(LineShortcuts.isOurs(context, forged))
        assertFalse(LineShortcuts.isOurs(context, Intent(ours).apply { removeExtra(LineShortcuts.EXTRA_TOKEN) }))
    }

    @Test
    fun `line shortcut toggles its line, stop shortcut only stops`() {
        val line = LineShortcuts.lineIntent(context, "save")
        assertEquals(BackgroundRecordActivity.RECORD_INTENT_ACTION, line.action)
        assertEquals("save", line.getStringExtra(BackgroundRecordActivity.EXTRA_ACTION_ID))
        assertEquals(BackgroundRecordActivity.STOP_INTENT_ACTION, LineShortcuts.stopIntent(context).action)
    }

    @Test
    fun `the menu holds the first lines in board order, then stop`() {
        ActionStore(context).save((1..6).map { RecordAction(id = "l$it", name = "Line $it") })
        LineShortcuts.publish(context)
        val dynamic = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.sortedBy { it.rank }
        assertEquals(listOf("line_l1", "line_l2", "line_l3", "line_l4", LineShortcuts.STOP_ID), dynamic.map { it.id })
        assertEquals("Line 1", dynamic.first().shortLabel)
    }
}
