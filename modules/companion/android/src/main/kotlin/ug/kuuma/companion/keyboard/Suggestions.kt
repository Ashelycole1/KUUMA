package ug.kuuma.companion.keyboard

import java.util.concurrent.ConcurrentHashMap
import ug.kuuma.contracts.Language

/**
 * Word suggestions from a fixed, bundled word list per language (`kuuma/companion/words-<lang>.txt`,
 * one word per line, most common first).
 *
 * It never learns from what the child types: nothing typed is stored, so there is nothing to leak.
 * Lookup is a binary search over a sorted array, fast enough for every keystroke on a low-end phone.
 */
class WordList(wordsByRank: List<String>) {
    private val sorted: Array<String>
    private val rank: IntArray

    init {
        val clean = wordsByRank.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val order = clean.indices.sortedBy { clean[it] }
        sorted = Array(order.size) { clean[order[it]] }
        rank = IntArray(order.size) { order[it] }
    }

    val size: Int get() = sorted.size

    /** Up to [limit] completions of [prefix], most common first, matching the prefix's capitalisation. */
    fun complete(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        var lo = lowerBound(p)
        val hits = ArrayList<Int>()
        while (lo < sorted.size && sorted[lo].startsWith(p)) {
            if (sorted[lo] != p) hits += lo
            lo++
        }
        return hits.sortedBy { rank[it] }.take(limit).map { matchCase(prefix, sorted[it]) }
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    companion object {
        private val cache = ConcurrentHashMap<Language, WordList>()

        /** The bundled list for [language], or an empty list if none ships yet. */
        fun forLanguage(language: Language): WordList = cache.getOrPut(language) {
            val stream = WordList::class.java.classLoader!!.getResourceAsStream(
                "kuuma/companion/words-${language.wire}.txt"
            )
            WordList(
                stream?.use { s -> s.bufferedReader(Charsets.UTF_8).readLines().filterNot { it.startsWith("#") } }
                    ?: emptyList()
            )
        }
    }
}

/** The word being typed: the letters right before the cursor. */
fun currentWord(textBeforeCursor: CharSequence): String {
    var i = textBeforeCursor.length
    while (i > 0 && (textBeforeCursor[i - 1].isLetter() || textBeforeCursor[i - 1] == '\'')) i--
    return textBeforeCursor.subSequence(i, textBeforeCursor.length).toString()
}
