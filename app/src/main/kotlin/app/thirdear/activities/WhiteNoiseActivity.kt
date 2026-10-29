package app.thirdear.activities

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.views.MyTextView
import app.thirdear.R
import app.thirdear.databinding.ActivityWhiteNoiseBinding
import app.thirdear.databinding.DialogGroupSettingsBinding
import app.thirdear.extensions.config
import app.thirdear.whitenoise.GroupSettings
import app.thirdear.whitenoise.GroupSettingsStore
import app.thirdear.whitenoise.MarmotHost
import app.thirdear.whitenoise.ReadMode
import app.thirdear.whitenoise.ReplyMode
import app.thirdear.whitenoise.WhiteNoiseService

/**
 * Third Ear's White Noise identity: turn it on (an identity is created the first time), show and
 * copy the npub (QR), accept or decline group invites, per-group reply settings, a test group,
 * and the key backup (plain nsec, as in I Hear You).
 */
class WhiteNoiseActivity : SimpleActivity() {

    private lateinit var binding: ActivityWhiteNoiseBinding
    private var refresher: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhiteNoiseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.wnScrollview))
        setupMaterialScrollListener(binding.wnScrollview, binding.wnAppbar)

        binding.wnEnabled.isChecked = config.whiteNoiseEnabled
        binding.wnEnabledHolder.setOnClickListener {
            if (!MarmotHost.supported()) {
                toast(R.string.te_wn_unsupported)
                return@setOnClickListener
            }
            binding.wnEnabled.toggle()
            config.whiteNoiseEnabled = binding.wnEnabled.isChecked
            if (config.whiteNoiseEnabled) {
                WhiteNoiseService.start(this)
                lifecycleScope.launch { MarmotHost.start(this@WhiteNoiseActivity) }
            } else {
                WhiteNoiseService.stop(this)
            }
            render()
        }
        binding.wnCopyNpub.setOnClickListener { MarmotHost.npub.value?.let { copy(it, sensitive = false) } }
        binding.wnShareNpub.setOnClickListener {
            MarmotHost.npub.value?.let { n ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n), null))
            }
        }
        binding.wnCreateGroup.setOnClickListener { createGroup() }
        binding.wnMessages.setOnClickListener { startActivity(Intent(this, MessagesActivity::class.java)) }
        binding.wnShowNsec.setOnClickListener { showNsec() }
        binding.wnImportNsec.setOnClickListener { importNsec() }

        lifecycleScope.launch {
            combine(MarmotHost.phase, MarmotHost.npub, MarmotHost.groups, MarmotHost.error) { _, _, _, _ -> }.collect { render() }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.wnAppbar, NavigationIcon.Arrow)
        if (config.whiteNoiseEnabled && MarmotHost.phase.value == MarmotHost.Phase.OFF) {
            WhiteNoiseService.start(this)
            lifecycleScope.launch { MarmotHost.start(this@WhiteNoiseActivity) }
        }
        // invites arrive through relays; look again while the screen is open
        refresher = lifecycleScope.launch {
            while (isActive) {
                MarmotHost.refreshGroups()
                delay(15_000)
            }
        }
        render()
    }

    override fun onPause() {
        refresher?.cancel()
        super.onPause()
    }

    private fun render() {
        val on = config.whiteNoiseEnabled
        val ready = MarmotHost.phase.value == MarmotHost.Phase.READY
        binding.wnStatus.text = when {
            !MarmotHost.supported() -> getString(R.string.te_wn_unsupported)
            !on -> getString(R.string.te_wn_intro)
            MarmotHost.phase.value == MarmotHost.Phase.STARTING -> getString(R.string.te_wn_starting)
            MarmotHost.phase.value == MarmotHost.Phase.FAILED -> getString(R.string.te_wn_failed, MarmotHost.error.value ?: "?")
            else -> getString(R.string.te_wn_ready)
        }
        listOf(binding.wnIdentity, binding.wnInvitesLabel, binding.wnInvites, binding.wnGroupsLabel, binding.wnGroups,
            binding.wnCreateGroup, binding.wnMessages, binding.wnBackupLabel, binding.wnShowNsec, binding.wnImportNsec,
        ).forEach { it.beVisibleIf(on && ready) }
        binding.wnMessages.beVisibleIf(on)
        val npub = MarmotHost.npub.value
        binding.wnNpub.text = npub
        if (npub != null && binding.wnQr.tag != npub) {
            binding.wnQr.setImageBitmap(qr(npub))
            binding.wnQr.tag = npub
        }

        val groups = MarmotHost.groups.value
        val invites = groups.filter { it.pending }
        binding.wnInvites.removeAllViews()
        binding.wnInvitesLabel.beVisibleIf(on && ready && invites.isNotEmpty())
        for (g in invites) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad(), pad() / 2, pad(), 0)
            }
            row.addView(MyTextView(this).apply { text = g.name.ifBlank { g.id.take(12) } })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(textButton(R.string.te_wn_accept) { act { MarmotHost.accept(g.id) } })
            buttons.addView(textButton(R.string.te_wn_decline) { act { MarmotHost.decline(g.id) } })
            row.addView(buttons)
            binding.wnInvites.addView(row)
        }

        val joined = groups.filter { it.member && !it.pending }
        binding.wnGroups.removeAllViews()
        if (joined.isEmpty()) {
            binding.wnGroups.addView(MyTextView(this).apply {
                text = getString(R.string.te_no_groups)
                setPadding(pad(), pad() / 2, pad(), pad() / 2)
            })
        }
        val store = GroupSettingsStore(this)
        for (g in joined) {
            val s = store.get(g.id)
            binding.wnGroups.addView(MyTextView(this).apply {
                text = g.name + "\n" + summary(s)
                setPadding(pad(), pad() / 2, pad(), pad() / 2)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { editGroup(g.id, g.name) }
            })
        }
        updateTextColors(binding.wnScrollview)
        listOf(binding.wnInvitesLabel, binding.wnGroupsLabel, binding.wnBackupLabel).forEach { it.setTextColor(getProperPrimaryColor()) }
        listOf(binding.wnCopyNpub, binding.wnShareNpub).forEach { it.setTextColor(getProperPrimaryColor()) }
    }

    private fun summary(s: GroupSettings): String {
        val replies = getString(
            when (s.replies) {
                ReplyMode.OFF -> R.string.te_replies_off
                ReplyMode.VOICE -> R.string.te_replies_voice
                ReplyMode.VOICE_AND_TEXT -> R.string.te_replies_voice_text
            },
        )
        if (s.replies == ReplyMode.OFF) return replies
        val read = getString(if (s.read == ReadMode.AUTO) R.string.te_reading_auto else R.string.te_reading_ask)
        return "$replies · $read"
    }

    private fun editGroup(id: String, name: String) {
        val store = GroupSettingsStore(this)
        val s = store.get(id)
        val b = DialogGroupSettingsBinding.inflate(layoutInflater)
        b.groupReplies.check(
            when (s.replies) {
                ReplyMode.OFF -> R.id.replies_off
                ReplyMode.VOICE -> R.id.replies_voice
                ReplyMode.VOICE_AND_TEXT -> R.id.replies_voice_text
            },
        )
        b.groupReading.check(if (s.read == ReadMode.AUTO) R.id.reading_auto else R.id.reading_ask)
        b.groupIgnore.setText(s.ignore.joinToString("\n"))
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                store.put(
                    id,
                    GroupSettings(
                        replies = when (b.groupReplies.checkedRadioButtonId) {
                            R.id.replies_off -> ReplyMode.OFF
                            R.id.replies_voice -> ReplyMode.VOICE
                            else -> ReplyMode.VOICE_AND_TEXT
                        },
                        read = if (b.groupReading.checkedRadioButtonId == R.id.reading_auto) ReadMode.AUTO else ReadMode.ASK,
                        ignore = GroupSettings.parseIgnore(b.groupIgnore.text?.toString().orEmpty()),
                    ),
                )
                render()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply { setupDialogStuff(b.root, this, titleText = name) { updateTextColors(b.root) } }
    }

    private fun createGroup() {
        val input = EditText(this).apply { setText(R.string.te_wn_test_group_name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.te_wn_create_group)
            .setMessage(R.string.te_wn_create_group_desc)
            .setView(input)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.te_wn_test_group_name) }
                act { MarmotHost.createGroup(name) }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun showNsec() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.te_wn_show_nsec)
            .setMessage(R.string.te_wn_nsec_warning)
            .setPositiveButton(R.string.te_wn_reveal) { _, _ ->
                val nsec = runCatching { MarmotHost.revealNsec() }.getOrElse {
                    toast(it.message ?: "?")
                    return@setPositiveButton
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.te_wn_show_nsec)
                    .setMessage(nsec)
                    .setPositiveButton(R.string.te_wn_copy) { _, _ -> copy(nsec, sensitive = true) }
                    .setNegativeButton(org.fossify.commons.R.string.cancel, null)
                    .show()
                    .findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun importNsec() {
        val input = EditText(this).apply {
            hint = "nsec1…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.te_wn_import_nsec)
            .setMessage(R.string.te_wn_import_warning)
            .setView(input)
            .setPositiveButton(R.string.te_wn_import, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text.toString().trim()
            if (!key.startsWith("nsec1")) {
                toast(R.string.te_wn_not_nsec)
                return@setOnClickListener
            }
            dialog.dismiss()
            act { MarmotHost.importNsec(this@WhiteNoiseActivity, key) }
        }
    }

    private fun act(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }.onFailure { toast(it.message ?: it.javaClass.simpleName) }
            render()
        }
    }

    private fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("Third Ear", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast(R.string.te_wn_copied)
    }

    private fun textButton(label: Int, onClick: () -> Unit) = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(label)
        setTextColor(getProperPrimaryColor())
        setOnClickListener { onClick() }
    }

    private fun pad() = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)

    private fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
