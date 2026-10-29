package app.thirdear.extensions

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import org.fossify.commons.extensions.internalStoragePath
import app.thirdear.activities.SimpleActivity
import android.view.WindowManager
import androidx.core.net.toUri
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.FilePickerDialog
import org.fossify.commons.extensions.createDocumentUriUsingFirstParentTreeUri
import org.fossify.commons.extensions.createSAFDirectorySdk30
import org.fossify.commons.extensions.deleteFile
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.hasProperStoredFirstParentUri
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.isShowingSAFDialogSdk30
import org.fossify.commons.extensions.toFileDirItem
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.models.FileDirItem
import app.thirdear.dialogs.StoragePermissionDialog
import app.thirdear.models.Recording
import java.io.File

fun Activity.setKeepScreenAwake(keepScreenOn: Boolean) {
    if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

fun BaseSimpleActivity.ensureStoragePermission(callback: (result: Boolean) -> Unit) {
    val folder = config.saveRecordingsFolder
    if (hasProperStoredFirstParentUri(folder)) {
        ensureFolderExists(folder)
        callback(true)
        return
    }
    // commons explains, then opens the system picker on the folder's top-level directory
    handleSafSdk30(folder) { granted ->
        val ok = granted && hasProperStoredFirstParentUri(folder)
        if (ok) ensureFolderExists(folder)
        callback(ok)
    }
}

private fun BaseSimpleActivity.ensureFolderExists(folder: String) {
    runCatching { if (!getDoesFilePathExistSdk30(folder)) createSAFDirectorySdk30(folder) }
}

/**
 * Picks the recordings folder with the system folder picker (commons' own picker and its
 * "create folder" dialog skip the permission grant for packages not named org.fossify.*), then
 * makes sure Third Ear holds the grant for the folder's top-level directory, which is what the
 * Fossify storage code writes through.
 */
fun BaseSimpleActivity.launchFolderPicker(
    currentPath: String,
    callback: (newPath: String?) -> Unit
) {
    SimpleActivity.pickFolder(this) { tree ->
        val path = tree?.let { treeUriToPath(it) }
        if (path == null) {
            callback(null)
            return@pickFolder
        }
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (hasProperStoredFirstParentUri(path)) {
            callback(path)
        } else {
            handleSafSdk30(path) { granted -> callback(if (granted && hasProperStoredFirstParentUri(path)) path else null) }
        }
    }
}

/** content://…externalstorage…/tree/primary:Music/Third Ear → /storage/emulated/0/Music/Third Ear */
fun BaseSimpleActivity.treeUriToPath(tree: android.net.Uri): String? = runCatching {
    val id = DocumentsContract.getTreeDocumentId(tree)
    val volume = id.substringBefore(':')
    val rel = id.substringAfter(':', "")
    val root = if (volume == "primary") internalStoragePath else "/storage/$volume"
    if (rel.isEmpty()) root else "$root/$rel"
}.getOrNull()

/**
 * fossify-commons' `handleSAFDialogSdk30` skips the folder-permission dialog (and reports success)
 * for any package not named `org.fossify.*`, which leaves a fork like Third Ear without write
 * access to its folder. This does the same thing as the original without that check.
 */
fun BaseSimpleActivity.handleSafSdk30(path: String, callback: (granted: Boolean) -> Unit) {
    hideKeyboard()
    if (isShowingSAFDialogSdk30(path, showRationale = false)) {
        BaseSimpleActivity.funAfterSdk30Action = callback
    } else {
        callback(true)
    }
}

fun BaseSimpleActivity.deleteRecordings(
    recordingsToRemove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        if (isRPlus()) {
            val resolver = contentResolver
            recordingsToRemove.forEach {
                DocumentsContract.deleteDocument(resolver, it.path.toUri())
                transcriptSibling(it.path.toUri())?.let { txt -> runCatching { DocumentsContract.deleteDocument(resolver, txt) } }
            }
        } else {
            recordingsToRemove.forEach {
                val fileDirItem = File(it.path).toFileDirItem(this)
                deleteFile(fileDirItem)
            }
        }

        callback(true)
    }
}

fun BaseSimpleActivity.trashRecordings(
    recordingsToMove: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToMove,
    sourceParent = config.saveRecordingsFolder,
    destinationParent = getOrCreateTrashFolder(),
    callback = callback
)

fun BaseSimpleActivity.restoreRecordings(
    recordingsToRestore: Collection<Recording>,
    callback: (success: Boolean) -> Unit
) = moveRecordings(
    recordingsToMove = recordingsToRestore,
    sourceParent = getOrCreateTrashFolder(),
    destinationParent = config.saveRecordingsFolder,
    callback = callback
)

fun BaseSimpleActivity.moveRecordings(
    recordingsToMove: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    if (isRPlus()) {
        moveRecordingsSAF(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    } else {
        moveRecordingsLegacy(
            recordings = recordingsToMove,
            sourceParent = sourceParent,
            destinationParent = destinationParent,
            callback = callback
        )
    }
}

private fun BaseSimpleActivity.moveRecordingsSAF(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    ensureBackgroundThread {
        val contentResolver = contentResolver
        val sourceParentDocumentUri = createDocumentUriUsingFirstParentTreeUri(sourceParent)
        val destinationParentDocumentUri =
            createDocumentUriUsingFirstParentTreeUri(destinationParent)

        if (!getDoesFilePathExistSdk30(destinationParent)) {
            createSAFDirectorySdk30(destinationParent)
        }

        recordings.forEach { recording ->
            // Third Ear's transcript (.txt next to the recording) goes along, when there is one
            transcriptSibling(recording.path.toUri())?.let { txt ->
                runCatching { DocumentsContract.moveDocument(contentResolver, txt, sourceParentDocumentUri, destinationParentDocumentUri) }
            }
            try {
                DocumentsContract.moveDocument(
                    contentResolver,
                    recording.path.toUri(),
                    sourceParentDocumentUri,
                    destinationParentDocumentUri
                )
            } catch (@Suppress("SwallowedException") e: IllegalStateException) {
                val sourceUri = recording.path.toUri()
                contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    val targetPath = File(destinationParent, recording.title).absolutePath
                    val targetUri = createDocumentFile(targetPath) ?: return@forEach
                    contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    DocumentsContract.deleteDocument(contentResolver, sourceUri)
                }
            }
        }

        callback(true)
    }
}

private fun BaseSimpleActivity.moveRecordingsLegacy(
    recordings: Collection<Recording>,
    sourceParent: String,
    destinationParent: String,
    callback: (success: Boolean) -> Unit
) {
    copyMoveFilesTo(
        fileDirItems = recordings
            .map { File(it.path).toFileDirItem(this) }
            .toMutableList() as ArrayList<FileDirItem>,
        source = sourceParent,
        destination = destinationParent,
        isCopyOperation = false,
        copyPhotoVideoOnly = false,
        copyHidden = false
    ) {
        callback(true)
    }
}

fun BaseSimpleActivity.deleteTrashedRecordings() {
    deleteRecordings(getAllRecordings(trashed = true)) {}
}

fun BaseSimpleActivity.deleteExpiredTrashedRecordings() {
    if (
        config.useRecycleBin &&
        config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                val recordingsToRemove = getAllRecordings(trashed = true)
                    .filter { it.timestamp < System.currentTimeMillis() - MONTH_SECONDS * 1000L }
                if (recordingsToRemove.isNotEmpty()) {
                    deleteRecordings(recordingsToRemove) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/** The document Uri of "<same name>.txt" next to a recording (SAF document ids are paths). */
fun transcriptSibling(recording: android.net.Uri): android.net.Uri? = runCatching {
    val id = DocumentsContract.getDocumentId(recording)
    if (!id.contains('.')) return null
    val tree = DocumentsContract.buildTreeDocumentUri(recording.authority, DocumentsContract.getTreeDocumentId(recording))
    DocumentsContract.buildDocumentUriUsingTree(tree, id.substringBeforeLast('.') + ".txt")
}.getOrNull()
