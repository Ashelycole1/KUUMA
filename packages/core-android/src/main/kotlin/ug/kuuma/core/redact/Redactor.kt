package ug.kuuma.core.redact

/**
 * Replaces personal data in message text with placeholders before anything leaves the
 * phone (for example before calling /v1/ai/analyse). Core ships [BasicRedactor];
 * Sentinel extends it with contact-book and app-specific knowledge.
 */
interface Redactor {
    fun redact(text: String, knownNames: Collection<String> = emptyList()): Redacted
}

/** Redacted text plus the placeholder → original map, which never leaves the phone. */
data class Redacted(val text: String, val replacements: Map<String, String>)

/**
 * Basic redaction:
 *  - phone numbers (international +256 7XX XXX XXX, local 07XX XXX XXX, other international forms) → [PHONE_n]
 *  - email addresses → [EMAIL_n]
 *  - @handles → [HANDLE_n]
 *  - names passed in [knownNames] (e.g. the sender's display name, contacts) → [CONTACT_n]
 *
 * The same original always maps to the same placeholder within one call.
 */
open class BasicRedactor : Redactor {
    protected open val rules: List<Pair<String, Regex>> = listOf(
        "EMAIL" to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        "PHONE" to Regex("""(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])"""),
        "HANDLE" to Regex("""(?<![\w@])@[A-Za-z0-9_.]{2,30}""")
    )

    override fun redact(text: String, knownNames: Collection<String>): Redacted {
        val replacements = LinkedHashMap<String, String>()
        val counters = HashMap<String, Int>()
        val byOriginal = HashMap<String, String>()

        fun placeholder(kind: String, original: String): String = byOriginal.getOrPut("$kind|${original.lowercase()}") {
            val n = (counters[kind] ?: 0) + 1
            counters[kind] = n
            "[${kind}_$n]".also { replacements[it] = original }
        }

        var out = text
        // Patterns first, so a name inside an email address goes with the email.
        for ((kind, re) in rules) {
            out = re.replace(out) { m ->
                if (kind == "PHONE" && m.value.count(Char::isDigit) !in 9..15) m.value else placeholder(kind, m.value)
            }
        }
        // Then names, longest first so "Kato John" wins over "Kato"; never inside a placeholder.
        for (name in knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            val re =
                Regex(
                    "(?<![\\p{L}\\d\\[_])" + Regex.escape(name.trim()) + "(?![\\p{L}\\d_\\]])",
                    RegexOption.IGNORE_CASE
                )
            out = re.replace(out) { placeholder("CONTACT", it.value) }
        }
        return Redacted(out, replacements)
    }
}
