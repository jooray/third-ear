package app.thirdear.shortcuts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.thirdear.R
import app.thirdear.actions.LineCode
import app.thirdear.extensions.recordActions
import app.thirdear.ui.CodeCell
import app.thirdear.ui.Label
import app.thirdear.ui.Rule
import app.thirdear.ui.Te
import app.thirdear.ui.TeType
import app.thirdear.ui.ThirdEarTheme
import app.thirdear.ui.cordOf

/**
 * The "create shortcut" picker (ACTION_CREATE_SHORTCUT): launchers, automation and
 * button-mapping apps open it to bind a Third Ear shortcut. Pick a line (tap to record on it,
 * tap again to stop and send) or "Stop recording".
 */
class ShortcutPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_CREATE_SHORTCUT) {
            finish()
            return
        }
        val lines = recordActions()
        setContent {
            ThirdEarTheme {
                val p = Te.colors
                Column(
                    Modifier.fillMaxSize().background(p.face).systemBarsPadding().verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.te_shortcut_picker_title),
                        style = TeType.screenTitle,
                        color = p.ink,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                    )
                    Text(
                        stringResource(R.string.te_shortcut_picker_hint),
                        style = TeType.small,
                        color = p.inkMuted,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                    )
                    Label(stringResource(R.string.te_shortcut_picker_lines), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp))
                    Rule()
                    lines.forEachIndexed { i, a ->
                        PickRow(
                            lead = { CodeCell(LineCode.of(a), cordOf(a, i)) },
                            title = a.name,
                            subtitle = stringResource(R.string.te_shortcut_line_hint),
                            onClick = { done(LineShortcuts.lineInfo(this@ShortcutPickerActivity, a, i), LineShortcuts.lineBitmap(this@ShortcutPickerActivity, a, i, adaptive = false)) },
                        )
                        Rule(inset = 76.dp)
                    }
                    PickRow(
                        lead = { Box(Modifier.size(14.dp).background(p.fault)) },
                        title = stringResource(R.string.te_shortcut_stop),
                        subtitle = stringResource(R.string.te_shortcut_stop_hint),
                        onClick = { done(LineShortcuts.stopInfo(this@ShortcutPickerActivity), LineShortcuts.stopBitmap(this@ShortcutPickerActivity, adaptive = false)) },
                    )
                    Rule()
                }
            }
        }
    }

    private fun done(info: android.content.pm.ShortcutInfo, legacyIcon: android.graphics.Bitmap) {
        setResult(RESULT_OK, LineShortcuts.resultIntent(this, info, legacyIcon))
        finish()
    }
}

@Composable
private fun PickRow(lead: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit) {
    val p = Te.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(60.dp)) { lead() }
        Column(Modifier.weight(1f)) {
            Text(title, style = TeType.bodyStrong, color = p.ink)
            Text(subtitle, style = TeType.small, color = p.inkMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
