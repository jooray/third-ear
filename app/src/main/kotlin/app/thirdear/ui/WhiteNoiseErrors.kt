package app.thirdear.ui

import android.content.Context
import app.thirdear.R
import dev.ipf.marmotkit.MarmotKitException

/**
 * A MarmotKit error in words. Many of its errors carry only the group id as their message
 * ("groupIdHex=…"), which means nothing to a person; those get a sentence, the rest their message
 * or, failing that, the error's name.
 */
fun Context.whiteNoiseError(t: Throwable): String = when (t) {
    is MarmotKitException.WouldRemoveLastAdmin, is MarmotKitException.AdminCannotSelfRemove -> getString(R.string.te_wn_err_last_admin)
    is MarmotKitException.LeaveAlreadyRequested -> getString(R.string.te_wn_leaving)
    is MarmotKitException.GroupCatchingUp -> getString(R.string.te_wn_err_catching_up)
    is MarmotKitException.GroupCommitUndelivered -> getString(R.string.te_wn_err_undelivered)
    is MarmotKitException.GroupRemoved -> getString(R.string.te_wn_removed)
    is MarmotKitException.GroupDisbanding -> getString(R.string.te_wn_err_disbanding)
    is MarmotKitException.NotGroupAdmin, is MarmotKitException.NotAdmin -> getString(R.string.te_wn_err_not_admin)
    is MarmotKitException.UnknownGroup -> getString(R.string.te_wn_err_unknown_group)
    // MarmotKit 0.18: one commit at a time; a change, a member's leave or a decline waits for the last one
    // (media encrypted before it cannot be sent until it settles)
    is MarmotKitException.GroupCommitPending, is MarmotKitException.MediaReferenceEpochUnsettled,
    is MarmotKitException.MediaReferenceStaleEpoch -> getString(R.string.te_wn_err_commit_pending)
    // 0.18 (was Publish): no relay of the group may be sent to; a retry fails the same way
    is MarmotKitException.NoUsableRelay -> getString(R.string.te_wn_err_no_usable_relay)
    else -> readableMessage(t)
}

/** The message unless it is only a key=value dump of ids; else the error's name. */
internal fun readableMessage(t: Throwable): String {
    val m = t.message?.trim().orEmpty()
    val idsOnly = m.isEmpty() || Regex("""^(\w+=\S*,?\s*)+$""").matches(m)
    return if (idsOnly) t.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1 $2") else m
}
