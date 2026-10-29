package org.fossify.voicerecorder.extensions

import android.content.Context
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.actions.ActionPlan
import org.fossify.voicerecorder.actions.ActionStore
import org.fossify.voicerecorder.actions.Content
import org.fossify.voicerecorder.actions.Destination
import org.fossify.voicerecorder.actions.RecordAction
import org.fossify.voicerecorder.audio.EngineStatus
import org.fossify.voicerecorder.audio.Source

fun Context.defaultActions(): List<RecordAction> =
    ActionStore.defaults(getString(R.string.te_default_action_save), getString(R.string.te_default_action_transcript))

/** The user's actions (creating the defaults on first use). */
fun Context.recordActions(): List<RecordAction> = ActionStore(this).all { defaultActions() }

/** Which microphone records, as the source button and the notification say it. */
fun Context.inputLabel(status: EngineStatus): String = when {
    status.starting || status.switching ->
        if (status.source == Source.BLUETOOTH) getString(R.string.te_input_connecting_bluetooth) else getString(R.string.te_input_starting)

    status.waitingForBluetooth -> getString(R.string.te_input_waiting_bluetooth)
    status.input.bluetooth -> status.input.name?.let { getString(R.string.te_input_bluetooth_named, it) } ?: getString(R.string.te_input_bluetooth)
    status.source == Source.BLUETOOTH -> getString(R.string.te_input_bluetooth_not_routed)
    else -> getString(R.string.te_input_phone)
}

fun Context.destinationLabel(d: Destination): String = getString(
    when (d) {
        Destination.SAVE -> R.string.te_dest_save
        Destination.COPY_TRANSCRIPT -> R.string.te_dest_copy
        Destination.SHARE -> R.string.te_dest_share
        Destination.WHITE_NOISE -> R.string.te_dest_white_noise
        Destination.WEBHOOK -> R.string.te_dest_webhook
    },
)

fun Context.contentLabel(c: Content): String = getString(
    when (c) {
        Content.AUDIO -> R.string.te_content_audio
        Content.AUDIO_AND_TRANSCRIPT -> R.string.te_content_both
        Content.TRANSCRIPT -> R.string.te_content_text
    },
)

/** One line saying exactly what the action does: "Save · transcribe · send to Family (audio + transcript)". */
fun Context.describe(action: RecordAction): String {
    val plan = ActionPlan.of(action)
    return plan.steps.joinToString(" · ") { step ->
        when (step) {
            ActionPlan.Step.SAVE -> getString(R.string.te_step_desc_save)
            ActionPlan.Step.TRANSCRIBE -> getString(R.string.te_step_desc_transcribe)
            ActionPlan.Step.COPY -> getString(R.string.te_step_desc_copy)
            ActionPlan.Step.SHARE -> getString(R.string.te_step_desc_share, contentLabel(action.content))
            ActionPlan.Step.WHITE_NOISE -> getString(
                R.string.te_step_desc_white_noise, action.groupName ?: getString(R.string.te_no_group), contentLabel(action.content),
            )

            ActionPlan.Step.WEBHOOK -> getString(R.string.te_step_desc_webhook, contentLabel(action.content))
        }
    }
}
