package app.thirdear.dialogs

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.thirdear.R
import app.thirdear.audio.Source
import app.thirdear.extensions.config

/**
 * The microphone switch on the recording screen, as in OSTT's keyboard: it asks before switching.
 * Unlike OSTT, the recording continues into the same file. "Switch and keep" also changes the
 * setting for the next recordings.
 */
object SourceSwitchDialog {
    fun show(activity: Activity, recording: Boolean, current: Source, onSwitch: (Source) -> Unit) {
        val to = if (current == Source.BLUETOOTH) Source.PHONE else Source.BLUETOOTH
        val toBluetooth = to == Source.BLUETOOTH
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle(if (toBluetooth) R.string.te_switch_to_bluetooth_title else R.string.te_switch_to_phone_title)
            .setNegativeButton(android.R.string.cancel, null)
        val keep = if (toBluetooth) R.string.te_switch_keep_bluetooth else R.string.te_switch_keep_phone
        if (recording) {
            b.setMessage(R.string.te_switch_message)
                .setPositiveButton(R.string.te_switch_once) { _, _ -> onSwitch(to) }
                .setNeutralButton(keep) { _, _ ->
                    activity.config.preferBluetooth = toBluetooth
                    onSwitch(to)
                }
        } else {
            // nothing records yet: the choice is the setting
            b.setMessage(if (toBluetooth) R.string.te_setting_bluetooth_desc else R.string.te_input_phone)
                .setPositiveButton(keep) { _, _ ->
                    activity.config.preferBluetooth = toBluetooth
                    onSwitch(to)
                }
        }
        b.show()
    }
}
