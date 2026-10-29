package app.thirdear.activities

import android.os.Bundle
import android.text.format.DateUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.views.MyTextView
import app.thirdear.R
import app.thirdear.databinding.ActivityMessagesBinding
import app.thirdear.databinding.ItemMessageBinding
import app.thirdear.whitenoise.IncomingMessage
import app.thirdear.whitenoise.IncomingStore
import app.thirdear.whitenoise.MessageNotifier
import app.thirdear.whitenoise.WhiteNoise

/** Recent incoming White Noise messages, newest first, each with Play / Read aloud. */
class MessagesActivity : SimpleActivity() {

    private lateinit var binding: ActivityMessagesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.messagesScrollview))
        setupMaterialScrollListener(binding.messagesScrollview, binding.messagesAppbar)
        lifecycleScope.launch { IncomingStore.messages.collect { render(it) } }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.messagesAppbar, NavigationIcon.Arrow)
        lifecycleScope.launch { withContext(Dispatchers.IO) { IncomingStore(this@MessagesActivity).load() } }
    }

    private fun render(list: List<IncomingMessage>) {
        binding.messagesList.removeAllViews()
        if (list.isEmpty()) {
            val pad = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            binding.messagesList.addView(MyTextView(this).apply {
                setText(R.string.te_no_messages)
                setPadding(pad, pad, pad, pad)
            })
        }
        for (m in list) {
            val b = ItemMessageBinding.inflate(layoutInflater, binding.messagesList, false)
            val time = DateUtils.getRelativeTimeSpanString(m.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            var header = "${m.groupName} · ${m.senderName ?: m.sender.take(10)} · $time"
            if (m.ignoredBy != null) header += " · " + getString(R.string.te_ignored, m.ignoredBy)
            else if (!m.read) header += " · " + getString(R.string.te_unread)
            b.messageHeader.text = header
            b.messageText.text = MessageNotifier.messageLine(this, m)
            b.messagePlay.beVisibleIf(m.audio.isNotEmpty())
            b.messageRead.beVisibleIf(m.text.isNotBlank())
            b.messagePlay.setOnClickListener { play(m, audio = true, text = false) }
            b.messageRead.setOnClickListener { play(m, audio = false, text = true) }
            listOf(b.messagePlay, b.messageRead).forEach { it.setTextColor(getProperPrimaryColor()) }
            binding.messagesList.addView(b.root)
        }
        updateTextColors(binding.messagesScrollview)
    }

    private fun play(m: IncomingMessage, audio: Boolean, text: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            WhiteNoise.play(this@MessagesActivity, m, audio, text)
            runCatching { WhiteNoise.markRead(this@MessagesActivity, m.groupId, listOf(m.id)) }
        }
    }
}
