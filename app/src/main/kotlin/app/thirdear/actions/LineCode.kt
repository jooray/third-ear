package app.thirdear.actions

import java.text.Normalizer

/** The short code a line carries on its key: the user's, else made from the name. */
object LineCode {
    const val MAX = 4

    fun of(action: RecordAction): String = action.code?.trim()?.uppercase()?.take(MAX)?.ifEmpty { null } ?: fromName(action.name)

    /** "Ask Hermes" → "HER", "Family" → "FAM", "Meeting notes" → "MTN": letters only, accents dropped. */
    fun fromName(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val words = plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "—"
        // the last word names the line ("Ask Hermes", "Send to Family"); short leading verbs are skipped
        val word = words.last().takeIf { words.size > 1 && it.length >= 3 && words.first().length <= 4 } ?: words.first()
        val upper = word.uppercase()
        val head = upper.take(3)
        // "MEE" reads badly; a doubled vowel switches to the consonant skeleton ("MTN")
        if (head.zipWithNext().none { (a, b) -> a in VOWELS && b in VOWELS }) return head
        val skeleton = upper.first() + upper.drop(1).filter { it !in VOWELS }
        return if (skeleton.length >= 3) skeleton.take(3) else head
    }

    private const val VOWELS = "AEIOUY"
}
