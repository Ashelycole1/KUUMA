package ug.kuuma.ai.runtime

import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier

/**
 * The phone's first opinion until the AI owner's lexicon and Knit model are in: a small reviewed
 * word list (`kuuma/ai/starter-lexicon.txt`) in English, Luganda and Swahili. It is built to be
 * careful in both directions, because Sentinel asks Sunflower whenever it is unsure:
 *
 *  - a harmful phrase ("i will kill you") or a hurtful word aimed at someone ("you idiot",
 *    "oli musilu", "wewe mjinga") is flagged with confidence;
 *  - a hurtful word not aimed at anyone ("this game is stupid"), or a word like "kill" that is
 *    as often a joke as a threat, is only a maybe: Sentinel asks Sunflower before anything
 *    reaches the parent;
 *  - a message in a local language with no known word is a maybe too, so Sunflower reads it;
 *  - plain English with no known word is clear.
 */
class StarterHarmClassifier(entries: List<Entry> = bundled()) : HarmClassifier {
    override val modelVersion = "starter-lexicon-0.1.0"

    enum class Kind { PHRASE, WORD, MAYBE }

    data class Entry(val category: HarmCategory, val severity: Severity, val kind: Kind, val term: String)

    private class Compiled(val entry: Entry, val regex: Regex)

    // Longest first: "i will kill you" before "kill".
    private val compiled = entries.sortedByDescending { it.term.length }.map {
        Compiled(
            it,
            Regex(
                "(?<![\\p{L}\\p{N}'])" + Regex.escape(it.term).replace(" ", "\\E\\s+\\Q") + "(?![\\p{L}\\p{N}])"
            )
        )
    }

    override fun classify(text: String, context: ClassifyContext): Verdict {
        val lower = text.lowercase().replace('’', '\'')
        val tokens = TOKEN.findAll(lower).map { it.value }.toList()
        var maybe: Entry? = null
        for (c in compiled) {
            val hit = c.regex.find(lower) ?: continue
            val sure = when (c.entry.kind) {
                Kind.PHRASE -> true
                Kind.WORD -> aimed(lower, hit.range, tokens.size)
                Kind.MAYBE -> false
            }
            if (sure) {
                val sure = if (c.entry.kind == Kind.PHRASE) PHRASE_CONFIDENCE else AIMED_CONFIDENCE
                return Verdict(true, c.entry.category, c.entry.severity, sure)
            }
            if (maybe == null) maybe = c.entry
        }
        maybe?.let { return Verdict(true, it.category, it.severity, MAYBE_CONFIDENCE) }
        val local = context.language != null && context.language != Language.EN
        return Verdict(false, confidence = if (local) LOCAL_UNSURE else CLEAR_CONFIDENCE)
    }

    /** Is the word aimed at a person: "you ...", "... gwe", "wewe ...", or a short name-calling message? */
    private fun aimed(lower: String, range: IntRange, tokenCount: Int): Boolean {
        if (tokenCount <= 1) return true // "idiot!" on its own
        val before = TOKEN.findAll(lower.substring(0, range.first)).map { it.value }.toList().takeLast(3)
        val after = TOKEN.findAll(lower.substring(range.last + 1)).map { it.value }.toList().take(2)
        return before.any { it in ADDRESS } || after.any { it in ADDRESS_AFTER }
    }

    companion object {
        const val PHRASE_CONFIDENCE = 0.95
        const val AIMED_CONFIDENCE = 0.8

        /** Below Sentinel's flag threshold (0.6) and above its floor (0.35): "ask Sunflower". */
        const val MAYBE_CONFIDENCE = 0.45

        /** Not harmful, but not sure either (Sentinel asks Sunflower below 0.6). */
        const val LOCAL_UNSURE = 0.5
        const val CLEAR_CONFIDENCE = 0.95

        private val TOKEN = Regex("[\\p{L}\\p{N}']+")

        /** Words that aim what follows at the reader, in English, Luganda and Swahili. */
        private val ADDRESS = setOf(
            "you", "u", "your", "ur", "you're", "youre", "yu", "ggwe", "gwe", "oli", "muli", "wewe", "uko"
        )
        private val ADDRESS_AFTER = setOf("gwe", "ggwe", "wewe", "we", "you", "u")
        private const val RESOURCE = "kuuma/ai/starter-lexicon.txt"

        /** `category severity kind | term`; `#` starts a comment. */
        fun parse(text: String): List<Entry> = text.lines().mapNotNull { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || '|' !in line) return@mapNotNull null
            val (head, term) = line.split('|', limit = 2).map { it.trim() }
            val parts = head.split(Regex("\\s+"))
            if (parts.size != 3 || term.isEmpty()) return@mapNotNull null
            Entry(
                HarmCategory.fromWire(parts[0]),
                Severity.fromWire(parts[1]),
                Kind.valueOf(parts[2].uppercase()),
                term.lowercase()
            )
        }

        fun bundled(): List<Entry> = StarterHarmClassifier::class.java.classLoader
            ?.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) }
            ?: emptyList()
    }
}
