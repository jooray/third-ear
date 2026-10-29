package app.thirdear.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.compose.extensions.getActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import app.thirdear.R
import app.thirdear.actions.ActionStore
import app.thirdear.actions.RecordAction
import app.thirdear.audio.BluetoothMics
import app.thirdear.audio.Source
import app.thirdear.databinding.FragmentRecorderBinding
import app.thirdear.databinding.ItemActionButtonBinding
import app.thirdear.dialogs.SourceSwitchDialog
import app.thirdear.extensions.config
import app.thirdear.extensions.describe
import app.thirdear.extensions.ensureStoragePermission
import app.thirdear.extensions.inputLabel
import app.thirdear.extensions.openOsttPage
import app.thirdear.extensions.recordActions
import app.thirdear.extensions.setKeepScreenAwake
import app.thirdear.pipeline.Note
import app.thirdear.pipeline.NotePipeline
import app.thirdear.pipeline.NoteState
import app.thirdear.pipeline.NoteStep
import app.thirdear.pipeline.NoteStore
import app.thirdear.services.RecorderService

/**
 * The recorder tab: the configured actions as big buttons. Idle, a tap starts recording with that
 * action; while recording every button reads "Stop → name" with what it will do, and a tap stops
 * and runs that one, so the destination can still change at the stop. The source button shows the
 * microphone that really records and switches it (asking first).
 */
class RecorderFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment(context, attributeSet) {

    companion object {
        private const val BLUETOOTH_REQUEST = 4711
    }

    private lateinit var binding: FragmentRecorderBinding
    private var scope: CoroutineScope? = null
    private var actions: List<RecordAction> = emptyList()
    private var renderedKey: String? = null
    private var live: RecorderService.Companion.Live? = null
    private var lastNotes: List<Note> = emptyList()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecorderBinding.bind(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binding.recorderVisualizer.recreate()
        binding.toggleRecordingButton.setOnClickListener { RecorderService.togglePause(context) }
        binding.cancelRecordingButton.setOnClickListener { confirmDiscard() }
        binding.sourceButton.setOnClickListener { onSourceTapped() }
        binding.osttHint.setOnClickListener { context.openOsttPage() }
        binding.lastNoteRetry.setOnClickListener { lastNotes.firstOrNull()?.let { RecorderService.retry(context, it.id) } }
        binding.lastNoteCopy.setOnClickListener { lastNotes.firstOrNull()?.let { copyTranscript(it) } }
        binding.lastNoteTranscript.setOnClickListener { lastNotes.firstOrNull()?.let { showTranscript(it) } }
        val s = MainScope()
        scope = s
        s.launch { RecorderService.live.collect { render(it) } }
        s.launch { ActionStore.changes.collect { reloadActions() } }
        s.launch { NoteStore.notes.collect { renderLastNote(it) } }
        s.launch { withContext(Dispatchers.IO) { NoteStore(context).reload() } }
        setupColors()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    override fun onResume() {
        setupColors()
        reloadActions()
        renderSource(live)
    }

    override fun onDestroy() {
        scope?.cancel()
    }

    private fun setupColors() {
        val text = context.getProperTextColor()
        val primary = context.getProperPrimaryColor()
        binding.toggleRecordingButton.background.applyColorFilter(primary)
        binding.cancelRecordingButton.applyColorFilter(text)
        binding.recorderVisualizer.chunkColor = primary
        binding.recordingDuration.setTextColor(text)
        binding.actionHint.setTextColor(text)
        binding.sourceButton.setTextColor(text)
        binding.sourceButton.iconTint = ColorStateList.valueOf(primary)
        binding.sourceButton.strokeColor = ColorStateList.valueOf(primary)
        binding.lastNoteRetry.setTextColor(primary)
        binding.lastNoteCopy.setTextColor(primary)
        listOf(binding.lastNoteTitle, binding.lastNoteStatus, binding.lastNoteTranscript, binding.sourceStatus).forEach { it.setTextColor(text) }
        renderedKey = null
        renderActions()
    }

    private fun reloadActions() {
        actions = context.recordActions()
        // an action wants a transcript but no transcription app is installed: say where to get one
        val needsTranscriber = actions.any { app.thirdear.actions.ActionPlan.of(it).transcribe }
        binding.osttHint.beVisibleIf(needsTranscriber && !app.thirdear.pipeline.Transcriber(context).available(context.config.transcriber))
        binding.osttHint.setTextColor(context.getProperPrimaryColor())
        renderedKey = null
        renderActions()
    }

    // ------------------------------------------------------------------ rendering

    private fun render(l: RecorderService.Companion.Live?) {
        val wasRecording = live != null
        live = l
        val recording = l != null
        if (recording != wasRecording) {
            if (!recording) binding.recorderVisualizer.recreate()
            if (context.config.keepScreenOn) runCatching { context.getActivity().setKeepScreenAwake(recording) }
        }
        val status = l?.status
        binding.recordingDuration.text = if (status != null) (status.durationMs / 1000).toInt().getFormattedDuration() else null
        if (status != null && !status.paused && !status.starting) binding.recorderVisualizer.update(status.amplitude)
        binding.cancelRecordingButton.beVisibleIf(recording)
        binding.toggleRecordingButton.beVisibleIf(recording)
        val paused = status?.paused == true
        binding.toggleRecordingButton.setImageDrawable(
            resources.getColoredDrawableWithColor(
                if (paused) R.drawable.ic_start_recording_vector else R.drawable.ic_pause_recording_vector,
                context.getProperPrimaryColor().getContrastColor(),
            ),
        )
        binding.toggleRecordingButton.contentDescription = context.getString(if (paused) R.string.resume_recording else R.string.pause_recording)
        binding.actionHint.setText(if (recording) R.string.te_tap_to_stop else R.string.te_tap_to_start)
        renderSource(l)
        renderActions()
        if (recording != wasRecording) renderLastNote(NoteStore.notes.value)
    }

    private fun renderSource(l: RecorderService.Companion.Live?) {
        val status = l?.status
        val bluetoothNow: Boolean
        val label: String
        if (status != null) {
            label = context.inputLabel(status)
            bluetoothNow = status.source == Source.BLUETOOTH
        } else {
            bluetoothNow = context.config.preferBluetooth && BluetoothMics.available(context)
            label = if (bluetoothNow) {
                BluetoothMics.name(context)?.let { context.getString(R.string.te_input_bluetooth_named, it) } ?: context.getString(R.string.te_input_bluetooth)
            } else {
                context.getString(R.string.te_input_phone)
            }
        }
        binding.sourceButton.text = label
        binding.sourceButton.setIconResource(if (bluetoothNow) R.drawable.ic_bluetooth_vector else R.drawable.ic_smartphone_vector)
        binding.sourceButton.contentDescription =
            context.getString(if (bluetoothNow) R.string.te_source_desc_bluetooth else R.string.te_source_desc_phone)
        val note = when {
            status == null -> if (context.config.preferBluetooth && !bluetoothNow) context.getString(R.string.te_no_bluetooth_mic) else null
            status.silent && !status.paused -> context.getString(R.string.microphone_silent_text)
            status.fallbackReason != null && status.source == Source.PHONE -> context.getString(R.string.te_fallback_reason, status.fallbackReason)
            else -> null
        }
        binding.sourceStatus.text = note
        binding.sourceStatus.beVisibleIf(note != null)
    }

    private fun renderActions() {
        val l = live
        val key = actions.joinToString { it.toJson().toString() } + "|" + (l?.action?.id ?: "-")
        if (key == renderedKey) return
        renderedKey = key
        val holder = binding.actionsHolder
        holder.removeAllViews()
        val primary = context.getProperPrimaryColor()
        val text = context.getProperTextColor()
        val radius = resources.getDimension(org.fossify.commons.R.dimen.activity_margin)
        val stroke = (resources.displayMetrics.density * 2).toInt()
        for (action in actions) {
            val b = ItemActionButtonBinding.inflate(LayoutInflater.from(context), holder, false)
            val startedWithThis = l?.action?.id == action.id
            val filled = l == null || startedWithThis
            b.root.background = GradientDrawable().apply {
                cornerRadius = radius
                if (filled) {
                    setColor(primary)
                } else {
                    setColor(0)
                    setStroke(stroke, primary)
                }
            }
            val fg = if (filled) primary.getContrastColor() else text
            b.actionTitle.setTextColor(fg)
            b.actionDescription.setTextColor(fg)
            b.actionTitle.text = if (l == null) action.name else context.getString(R.string.te_stop_with, action.name)
            var desc = context.describe(action)
            if (startedWithThis) desc += " · " + context.getString(R.string.te_started_with)
            b.actionDescription.text = desc
            b.root.contentDescription = b.actionTitle.text.toString() + ". " + desc
            b.root.setOnClickListener { onActionTapped(action) }
            holder.addView(b.root)
        }
    }

    private fun renderLastNote(notes: List<Note>) {
        lastNotes = notes.filter { it.state != NoteState.RECORDING }
        val n = lastNotes.firstOrNull()
        binding.lastNoteCard.beVisibleIf(n != null && live == null)
        if (n == null) return
        val secs = n.durationMs / 1000
        binding.lastNoteTitle.text = context.getString(R.string.te_last_recording) + " · " + n.action.name + " · " +
            context.getString(R.string.te_recording_length, (secs / 60).toInt(), (secs % 60).toInt())
        val status = when (n.state) {
            NoteState.PROCESSING -> when (n.step) {
                NoteStep.TRANSCRIBING -> context.getString(R.string.te_step_transcribing)
                NoteStep.WAITING_FOR_TRANSCRIBER -> context.getString(R.string.te_step_waiting_transcriber)
                NoteStep.SENDING -> context.getString(R.string.te_step_sending, n.action.name)
                else -> context.getString(R.string.te_step_saving)
            }

            NoteState.FAILED -> context.getString(R.string.te_state_failed, n.error ?: "?")
            else -> n.savedName?.let { context.getString(R.string.te_state_saved_as, it) } ?: context.getString(R.string.te_state_done)
        } + (n.transcriptError?.let { "\n" + context.getString(R.string.te_transcript_failed, it) } ?: "")
        binding.lastNoteStatus.text = status
        val transcript = if (n.transcript) NoteStore(context).transcript(n.id) else null
        binding.lastNoteTranscript.text = transcript
        binding.lastNoteTranscript.beVisibleIf(transcript != null)
        binding.lastNoteCopy.beVisibleIf(transcript != null)
        binding.lastNoteRetry.beVisibleIf(n.state == NoteState.FAILED)
    }

    // ------------------------------------------------------------------ taps

    private fun onActionTapped(action: RecordAction) {
        if (live != null) {
            RecorderService.stop(context, action.id)
            return
        }
        val activity = context as? BaseSimpleActivity ?: return
        activity.ensureStoragePermission { storage ->
            if (!storage) {
                activity.toast(org.fossify.commons.R.string.no_storage_permissions)
                return@ensureStoragePermission
            }
            activity.handleNotificationPermission { granted ->
                if (!granted) {
                    PermissionRequiredDialog(
                        activity = activity,
                        textId = org.fossify.commons.R.string.allow_notifications_voice_recorder,
                        positiveActionCallback = { activity.openNotificationSettings() },
                    )
                    return@handleNotificationPermission
                }
                if (context.config.preferBluetooth &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
                }
                RecorderService.start(context, action.id)
            }
        }
    }

    private fun onSourceTapped() {
        val activity = context.getActivity()
        val l = live
        val current = l?.status?.source
            ?: if (context.config.preferBluetooth && BluetoothMics.available(context)) Source.BLUETOOTH else Source.PHONE
        if (current == Source.PHONE && !BluetoothMics.available(context)) {
            context.toast(R.string.te_no_bluetooth_mic)
            return
        }
        SourceSwitchDialog.show(activity, recording = l != null, current = current) { to ->
            if (live != null) RecorderService.switchSource(context, to) else renderSource(null)
        }
    }

    private fun confirmDiscard() {
        val activity = context as? BaseSimpleActivity ?: return
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.discard_recording_confirmation),
            dialogTitle = activity.getString(R.string.discard_recording),
        ) { RecorderService.discard(context) }
    }

    private fun copyTranscript(note: Note) {
        val text = NoteStore(context).transcript(note.id) ?: return
        NotePipeline(context).copyToClipboard(text)
        context.toast(R.string.te_transcript_copied)
    }

    private fun showTranscript(note: Note) {
        val text = NoteStore(context).transcript(note.id) ?: return
        MaterialAlertDialogBuilder(context.getActivity())
            .setTitle(R.string.te_transcript)
            .setMessage(text)
            .setPositiveButton(R.string.te_copy_transcript) { _, _ -> copyTranscript(note) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
