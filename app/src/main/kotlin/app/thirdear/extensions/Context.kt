package app.thirdear.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSAFDocumentId
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAudioFast
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import app.thirdear.R
import app.thirdear.helpers.Config
import app.thirdear.helpers.DEFAULT_RECORDINGS_FOLDER
import app.thirdear.helpers.IS_RECORDING
import app.thirdear.helpers.MyWidgetRecordDisplayProvider
import app.thirdear.helpers.TOGGLE_WIDGET_UI
import app.thirdear.models.Recording
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.trashFolder
    get() = "${config.saveRecordingsFolder}/.trash"

fun Context.drawableToBitmap(drawable: Drawable): Bitmap {
    val size = (60 * resources.displayMetrics.density).toInt()
    val mutableBitmap = createBitmap(size, size)
    val canvas = Canvas(mutableBitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return mutableBitmap
}

fun Context.updateWidgets(isRecording: Boolean) {
    val widgetIDs = AppWidgetManager.getInstance(applicationContext)
        ?.getAppWidgetIds(
            ComponentName(
                applicationContext,
                MyWidgetRecordDisplayProvider::class.java
            )
        ) ?: return

    if (widgetIDs.isNotEmpty()) {
        Intent(applicationContext, MyWidgetRecordDisplayProvider::class.java).apply {
            action = TOGGLE_WIDGET_UI
            putExtra(IS_RECORDING, isRecording)
            sendBroadcast(this)
        }
    }
}

fun Context.getOrCreateTrashFolder(): String {
    val folder = File(trashFolder)
    if (!folder.exists()) {
        folder.mkdir()
    }
    return trashFolder
}

fun Context.getDefaultRecordingsFolder(): String {
    val defaultPath = getDefaultRecordingsRelativePath()
    return "$internalStoragePath/$defaultPath"
}

fun Context.getDefaultRecordingsRelativePath(): String {
    return if (isQPlus()) {
        "${Environment.DIRECTORY_MUSIC}/$DEFAULT_RECORDINGS_FOLDER"
    } else {
        getString(R.string.app_name)
    }
}

fun Context.hasRecordings(): Boolean {
    val recordingsFolder = config.saveRecordingsFolder
    return if (isRPlus()) {
        folderDocument(recordingsFolder)
            ?.listFiles()
            ?.any { it.isAudioRecording() }
            ?: false
    } else {
        File(recordingsFolder)
            .listFiles()
            ?.any { it.isAudioFast() }
            ?: false
    }
}

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    return if (isRPlus()) {
        val recordings = arrayListOf<Recording>()
        recordings.addAll(getRecordings(trashed))
        if (trashed) {
            // Return recordings trashed using MediaStore, this won't be needed in the future
            @Suppress("DEPRECATION")
            recordings.addAll(getMediaStoreTrashedRecordings())
        }

        recordings
    } else {
        getLegacyRecordings(trashed)
    }
}

private fun Context.getRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) trashFolder else config.saveRecordingsFolder
    val files = folderDocument(folder)?.listFiles() ?: return recordings
    files.forEach { file ->
        if (file.isAudioRecording()) {
            recordings.add(
                readRecordingFromFile(file)
            )
        }
    }

    return recordings
}

@Deprecated(
    message = "Use getRecordings instead. This method is only here for backward compatibility.",
    replaceWith = ReplaceWith("getRecordings(trashed = true)")
)
private fun Context.getMediaStoreTrashedRecordings(): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = config.saveRecordingsFolder
    val documentFiles = folderDocument(folder)?.listFiles() ?: return recordings
    documentFiles.forEach { file ->
        if (file.isTrashedMediaStoreRecording()) {
            val recording = readRecordingFromFile(file)
            recordings.add(
                recording.copy(
                    title = "^\\.trashed-\\d+-".toRegex().replace(file.name!!, "")
                )
            )
        }
    }

    return recordings
}

private fun Context.getLegacyRecordings(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = File(folder).listFiles() ?: return recordings

    files.filter { it.isAudioFast() }.forEach {
        val id = it.hashCode()
        val title = it.name
        val path = it.absolutePath
        val timestamp = it.lastModified()
        val duration = getDuration(it.absolutePath) ?: 0
        val size = it.length().toInt()
        recordings.add(
            Recording(
                id = id,
                title = title,
                path = path,
                timestamp = timestamp,
                duration = duration,
                size = size
            )
        )
    }
    return recordings
}

private fun Context.readRecordingFromFile(file: DocumentFile): Recording {
    val id = file.hashCode()
    val title = file.name!!
    val path = file.uri.toString()
    val timestamp = file.lastModified()
    val duration = getDurationFromUri(file.uri)
    val size = file.length().toInt()
    return Recording(
        id = id,
        title = title,
        path = path,
        timestamp = timestamp,
        duration = duration.toInt(),
        size = size
    )
}

private fun Context.getDurationFromUri(uri: Uri): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(this, uri)
        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
        (time.toLong() / 1000.toDouble()).roundToLong()
    } catch (e: Exception) {
        0L
    }
}

/** Creates the file [path] in the recordings folder (or another granted one); null without access. */
fun Context.createDocumentFile(path: String): Uri? = makeFile(path)

// move to commons in the future
fun Context.getFormattedFilename(): String {
    val pattern = config.filenamePattern
    val calendar = Calendar.getInstance()

    val year = calendar.get(Calendar.YEAR).toString()
    val month = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1)
    val day = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
    val hour = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
    val minute = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE))
    val second = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.SECOND))

    return pattern
        .replace("%Y", year, false)
        .replace("%M", month, false)
        .replace("%D", day, false)
        .replace("%h", hour, false)
        .replace("%m", minute, false)
        .replace("%s", second, false)
}
