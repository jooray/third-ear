package app.thirdear.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.thirdear.R

/**
 * The Jack Field: a switchboard face (bakelite black, or designation paper by day), lines in cord
 * colours, opal lamps. Flat: tonal steps and 1 dp rules, no shadows.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val face: Color,
    val plate: Color,
    val plateHi: Color,
    val recess: Color,
    val rule: Color,
    val ruleStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    /** The designation strip under each key: paper on the black face, black tape on the paper face. */
    val strip: Color,
    val stripInk: Color,
    val stripRule: Color,
    val lampOff: Color,
    /** Bezel of an unlit lamp; on paper it is lighter, so an unlit lamp never reads as lit. */
    val lampOffBezel: Color,
    val lampBezel: Color,
    val busy: Color,
    val reply: Color,
    val fault: Color,
    /** Text in fault colour (readable on face and plate). */
    val faultInk: Color,
    val busyInk: Color,
)

val BakeliteFace = Palette(
    dark = true,
    face = Color(0xFF16130F),
    plate = Color(0xFF211D18),
    plateHi = Color(0xFF302A23),
    recess = Color(0xFF0E0C09),
    rule = Color(0xFF3A342B),
    ruleStrong = Color(0xFF5A5246),
    ink = Color(0xFFEDE8DA),
    inkMuted = Color(0xFFADA595),
    strip = Color(0xFFE6E0CE),
    stripInk = Color(0xFF1E1A14),
    stripRule = Color(0xFFB4AD9B),
    lampOff = Color(0xFF2E2922),
    lampOffBezel = Color(0xFF4A4337),
    lampBezel = Color(0xFF4A4337),
    busy = Color(0xFFF2A33A),
    reply = Color(0xFFF7F4EA),
    fault = Color(0xFFE5483A),
    faultInk = Color(0xFFFF8A7A),
    busyInk = Color(0xFFF2B65E),
)

val PaperFace = Palette(
    dark = false,
    // the designation-strip paper itself: warm, the keys a tonal step lighter than the face
    face = Color(0xFFE3DDCD),
    plate = Color(0xFFF3EFE4),
    plateHi = Color(0xFFD8D1BE),
    recess = Color(0xFFD9D2C0),
    rule = Color(0xFFC3BBA7),
    ruleStrong = Color(0xFF8C8472),
    ink = Color(0xFF17140F),
    inkMuted = Color(0xFF575045),
    strip = Color(0xFF1E1A15),
    stripInk = Color(0xFFEDE8DA),
    stripRule = Color(0xFF4A4337),
    lampOff = Color(0xFFDDD6C5),
    lampOffBezel = Color(0xFFA39B88),
    lampBezel = Color(0xFF17140F),
    busy = Color(0xFFF2A33A),
    reply = Color(0xFFFFF1C2),
    fault = Color(0xFFD63A2B),
    faultInk = Color(0xFFA8281B),
    busyInk = Color(0xFF7E5200),
)

/** Cord colours: a line's identity on its key, tile, notification and car grid. */
object Cords {
    val all = listOf(
        Color(0xFFC8321E), // red
        Color(0xFF2F7D4F), // green
        Color(0xFF2C5AA0), // blue
        Color(0xFFD9B23A), // yellow
        Color(0xFFE6E0CE), // white
        Color(0xFF7A4FA0), // violet
        Color(0xFFD9762B), // orange
        Color(0xFF23807F), // teal
    )

    fun color(index: Int): Color = all[Math.floorMod(index, all.size)]

    /** Ink on a cord fill: black on the light cords, paper on the dark ones. */
    fun ink(index: Int): Color = if (color(index).luminance() > 0.4f) Color(0xFF17140F) else Color(0xFFF7F4EA)
}

private val Reading = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_semibold, FontWeight.SemiBold),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

private val Strip = FontFamily(
    Font(R.font.sairacondensed_medium, FontWeight.Medium),
    Font(R.font.sairacondensed_semibold, FontWeight.SemiBold),
    Font(R.font.sairacondensed_bold, FontWeight.Bold),
)

/** Type: Atkinson Hyperlegible Next for everything read, Saira Condensed caps for strips, codes and labels. */
object TeType {
    val timer = TextStyle(fontFamily = Strip, fontWeight = FontWeight.Bold, fontSize = 88.sp, lineHeight = 88.sp, fontFeatureSettings = "tnum")
    val wordmark = TextStyle(fontFamily = Strip, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 0.05.em)
    val screenTitle = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp)
    val keyName = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 23.sp)
    val title = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val smallStrong = small.copy(fontWeight = FontWeight.Bold)
    val button = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Strip, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.12.em)
    val code = TextStyle(fontFamily = Strip, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 0.1.em)
    val strip = TextStyle(fontFamily = Strip, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.06.em)
    /** Numbers and timestamps read as text: Atkinson with tabular figures. */
    val figures = TextStyle(fontFamily = Reading, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp, fontFeatureSettings = "tnum")
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
}

val LocalPalette = staticCompositionLocalOf { BakeliteFace }

object Te {
    val colors: Palette
        @Composable get() = LocalPalette.current
}

@Composable
fun ThirdEarTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) BakeliteFace else PaperFace
    // Material components (dialogs, fields, switches, menus) take the same face
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.ink, onPrimary = p.face, primaryContainer = p.plateHi, onPrimaryContainer = p.ink,
            secondary = p.inkMuted, onSecondary = p.face, secondaryContainer = p.plateHi, onSecondaryContainer = p.ink,
            tertiary = p.busy, onTertiary = p.face,
            background = p.face, onBackground = p.ink, surface = p.face, onSurface = p.ink,
            surfaceVariant = p.plate, onSurfaceVariant = p.inkMuted,
            surfaceContainerLowest = p.recess, surfaceContainerLow = p.plate, surfaceContainer = p.plate,
            surfaceContainerHigh = p.plate, surfaceContainerHighest = p.plateHi,
            surfaceBright = p.plateHi, surfaceDim = p.face,
            outline = p.ruleStrong, outlineVariant = p.rule, error = p.faultInk, onError = p.face,
            inverseSurface = p.strip, inverseOnSurface = p.stripInk, scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.plate, primaryContainer = p.plateHi, onPrimaryContainer = p.ink,
            secondary = p.inkMuted, onSecondary = p.plate, secondaryContainer = p.plateHi, onSecondaryContainer = p.ink,
            tertiary = p.busyInk, onTertiary = p.plate,
            background = p.face, onBackground = p.ink, surface = p.face, onSurface = p.ink,
            surfaceVariant = p.plate, onSurfaceVariant = p.inkMuted,
            surfaceContainerLowest = p.plate, surfaceContainerLow = p.plate, surfaceContainer = p.plate,
            surfaceContainerHigh = p.plate, surfaceContainerHighest = p.plateHi,
            surfaceBright = p.plate, surfaceDim = p.face,
            outline = p.ruleStrong, outlineVariant = p.rule, error = p.faultInk, onError = p.plate,
            inverseSurface = p.strip, inverseOnSurface = p.stripInk, scrim = Color.Black,
        )
    }
    val type = Typography().copy(
        bodyLarge = TeType.body, bodyMedium = TeType.small, bodySmall = TeType.small,
        titleLarge = TeType.screenTitle, titleMedium = TeType.title, titleSmall = TeType.smallStrong,
        labelLarge = TeType.button, labelMedium = TeType.small, labelSmall = TeType.small,
        headlineSmall = TeType.screenTitle,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
