package app.thirdear.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.renameDocumentSdk30
import org.fossify.commons.extensions.renameFile
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import app.thirdear.databinding.DialogRenameRecordingBinding
import app.thirdear.extensions.config
import app.thirdear.extensions.handleSafSdk30
import app.thirdear.models.Events
import app.thirdear.models.Recording
import org.greenrobot.eventbus.EventBus
import java.io.File

class RenameRecordingDialog(
    val activity: BaseSimpleActivity,
    val recording: Recording,
    val callback: () -> Unit
) {
    init {
        val binding = DialogRenameRecordingBinding.inflate(activity.layoutInflater).apply {
            renameRecordingTitle.setText(recording.title.substringBeforeLast('.'))
        }
        val view = binding.root

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(
                    view = view,
                    dialog = this,
                    titleId = org.fossify.commons.R.string.rename
                ) { alertDialog ->
                    alertDialog.showKeyboard(binding.renameRecordingTitle)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.renameRecordingTitle.value
                        if (newTitle.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                            return@setOnClickListener
                        }

                        if (!newTitle.isAValidFilename()) {
                            activity.toast(org.fossify.commons.R.string.invalid_name)
                            return@setOnClickListener
                        }

                        ensureBackgroundThread {
                            if (isRPlus()) {
                                renameRecording(recording, newTitle)
                            } else {
                                renameRecordingLegacy(recording, newTitle)
                            }

                            activity.runOnUiThread {
                                callback()
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun renameRecording(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val newDisplayName = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"

        try {
            val path = "${activity.config.saveRecordingsFolder}/${recording.title}"
            val newPath = "${path.getParentPath()}/$newDisplayName"
            activity.handleSafSdk30(path) {
                val success = activity.renameDocumentSdk30(path, newPath)
                if (success) {
                    // the transcript next to it and Third Ear's note follow the new name
                    val folder = path.getParentPath()
                    val oldTxt = "$folder/${app.thirdear.pipeline.NoteStore.transcriptName(recording.title)}"
                    if (activity.getDoesFilePathExistSdk30(oldTxt)) {
                        runCatching { activity.renameDocumentSdk30(oldTxt, "$folder/${app.thirdear.pipeline.NoteStore.transcriptName(newDisplayName)}") }
                    }
                    runCatching {
                        app.thirdear.pipeline.NoteStore(activity).renamed(
                            recording.title, newDisplayName, activity.createDocumentUriUsingFirstParentTreeUri(newPath).toString(),
                        )
                    }
                    EventBus.getDefault().post(Events.RecordingCompleted())
                }
            }
        } catch (e: Exception) {
            activity.showErrorToast(e)
        }
    }

    private fun renameRecordingLegacy(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val oldPath = recording.path
        val newFilename = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"
        val newPath = File(oldPath.getParentPath(), newFilename).absolutePath
        activity.renameFile(oldPath, newPath, false)
    }
}
