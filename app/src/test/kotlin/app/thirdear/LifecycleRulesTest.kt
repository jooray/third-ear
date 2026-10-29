package app.thirdear

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * A recording stops only when the user stops it (a button, a tile, the notification, the car) or
 * the microphone fails: never because the screen turned off, the phone locked, or an activity
 * paused, stopped or was destroyed. These checks keep lifecycle code from stopping it.
 */
class LifecycleRulesTest {

    private val root = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val sources = root.resolve("kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    private val manifest = root.resolve("AndroidManifest.xml").readText()

    private fun body(text: String, fn: String): String? {
        val i = text.indexOf("override fun $fn(")
        if (i < 0) return null
        var depth = 0
        var started = false
        for (j in text.indexOf('{', i) until text.length) {
            when (text[j]) {
                '{' -> { depth++; started = true }
                '}' -> { depth--; if (started && depth == 0) return text.substring(i, j + 1) }
            }
        }
        return null
    }

    @Test
    fun `nothing stops the recorder service from outside`() {
        val offenders = sources.filter { it.readText().contains("stopService(") }.map { it.name }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `activity and fragment lifecycle callbacks never stop, discard or pause a recording`() {
        val stopCalls = listOf("RecorderService.stop(", "RecorderService.discard(", "RecorderService.togglePause(", "ACTION_STOP", "ACTION_DISCARD")
        for (f in sources.filter { it.path.contains("activities") || it.path.contains("fragments") }) {
            val text = f.readText()
            for (fn in listOf("onPause", "onStop", "onDestroy", "onDetachedFromWindow", "onUserLeaveHint", "onTrimMemory")) {
                val b = body(text, fn) ?: continue
                for (c in stopCalls) assertFalse(b.contains(c), "${f.name}.$fn calls $c")
            }
        }
        assertFalse(sources.any { it.readText().contains("ACTION_SCREEN_OFF") }, "no screen-off handling may end a recording")
    }

    @Test
    fun `the recorder is a microphone foreground service that survives the task being removed`() {
        val svc = Regex("""<service\s+android:name="\.services\.RecorderService"[^>]*>""").find(manifest)!!.value
        assertTrue(svc.contains("microphone"), svc)
        assertFalse(svc.contains("stopWithTask=\"true\""), svc)
        val text = sources.first { it.name == "RecorderService.kt" }.readText()
        assertTrue(text.contains("PARTIAL_WAKE_LOCK"))
        assertTrue(text.contains("START_NOT_STICKY") || text.contains("START_STICKY"))
        assertFalse(text.contains("override fun onTaskRemoved"), "removing the task from recents must not end a recording")
    }

    @Test
    fun `only the bluetooth route releases the communication device`() {
        val users = sources.filter { it.readText().contains("clearCommunicationDevice") }.map { it.name }
        assertEquals(listOf("AndroidSources.kt"), users)
    }
}
