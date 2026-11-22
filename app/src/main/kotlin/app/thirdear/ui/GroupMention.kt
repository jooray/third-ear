package app.thirdear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.thirdear.R
import app.thirdear.whitenoise.MarmotHost

/**
 * Who every message to a group mentions: nobody (the default) or one member, for agents that
 * act only when mentioned (#13, #16). [mention] is the member's pubkey hex and name.
 */
@Composable
fun MentionChoice(
    members: List<MarmotHost.Member>?,
    mention: Pair<String, String?>?,
    onPick: (Pair<String, String?>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Te.colors
    Column(modifier) {
        Label(stringResource(R.string.te_group_mention), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
        RadioRow(stringResource(R.string.te_group_mention_none), mention == null) { onPick(null) }
        val others = members.orEmpty().filter { !it.self }
        others.forEach { m -> RadioRow(m.label, mention?.first == m.pubkey) { onPick(m.pubkey to m.name) } }
        // a member picked earlier who is no longer listed (or the list is still loading) stays visible
        if (mention != null && others.none { it.pubkey == mention.first }) {
            RadioRow(mention.second ?: MarmotHost.Member.short(mention.first), true) {}
        }
        Text(stringResource(R.string.te_group_mention_note), style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(16.dp))
    }
}
