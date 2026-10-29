package app.thirdear

import app.thirdear.whitenoise.WhiteNoise
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Backups carry the settings and never the White Noise secrets or working files. */
class BackupRulesTest {

    private fun rules(name: String): List<Triple<String, String, String>> {
        val f = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableListOf<Triple<String, String, String>>()
        for (tag in listOf("include", "exclude")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val section = (e.parentNode as Element).tagName
                out += Triple("$section/$tag", e.getAttribute("domain"), e.getAttribute("path"))
            }
        }
        return out
    }

    @Test
    fun `cloud backup and device transfer include preferences and exclude the white noise secrets`() {
        val r = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue(Triple("$section/include", "sharedpref", ".") in r, section)
            assertTrue(Triple("$section/exclude", "sharedpref", "marmot_secrets.xml") in r, section)
            // nothing else is included: no files (recordings in progress, working copies, cached messages)
            assertEquals(1, r.count { it.first == "$section/include" }, section)
        }
    }

    @Test
    fun `full backup content matches`() {
        val r = rules("backup_rules.xml")
        assertEquals(
            listOf(Triple("full-backup-content/include", "sharedpref", "."), Triple("full-backup-content/exclude", "sharedpref", "marmot_secrets.xml")),
            r,
        )
    }

    @Test
    fun `a restored white noise setting without its identity is noticed`() {
        assertTrue(WhiteNoise.restoredWithoutIdentity(enabled = true, localState = false))
        assertFalse(WhiteNoise.restoredWithoutIdentity(enabled = true, localState = true))
        assertFalse(WhiteNoise.restoredWithoutIdentity(enabled = false, localState = false))
    }
}
