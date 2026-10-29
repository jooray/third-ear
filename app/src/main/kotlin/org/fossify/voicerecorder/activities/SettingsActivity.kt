package org.fossify.voicerecorder.activities

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.fossify.voicerecorder.actions.ActionStore
import org.fossify.voicerecorder.extensions.recordActions
import org.fossify.voicerecorder.pipeline.MediaPauser
import org.fossify.voicerecorder.pipeline.Transcriber
import org.fossify.voicerecorder.tiles.ActionTileService
import android.media.MediaRecorder
import android.os.Bundle
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.IS_CUSTOMIZING_COLORS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.helpers.sumByInt
import org.fossify.commons.models.RadioItem
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivitySettingsBinding
import org.fossify.voicerecorder.dialogs.FilenamePatternDialog
import org.fossify.voicerecorder.dialogs.MoveRecordingsDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteTrashedRecordings
import org.fossify.voicerecorder.extensions.getAllRecordings
import org.fossify.voicerecorder.extensions.hasRecordings
import org.fossify.voicerecorder.extensions.launchFolderPicker
import org.fossify.voicerecorder.helpers.BITRATES
import org.fossify.voicerecorder.helpers.DEFAULT_BITRATE
import org.fossify.voicerecorder.helpers.DEFAULT_SAMPLING_RATE
import org.fossify.voicerecorder.helpers.EXTENSION_M4A
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.EXTENSION_OGG
import org.fossify.voicerecorder.helpers.SAMPLING_RATES
import org.fossify.voicerecorder.helpers.SAMPLING_RATE_BITRATE_LIMITS
import org.fossify.voicerecorder.models.Events
import org.greenrobot.eventbus.EventBus
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private var recycleBinContentSize = 0
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeWidgetColors()
        setupUseEnglish()
        setupLanguage()
        setupChangeDateTimeFormat()
        setupSaveRecordingsFolder()
        setupFilenamePattern()
        setupExtension()
        setupBitrate()
        setupSamplingRate()
        setupMicrophoneMode()
        setupRecordAfterLaunch()
        setupKeepScreenOn()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        setupThirdEar()
        updateTextColors(binding.settingsNestedScrollview)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsThirdEarLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsRecordingSectionLabel,
            binding.settingsAudioSectionLabel,
            binding.settingsRecycleBinLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            openColorCustomization()
        }
    }

    private fun setupCustomizeWidgetColors() {
        binding.settingsWidgetColorCustomizationHolder.setOnClickListener {
            Intent(this, WidgetRecordDisplayConfigureActivity::class.java).apply {
                putExtra(IS_CUSTOMIZING_COLORS, true)
                startActivity(this)
            }
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            binding.settingsLanguageHolder.beVisible()
            binding.settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            binding.settingsLanguageHolder.beGone()
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupSaveRecordingsFolder() {
        binding.settingsSaveRecordingsLabel.text =
            getString(R.string.save_recordings_in)
        binding.settingsSaveRecordings.text = humanizePath(config.saveRecordingsFolder)
        binding.settingsSaveRecordingsHolder.setOnClickListener {
            val currentFolder = config.saveRecordingsFolder
            launchFolderPicker(currentFolder) { newFolder ->
                if (!newFolder.isNullOrEmpty()) {
                    ensureBackgroundThread {
                        val hasRecordings = hasRecordings()
                        runOnUiThread {
                            if (newFolder != currentFolder && hasRecordings) {
                                MoveRecordingsDialog(
                                    activity = this,
                                    previousFolder = currentFolder,
                                    newFolder = newFolder
                                ) {
                                    config.saveRecordingsFolder = newFolder
                                    binding.settingsSaveRecordings.text =
                                        humanizePath(config.saveRecordingsFolder)
                                }
                            } else {
                                config.saveRecordingsFolder = newFolder
                                binding.settingsSaveRecordings.text =
                                    humanizePath(config.saveRecordingsFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupFilenamePattern() {
        binding.settingsFilenamePattern.text = config.filenamePattern
        binding.settingsFilenamePatternHolder.setOnClickListener {
            FilenamePatternDialog(this) { newPattern ->
                binding.settingsFilenamePattern.text = newPattern
            }
        }
    }

    private fun setupExtension() {
        binding.settingsExtension.text = config.getExtensionText()
        binding.settingsExtensionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(EXTENSION_M4A, getString(R.string.m4a)),
                RadioItem(EXTENSION_MP3, getString(R.string.mp3_experimental))
            )

            if (isQPlus()) {
                items.add(RadioItem(EXTENSION_OGG, getString(R.string.ogg_opus)))
            }

            RadioGroupDialog(this@SettingsActivity, items, config.extension) {
                config.extension = it as Int
                binding.settingsExtension.text = config.getExtensionText()
                adjustBitrate()
                adjustSamplingRate()
            }
        }
    }

    private fun setupBitrate() {
        binding.settingsBitrate.text = getBitrateText(config.bitrate)
        binding.settingsBitrateHolder.setOnClickListener {
            val items = BITRATES[config.extension]!!
                .map { RadioItem(it, getBitrateText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.bitrate) {
                config.bitrate = it as Int
                binding.settingsBitrate.text = getBitrateText(config.bitrate)
                adjustSamplingRate()
            }
        }
    }

    private fun getBitrateText(value: Int): String {
        return getString(R.string.bitrate_value).format(value / 1000)
    }

    private fun adjustBitrate() {
        val availableBitrates = BITRATES[config.extension]!!
        if (!availableBitrates.contains(config.bitrate)) {
            val currentBitrate = config.bitrate
            val closestBitrate = availableBitrates.minByOrNull { abs(it - currentBitrate) }
                ?: DEFAULT_BITRATE

            config.bitrate = closestBitrate
            binding.settingsBitrate.text = getBitrateText(config.bitrate)
        }
    }

    private fun setupSamplingRate() {
        binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
        binding.settingsSamplingRateHolder.setOnClickListener {
            val items = getSamplingRatesArray()
                .map { RadioItem(it, getSamplingRateText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.samplingRate) {
                config.samplingRate = it as Int
                binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
            }
        }
    }

    private fun getSamplingRateText(value: Int): String {
        return getString(R.string.sampling_rate_value).format(value)
    }

    private fun getSamplingRatesArray(): ArrayList<Int> {
        val baseRates = SAMPLING_RATES[config.extension]!!
        val limits = SAMPLING_RATE_BITRATE_LIMITS[config.extension]!!
        val filteredRates = baseRates.filter {
            config.bitrate in limits[it]!![0]..limits[it]!![1]
        } as ArrayList
        return filteredRates
    }

    private fun adjustSamplingRate() {
        val availableSamplingRates = getSamplingRatesArray()
        if (!availableSamplingRates.contains(config.samplingRate)) {
            if (availableSamplingRates.contains(DEFAULT_SAMPLING_RATE)) {
                config.samplingRate = DEFAULT_SAMPLING_RATE
            } else {
                config.samplingRate = availableSamplingRates.last()
            }
            binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
        }
    }

    private fun setupRecordAfterLaunch() {
        binding.settingsRecordAfterLaunch.isChecked = config.recordAfterLaunch
        binding.settingsRecordAfterLaunchHolder.setOnClickListener {
            binding.settingsRecordAfterLaunch.toggle()
            config.recordAfterLaunch = binding.settingsRecordAfterLaunch.isChecked
        }
    }

    private fun setupKeepScreenOn() {
        binding.settingsKeepScreenOn.isChecked = config.keepScreenOn
        binding.settingsKeepScreenOnHolder.setOnClickListener {
            binding.settingsKeepScreenOn.toggle()
            config.keepScreenOn = binding.settingsKeepScreenOn.isChecked
        }
    }

    private fun setupUseRecycleBin() {
        updateRecycleBinButtons()
        binding.settingsUseRecycleBin.isChecked = config.useRecycleBin
        binding.settingsUseRecycleBinHolder.setOnClickListener {
            binding.settingsUseRecycleBin.toggle()
            config.useRecycleBin = binding.settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() {
        binding.settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() {
        ensureBackgroundThread {
            try {
                recycleBinContentSize = getAllRecordings(trashed = true).sumByInt { it.size }
            } catch (_: Exception) {
            }

            runOnUiThread {
                binding.settingsEmptyRecycleBinSize.text = recycleBinContentSize.formatSize()
            }
        }

        binding.settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinContentSize == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this,
                    message = "",
                    messageId = org.fossify.commons.R.string.empty_recycle_bin_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        deleteTrashedRecordings()
                        runOnUiThread {
                            recycleBinContentSize = 0
                            binding.settingsEmptyRecycleBinSize.text = 0.formatSize()
                            EventBus.getDefault().post(Events.RecordingTrashUpdated())
                        }
                    }
                }
            }
        }
    }

    private fun setupMicrophoneMode() {
        binding.settingsMicrophoneMode.text = config.getMicrophoneModeText(config.microphoneMode)
        binding.settingsMicrophoneModeHolder.setOnClickListener {
            if (config.wasMicModeWarningShown) {
                showMicrophoneModeDialog()
            } else {
                ConfirmationDialog(
                    activity = this,
                    dialogTitle = getString(R.string.microphone_mode),
                    message = getString(R.string.change_microphone_mode_confirmation),
                    negative = 0,
                    positive = org.fossify.commons.R.string.ok
                ) {
                    config.wasMicModeWarningShown = true
                    showMicrophoneModeDialog()
                }
            }
        }
    }

    private fun showMicrophoneModeDialog() {
        val items = getMediaRecorderAudioSources()
            .map { microphoneMode ->
                RadioItem(
                    id = microphoneMode,
                    title = config.getMicrophoneModeText(microphoneMode)
                )
            } as ArrayList

        RadioGroupDialog(
            activity = this@SettingsActivity,
            items = items,
            checkedItemId = config.microphoneMode
        ) {
            config.microphoneMode = it as Int
            binding.settingsMicrophoneMode.text = config.getMicrophoneModeText(config.microphoneMode)
        }
    }

    private fun getMediaRecorderAudioSources(): List<Int> {
        return buildList {
            add(MediaRecorder.AudioSource.DEFAULT)
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)

            if (isQPlus()) {
                add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            }

            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.UNPROCESSED)
        }
    }

    // ------------------------------------------------------------------ Third Ear

    private fun setupThirdEar() {
        val actions = recordActions()
        binding.settingsActions.text = actions.joinToString(", ") { it.name }
        binding.settingsActionsHolder.setOnClickListener { startActivity(Intent(this, ActionsActivity::class.java)) }

        binding.settingsPreferBluetooth.isChecked = config.preferBluetooth
        binding.settingsPreferBluetoothHolder.setOnClickListener {
            binding.settingsPreferBluetooth.toggle()
            config.preferBluetooth = binding.settingsPreferBluetooth.isChecked
            if (config.preferBluetooth &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 4711)
            }
        }

        binding.settingsSpokenCues.isChecked = config.spokenCues
        binding.settingsSpokenCuesHolder.setOnClickListener {
            binding.settingsSpokenCues.toggle()
            config.spokenCues = binding.settingsSpokenCues.isChecked
        }

        binding.settingsPauseMedia.isChecked = config.pauseMedia && MediaPauser.hasAccess(this)
        binding.settingsPauseMediaHolder.setOnClickListener {
            binding.settingsPauseMedia.toggle()
            config.pauseMedia = binding.settingsPauseMedia.isChecked
            if (config.pauseMedia && !MediaPauser.hasAccess(this)) {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }

        val transcriber = Transcriber(this)
        val providers = transcriber.providers()
        val current = transcriber.resolve(config.transcriber)
        binding.settingsTranscriber.text = current?.let { providerLabel(transcriber, it) } ?: getString(R.string.te_setting_transcriber_none)
        binding.settingsTranscriberHolder.setOnClickListener {
            if (providers.isEmpty()) return@setOnClickListener
            val items = providers.mapIndexed { i, c -> RadioItem(i, providerLabel(transcriber, c)) }
            RadioGroupDialog(this, ArrayList(items), providers.indexOf(current)) {
                config.transcriber = providers[it as Int].flattenToString()
                setupThirdEar()
            }
        }

        binding.settingsTranscriptFiles.isChecked = config.saveTranscriptFiles
        binding.settingsTranscriptFilesHolder.setOnClickListener {
            binding.settingsTranscriptFiles.toggle()
            config.saveTranscriptFiles = binding.settingsTranscriptFiles.isChecked
        }

        val store = ActionStore(this)
        listOf(
            Triple(1, binding.settingsTile1, binding.settingsTile1Holder),
            Triple(2, binding.settingsTile2, binding.settingsTile2Holder),
            Triple(3, binding.settingsTile3, binding.settingsTile3Holder),
        ).forEach { (slot, value, holder) ->
            value.text = store.tileAction(slot)?.name ?: getString(R.string.te_tile_no_action)
            holder.setOnClickListener {
                pickAction(store.tileAction(slot)?.id) { id ->
                    store.setTile(slot, id)
                    ActionTileService.refreshAll(this)
                    setupThirdEar()
                }
            }
        }

        binding.settingsWidgetAction.text = store.defaultAction()?.name
        binding.settingsWidgetActionHolder.setOnClickListener {
            pickAction(store.defaultAction()?.id) { id ->
                store.widgetActionId = id
                setupThirdEar()
            }
        }

        binding.settingsWhiteNoise.text = getString(R.string.te_setting_white_noise_desc)
        binding.settingsWhiteNoiseHolder.setOnClickListener { startActivity(Intent(this, WhiteNoiseActivity::class.java)) }
        binding.settingsAndroidAuto.text = getString(R.string.te_setting_android_auto_desc)
    }

    private fun providerLabel(transcriber: Transcriber, c: ComponentName): String {
        val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            .getOrDefault(c.packageName)
        return when (transcriber.contractFor(c)) {
            Transcriber.Contract.FILE -> getString(R.string.te_transcriber_file_api, name)
            Transcriber.Contract.CALLER_AUDIO -> getString(R.string.te_transcriber_caller_audio, name)
            null -> name
        }
    }

    private fun pickAction(currentId: String?, onPick: (String) -> Unit) {
        val actions = recordActions()
        val items = actions.mapIndexed { i, a -> RadioItem(i, a.name) }
        RadioGroupDialog(this, ArrayList(items), actions.indexOfFirst { it.id == currentId }) {
            onPick(actions[it as Int].id)
        }
    }
}
