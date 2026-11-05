package ug.kuuma.sentinel.normalize

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ug.kuuma.contracts.Language

/** One lexicon entry (format: packages/lang/lexicon/README.md). Owned and filled by the AI build. */
@Serializable
data class LexiconEntry(
    val term: String,
    val variants: List<String> = emptyList(),
    val category: String,
    val severity: String,
    val contextual: Boolean = false
)

@Serializable
data class LexiconFile(val language: String, val version: String, val entries: List<LexiconEntry> = emptyList())

/**
 * The lexicon bundle Sentinel downloads from GET /v1/ai/lexicon/latest: the per-language
 * lexicon files from packages/lang/lexicon, as JSON.
 */
@Serializable
data class LexiconBundle(val version: String, val files: List<LexiconFile> = emptyList()) {
    companion object {
        val EMPTY = LexiconBundle("0.0.0")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): LexiconBundle = json.decodeFromString(serializer(), text)
    }

    /** variant -> canonical term, for every language. */
    fun variantMap(): Map<String, String> = buildMap {
        for (f in files) {
            for (e in f.entries) {
                for (v in e.variants) {
                    put(
                        v.lowercase(Locale.ROOT),
                        e.term.lowercase(Locale.ROOT)
                    )
                }
            }
        }
    }
}

/**
 * Normalises message text before screening: Unicode forms, case, emoji and emoticon codes,
 * leetspeak, stretched letters ("stuuupid"), SMS shorthand and lexicon variants.
 * The original text is kept for evidence; only the classifier sees the normalised form.
 */
class Normaliser(lexicon: LexiconBundle = LexiconBundle.EMPTY) {
    @Volatile private var variants: Map<String, String> = lexicon.variantMap()

    fun update(lexicon: LexiconBundle) {
        variants = lexicon.variantMap()
    }

    fun normalise(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        for ((code, word) in EMOJI) s = s.replace(code, " $word ")
        val tokens = s.split(WHITESPACE).filter { it.isNotEmpty() }.map { token(it) }
        return tokens.joinToString(" ").trim()
    }

    private fun token(raw: String): String {
        val core = raw.trim { !it.isLetterOrDigit() && it != '@' && it != '$' }
        if (core.isEmpty()) return raw
        var t = core
        if (t.any(Char::isLetter) && t.any { it in LEET }) t = t.map { LEET[it] ?: it }.joinToString("")
        t = STRETCH.replace(t) { it.value.substring(0, 1) }
        t = variants[t] ?: SHORTHAND[t] ?: t
        return raw.replace(core, t)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Three or more of the same letter become one ("nooo" -> "no"); doubles stay ("nnyo"). */
        private val STRETCH = Regex("(\\p{L})\\1{2,}")
        private val LEET = mapOf(
            '0' to 'o',
            '1' to 'i',
            '3' to 'e',
            '4' to 'a',
            '5' to 's',
            '7' to 't',
            '@' to 'a',
            '$' to 's'
        )
        private val SHORTHAND = mapOf(
            "u" to "you", "ur" to "your", "r" to "are", "pls" to "please", "plz" to "please", "bt" to "but",
            "stfu" to "shut up", "kys" to "kill yourself", "gtfo" to "get out", "idc" to "i do not care",
            "wtf" to "what the hell"
        )
        private val EMOJI = linkedMapOf(
            "🖕" to "middle_finger", "🔪" to "knife", "🔫" to "gun", "💀" to "skull", "☠" to "skull",
            "🤬" to "angry_swearing", "😡" to "angry", "🍆" to "eggplant", "🍑" to "peach", "💦" to "droplets",
            "🤡" to "clown", "🐷" to "pig", "🐒" to "monkey", "💩" to "poo",
            ":)" to "smile", ":-)" to "smile", ":(" to "sad", ":-(" to "sad", ":p" to "tongue", ";)" to "wink"
        )
    }
}

/**
 * A cheap guess at the message language from common function words. It only steers
 * the classifier; the AI build's models do the real work.
 */
object LanguageGuesser {
    private val markers: Map<Language, Set<String>> = mapOf(
        Language.LG to
            setOf(
                "oli", "nnyo", "ggwe", "nze", "tolina", "era", "kiki", "otya", "webale", "nkwagala", "mukwano",
                "ssomero", "olwaleero", "musiru", "kasiru", "toyinza", "buli", "muntu"
            ),
        Language.SW to
            setOf(
                "wewe", "mimi", "sana", "nini", "habari", "rafiki", "shule", "kesho", "mjinga", "nakupenda", "hapana",
                "ndiyo", "sasa"
            ),
        Language.NYN to
            setOf("niinye", "naiwe", "munonga", "nkakugonza", "reero", "omukazi", "omwana", "ninkukunda", "buzima"),
        Language.EN to
            setOf("the", "you", "your", "are", "is", "and", "to", "my", "will", "not", "i", "me", "school", "tomorrow")
    )

    fun guess(normalised: String, default: Language = Language.EN): Language {
        val words = normalised.split(' ').toSet()
        val scores = markers.mapValues { (_, set) -> words.count { it in set } }
        val best = scores.maxByOrNull { it.value } ?: return default
        return if (best.value == 0) default else best.key
    }
}
