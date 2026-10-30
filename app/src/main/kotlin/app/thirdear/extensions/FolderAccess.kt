package app.thirdear.extensions

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getSAFDocumentId
import org.fossify.commons.extensions.internalStoragePath

/*
 * Access to the recordings folder through the folder the user picked (a persisted SAF tree grant).
 *
 * Fossify's storage helpers need a grant for the top-level folder ("Music") and ask for it with a
 * picker that looks like "choose where to save", so people opened a subfolder and were told
 * "Wrong folder selected" (issue #7). Here whatever folder the user picks is the grant: the
 * recordings folder itself, or any folder above it (installs from before kept their "Music" grant).
 */

/** content://…externalstorage…/tree/primary:Music/Third Ear → /storage/emulated/0/Music/Third Ear */
fun Context.treeUriToPath(tree: Uri): String? = runCatching {
    val id = DocumentsContract.getTreeDocumentId(tree)
    val volume = id.substringBefore(':')
    val rel = id.substringAfter(':', "")
    val root = if (volume == "primary") internalStoragePath else "/storage/$volume"
    if (rel.isEmpty()) root else "$root/$rel"
}.getOrNull()

/** The granted tree that covers [path]: the deepest persisted, writable folder grant at or above it. */
fun Context.grantedTreeFor(path: String): Uri? {
    val target = path.trimEnd('/')
    return contentResolver.persistedUriPermissions
        .filter { it.isWritePermission && it.isReadPermission && runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.isSuccess }
        .mapNotNull { p -> treeUriToPath(p.uri)?.trimEnd('/')?.let { p.uri to it } }
        .filter { (_, root) -> target == root || target.startsWith("$root/") }
        .maxByOrNull { (_, root) -> root.length }
        ?.first
}

fun Context.hasFolderAccess(path: String): Boolean = grantedTreeFor(path) != null

/** The document Uri of [path] inside its granted tree (whether or not it exists yet). */
fun Context.documentUri(path: String): Uri? {
    val tree = grantedTreeFor(path) ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(tree, getSAFDocumentId(path.trimEnd('/')))
}

fun Context.pathExists(path: String): Boolean {
    val uri = documentUri(path) ?: return false
    return runCatching {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

/** A folder as a DocumentFile for listing; null when it is not reachable. */
fun Context.folderDocument(path: String): DocumentFile? {
    val uri = documentUri(path) ?: return null
    return DocumentFile.fromTreeUri(this, uri)?.takeIf { it.exists() && it.isDirectory }
}

/** Creates [path] and any missing folders above it, inside the granted tree. */
fun Context.makeFolder(path: String): Boolean {
    val tree = grantedTreeFor(path) ?: return false
    val root = treeUriToPath(tree)?.trimEnd('/') ?: return false
    var current = root
    for (segment in path.trimEnd('/').removePrefix(root).split('/').filter { it.isNotEmpty() }) {
        val next = "$current/$segment"
        if (!pathExists(next)) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, getSAFDocumentId(current))
            runCatching { DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, segment) }
                .getOrNull() ?: return false
        }
        current = next
    }
    return true
}

/** Creates the file [path] (and its folder); returns its Uri, or null without access. */
fun Context.makeFile(path: String, mime: String = path.getMimeType()): Uri? {
    val folder = path.substringBeforeLast('/')
    if (!makeFolder(folder)) return null
    val parent = documentUri(folder) ?: return null
    return runCatching {
        DocumentsContract.createDocument(contentResolver, parent, mime, path.substringAfterLast('/'))
    }.getOrNull()
}

/** Renames the file at [path] to [newName] in the same folder; returns its new Uri. */
fun Context.renameInFolder(path: String, newName: String): Uri? {
    val uri = documentUri(path) ?: return null
    return runCatching { DocumentsContract.renameDocument(contentResolver, uri, newName) }.getOrNull()
}

/** The name a document Uri ended up with (a provider may number a name that is taken). */
fun Uri.documentName(): String = DocumentsContract.getDocumentId(this).substringAfterLast('/')
