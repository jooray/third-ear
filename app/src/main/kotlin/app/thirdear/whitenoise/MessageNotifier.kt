package app.thirdear.whitenoise

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.thirdear.R
import app.thirdear.activities.MainActivity

/**
 * Incoming White Noise messages as MessagingStyle notifications, one per group, with Reply
 * (RemoteInput, sends text to the group) and Mark as read: the semantic actions Android Auto needs
 * to announce a message and offer to read it aloud and answer by voice, as it does for WhatsApp.
 * When the group's policy offers it, a "Read aloud" / "Play" action plays it through Third Ear.
 */
object MessageNotifier {
    const val CHANNEL = "white_noise_messages"
    const val KEY_REPLY = "reply_text"
    private const val NOTIFICATION_ID_BASE = 5000

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.te_channel_messages), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun notificationId(groupId: String) = NOTIFICATION_ID_BASE + (groupId.hashCode() and 0xFFFF)

    fun messageLine(context: Context, m: IncomingMessage): String = when {
        m.audio.isNotEmpty() && m.text.isNotBlank() -> context.getString(R.string.te_message_voice_with_text, m.text)
        m.audio.isNotEmpty() -> context.getString(R.string.te_message_voice)
        else -> m.text
    }

    /** Re-posts the group's notification with its unread messages; cancels it when none are left. */
    fun post(context: Context, groupId: String, offerPlay: Boolean, offerRead: Boolean, alert: Boolean) {
        createChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val unread = IncomingStore(context).unread(groupId).sortedBy { it.at }.takeLast(10)
        if (unread.isEmpty()) {
            nm.cancel(notificationId(groupId))
            return
        }
        val me = Person.Builder().setName(context.getString(R.string.te_me)).setKey("self").build()
        val groupName = unread.last().groupName
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(groupName)
            .setGroupConversation(true)
        for (m in unread) {
            val sender = Person.Builder().setName(m.senderName ?: m.sender.take(10)).setKey(m.sender).build()
            style.addMessage(messageLine(context, m), m.at, sender)
        }
        val code = notificationId(groupId)
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_send_vector_te, context.getString(R.string.te_reply),
            PendingIntent.getForegroundService(
                context, code,
                WhiteNoiseService.intent(context, WhiteNoiseService.ACTION_REPLY).putExtra(WhiteNoiseService.EXTRA_GROUP, groupId),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.te_reply)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
        val markRead = NotificationCompat.Action.Builder(
            R.drawable.ic_check_vector_te, context.getString(R.string.te_mark_read),
            PendingIntent.getForegroundService(
                context, code + 1,
                WhiteNoiseService.intent(context, WhiteNoiseService.ACTION_MARK_READ).putExtra(WhiteNoiseService.EXTRA_GROUP, groupId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val open = PendingIntent.getActivity(
            context, code, MainActivity.intent(context, MainActivity.OPEN_REPLIES), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_graphic_eq_vector)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(false)
            .addAction(reply)
            .addAction(markRead)
            .addInvisibleAction(markRead)
        if (offerPlay || offerRead) {
            b.addAction(
                0,
                context.getString(if (offerPlay) R.string.te_play else R.string.te_read_aloud),
                PendingIntent.getForegroundService(
                    context, code + 2,
                    WhiteNoiseService.intent(context, WhiteNoiseService.ACTION_READ_GROUP).putExtra(WhiteNoiseService.EXTRA_GROUP, groupId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        nm.notify(code, b.build())
    }

    fun cancel(context: Context, groupId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(groupId))
    }
}
