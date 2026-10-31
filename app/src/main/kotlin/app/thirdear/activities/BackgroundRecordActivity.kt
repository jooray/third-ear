package app.thirdear.activities

import android.content.Intent
import android.widget.Toast
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.openNotificationSettings
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.services.RecorderService

/**
 * Invisible trampoline for the widget, the Quick Settings tiles, the car screen and the app
 * shortcuts: Android lets a microphone service start only while the app is visible, and this
 * activity is, for a moment. [RECORD_INTENT_ACTION] toggles: starts recording with
 * [EXTRA_ACTION_ID] (or the default action), or stops the running recording with it.
 * [STOP_INTENT_ACTION] only stops, on the line the recording was started with.
 */
open class BackgroundRecordActivity : SimpleActivity() {
    companion object {
        const val RECORD_INTENT_ACTION = "RECORD_ACTION"
        const val STOP_INTENT_ACTION = "STOP_ACTION"
        const val EXTRA_ACTION_ID = RecorderService.EXTRA_ACTION_ID
    }

    /** Whether to act on [intent]; the exported shortcut entry checks its token here. */
    protected open fun accepts(intent: Intent): Boolean = true

    override fun onResume() {
        super.onResume()
        if (accepts(intent)) {
            when (intent.action) {
                RECORD_INTENT_ACTION -> record(intent.getStringExtra(EXTRA_ACTION_ID))
                STOP_INTENT_ACTION ->
                    if (RecorderService.isRunning) {
                        RecorderService.stop(this, null)
                    } else {
                        Toast.makeText(this, R.string.te_shortcut_not_recording, Toast.LENGTH_SHORT).show()
                    }
            }
        }
        moveTaskToBack(true)
        finish()
    }

    private fun record(actionId: String?) {
        // a shortcut can outlive its line; recording on another line instead would surprise
        if (actionId != null && ActionStore(this).stored() != null && ActionStore(this).get(actionId) == null) {
            Toast.makeText(this, R.string.te_shortcut_line_gone, Toast.LENGTH_LONG).show()
            return
        }
        if (RecorderService.isRunning) {
            RecorderService.stop(this, actionId)
        } else {
            handleNotificationPermission { granted ->
                if (granted) {
                    RecorderService.start(this, actionId)
                } else {
                    PermissionRequiredDialog(
                        activity = this,
                        textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                        positiveActionCallback = { openNotificationSettings() }
                    )
                }
            }
        }
    }
}

/**
 * The exported door for app shortcuts (launcher menu, pinned shortcuts, automation and
 * button-mapping apps). Acts only on intents carrying Third Ear's own shortcut token.
 */
class ShortcutActivity : BackgroundRecordActivity() {
    override fun accepts(intent: Intent): Boolean = app.thirdear.shortcuts.LineShortcuts.isOurs(this, intent)
}
