package app.thirdear.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getFilenameExtension
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.renameDocumentSdk30
import org.fossify.commons.extensions.renameFile
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import app.thirdear.extensions.config
import app.thirdear.extensions.handleSafSdk30
import app.thirdear.models.Events
import app.thirdear.models.Recording
import org.greenrobot.eventbus.EventBus
import java.io.File

/**
 * Renames a recording (and the transcript beside it, and Third Ear's note) to [newTitle], keeping
 * its extension. [onDone] gets null on success, else what was wrong, on a background thread.
 */
fun renameRecording(activity: BaseSimpleActivity, recording: Recording, newTitle: String, onDone: (error: String?) -> Unit) {
    val title = newTitle.trim()
    when {
        title.isEmpty() -> return onDone(activity.getString(org.fossify.commons.R.string.empty_name))
        !title.isAValidFilename() -> return onDone(activity.getString(org.fossify.commons.R.string.invalid_name))
    }
    ensureBackgroundThread {
        val error = runCatching {
            if (isRPlus()) RecordingRenamer(activity).rename(recording, title) else RecordingRenamer(activity).renameLegacy(recording, title)
        }.exceptionOrNull()
        onDone(error?.let { it.message ?: it.javaClass.simpleName })
    }
}

private class RecordingRenamer(val activity: BaseSimpleActivity) {
    fun rename(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val newDisplayName = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"

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
    }

    fun renameLegacy(recording: Recording, newTitle: String) {
        val oldExtension = recording.title.getFilenameExtension()
        val oldPath = recording.path
        val newFilename = "${newTitle.removeSuffix(".$oldExtension")}.$oldExtension"
        val newPath = File(oldPath.getParentPath(), newFilename).absolutePath
        activity.renameFile(oldPath, newPath, false)
    }
}
