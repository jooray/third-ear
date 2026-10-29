package app.thirdear.activities

import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.openNotificationSettings
import app.thirdear.services.RecorderService

/**
 * Invisible trampoline for the widget, the Quick Settings tiles and the car screen: Android lets
 * a microphone service start only while the app is visible, and this activity is, for a moment.
 * Toggles: starts recording with [EXTRA_ACTION_ID] (or the default action), or stops the running
 * recording with it.
 */
class BackgroundRecordActivity : SimpleActivity() {
    companion object {
        const val RECORD_INTENT_ACTION = "RECORD_ACTION"
        const val EXTRA_ACTION_ID = RecorderService.EXTRA_ACTION_ID
    }

    override fun onResume() {
        super.onResume()
        if (intent.action == RECORD_INTENT_ACTION) {
            val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
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
        moveTaskToBack(true)
        finish()
    }
}
