package org.fossify.voicerecorder.dialogs

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.models.RadioItem
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.actions.ActionPlan
import org.fossify.voicerecorder.actions.Content
import org.fossify.voicerecorder.actions.Destination
import org.fossify.voicerecorder.actions.RecordAction
import org.fossify.voicerecorder.databinding.DialogEditActionBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.describe
import org.fossify.voicerecorder.pipeline.Transcriber
import org.fossify.voicerecorder.whitenoise.WhiteNoise

/** Edits one action: name, destination, what is sent, transcript options; says what it will do. */
class ActionEditDialog(
    private val activity: Activity,
    original: RecordAction?,
    private val onSave: (RecordAction) -> Unit,
) {
    private val binding = DialogEditActionBinding.inflate(activity.layoutInflater)
    private var action = original ?: RecordAction(name = "")
    private val transcriberAvailable = Transcriber(activity).available(activity.config.transcriber)

    private val destinations = mapOf(
        Destination.SAVE to R.id.dest_save,
        Destination.COPY_TRANSCRIPT to R.id.dest_copy,
        Destination.SHARE to R.id.dest_share,
        Destination.WHITE_NOISE to R.id.dest_white_noise,
        Destination.WEBHOOK to R.id.dest_webhook,
    )
    private val contents = mapOf(
        Content.AUDIO to R.id.content_audio,
        Content.AUDIO_AND_TRANSCRIPT to R.id.content_both,
        Content.TRANSCRIPT to R.id.content_text,
    )

    init {
        binding.editActionName.setText(action.name)
        binding.editActionUrl.setText(action.webhookUrl.orEmpty())
        binding.editActionAuth.setText(action.webhookAuth.orEmpty())
        binding.editActionDestination.check(destinations.getValue(action.destination))
        binding.editActionContent.check(contents.getValue(action.content))
        binding.editActionTranscribe.isChecked = action.transcribe
        binding.editActionCopy.isChecked = action.copyTranscript

        binding.editActionName.doAfterTextChanged { update() }
        binding.editActionUrl.doAfterTextChanged { update() }
        binding.editActionAuth.doAfterTextChanged { update() }
        binding.editActionDestination.setOnCheckedChangeListener { _, _ -> update() }
        binding.editActionContent.setOnCheckedChangeListener { _, _ -> update() }
        binding.editActionTranscribe.setOnCheckedChangeListener { _, _ -> update() }
        binding.editActionCopy.setOnCheckedChangeListener { _, _ -> update() }
        binding.editActionGroup.setOnClickListener { pickGroup() }
        update()

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, if (original == null) R.string.te_new_action else R.string.te_edit_action) { dialog ->
                    activity.updateTextColors(binding.root)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val problems = ActionPlan.of(read()).problems(transcriberAvailable)
                            // a missing transcription app is a warning; the recording is still saved
                            .filter { it != ActionPlan.Problem.NO_TRANSCRIBER }
                        if (problems.isEmpty()) {
                            onSave(read())
                            dialog.dismiss()
                        } else {
                            activity.toast(problemText(problems.first()))
                        }
                    }
                }
            }
    }

    private fun read(): RecordAction {
        val destination = destinations.entries.first { it.value == binding.editActionDestination.checkedRadioButtonId }.key
        val content = contents.entries.firstOrNull { it.value == binding.editActionContent.checkedRadioButtonId }?.key ?: Content.AUDIO
        return action.copy(
            name = binding.editActionName.text?.toString()?.trim().orEmpty(),
            destination = destination,
            content = content,
            transcribe = binding.editActionTranscribe.isChecked,
            copyTranscript = binding.editActionCopy.isChecked,
            webhookUrl = binding.editActionUrl.text?.toString()?.trim()?.ifEmpty { null },
            webhookAuth = binding.editActionAuth.text?.toString()?.trim()?.ifEmpty { null },
        ).also { action = it }
    }

    private fun update() {
        val a = read()
        val sends = a.destination in setOf(Destination.SHARE, Destination.WHITE_NOISE, Destination.WEBHOOK)
        binding.editActionContentLabel.beVisibleIf(sends)
        binding.editActionContent.beVisibleIf(sends)
        binding.editActionGroup.beVisibleIf(a.destination == Destination.WHITE_NOISE)
        binding.editActionUrlHint.beVisibleIf(a.destination == Destination.WEBHOOK)
        binding.editActionAuthHint.beVisibleIf(a.destination == Destination.WEBHOOK)
        // implied by the destination: shown checked and fixed
        val impliedTranscript = ActionPlan.of(a.copy(transcribe = false, copyTranscript = false)).transcribe
        binding.editActionTranscribe.isEnabled = !impliedTranscript
        binding.editActionCopy.beVisibleIf(a.destination != Destination.COPY_TRANSCRIPT)
        binding.editActionGroup.text = activity.getString(R.string.te_action_group) + ": " + (a.groupName ?: activity.getString(R.string.te_action_pick_group))
        binding.editActionSummary.text = activity.describe(a)
        val problems = ActionPlan.of(a).problems(transcriberAvailable)
        binding.editActionProblems.text = problems.joinToString("\n") { problemText(it) }
        binding.editActionProblems.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun problemText(p: ActionPlan.Problem) = activity.getString(
        when (p) {
            ActionPlan.Problem.NO_NAME -> R.string.te_problem_no_name
            ActionPlan.Problem.NO_GROUP -> R.string.te_problem_no_group
            ActionPlan.Problem.NO_WEBHOOK_URL -> R.string.te_problem_no_webhook
            ActionPlan.Problem.NO_TRANSCRIBER -> R.string.te_problem_no_transcriber
        },
    )

    private fun pickGroup() {
        if (!activity.config.whiteNoiseEnabled) {
            activity.toast(R.string.te_white_noise_off)
            return
        }
        val groups = WhiteNoise.joinedGroups()
        if (groups.isEmpty()) {
            activity.toast(R.string.te_no_groups)
            return
        }
        val items = groups.mapIndexed { i, g -> RadioItem(i, g.name) }
        RadioGroupDialog(activity, ArrayList(items), groups.indexOfFirst { it.id == action.groupId }) {
            val g = groups[it as Int]
            action = read().copy(groupId = g.id, groupName = g.name)
            update()
        }
    }
}
