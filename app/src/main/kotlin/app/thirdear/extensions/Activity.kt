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
import org.fossify.commons.extensions.deleteFile
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
    if (hasFolderAccess(folder)) {
        ensureBackgroundThread { makeFolder(folder) }
        callback(true)
        return
    }
    // the first recording (or the grant is gone): the folder the user picks is where recordings go
    StoragePermissionDialog(this) { ok ->
        if (!ok) {
            callback(false)
            return@StoragePermissionDialog
        }
        launchFolderPicker(initial = folder) { picked ->
            if (picked == null) {
                callback(false)
            } else {
                config.saveRecordingsFolder = picked
                callback(true)
            }
        }
    }
}

/**
 * Picks a folder with the system folder picker and keeps the grant for it: that folder is where
 * recordings go, nothing above it is needed. [initial]: where the picker opens.
 */
fun BaseSimpleActivity.launchFolderPicker(initial: String? = null, callback: (newPath: String?) -> Unit) {
    SimpleActivity.pickFolder(this, initial?.let { pickerStart(it) }) { tree ->
        val path = tree?.let { treeUriToPath(it) }
        if (tree == null || path == null) {
            callback(null)
            return@pickFolder
        }
        // kept across restarts: this folder is the grant
        runCatching {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        callback(path)
    }
}

/** The picker opens at the nearest existing folder above [path] on internal storage (Music for the default). */
private fun BaseSimpleActivity.pickerStart(path: String): android.net.Uri? {
    var p = path.trimEnd('/')
    while (p.length > internalStoragePath.length && !File(p).exists()) p = p.substringBeforeLast('/')
    if (!p.startsWith(internalStoragePath) || p.length <= internalStoragePath.length) return null
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + p.removePrefix("$internalStoragePath/"))
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
        makeFolder(destinationParent)
        val sourceParentDocumentUri = documentUri(sourceParent)
        val destinationParentDocumentUri = documentUri(destinationParent)
        if (sourceParentDocumentUri == null || destinationParentDocumentUri == null) {
            callback(false)
            return@ensureBackgroundThread
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
            } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
                // folders under different grants can't move a document: copy and delete instead
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
