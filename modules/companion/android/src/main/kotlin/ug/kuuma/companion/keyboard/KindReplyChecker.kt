package ug.kuuma.companion.keyboard

import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.RiskySpan
import ug.kuuma.contracts.ai.ReplyRiskChecker

/**
 * Adds a kindness check to the reply checker the keyboard uses: words and phrases that may hurt
 * the person the child is writing to ("you are so stupid") are marked, so the keyboard can say
 * "this may sound hurtful" and offer the draft without them. It never blocks sending.
 *
 * The words come from a small bundled starter list (`kuuma/companion/unkind-words.txt`). It is a
 * placeholder for the AI owner's lexicon (packages/lang/lexicon): replace, don't extend, once that
 * exists. Every language is checked whatever the keyboard language, because children mix them.
 * Matching is whole words only, so "classic" never matches "ass".
 */
class KindReplyChecker(private val base: ReplyRiskChecker, terms: List<String> = bundledTerms()) : ReplyRiskChecker {
    override val modelVersion: String get() = "${base.modelVersion}+kind-0.1.0"

    // Longest first, so "shut up" wins over a shorter overlapping term.
    private val patterns = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }
        .map {
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it).replace(" ", "\\E\\s+\\Q") + "(?![\\p{L}\\p{N}])", OPTIONS)
        }

    override fun check(draft: String, language: Language): ReplyRisk {
        val first = base.check(draft, language)
        val unkind = patterns.flatMap { re ->
            re.findAll(draft).map { RiskySpan(it.range.first.toLong(), (it.range.last + 1).toLong(), BE_KIND) }
        }
        if (unkind.isEmpty()) return first
        val spans = ReplyCheckRunner.validSpans(first.spans + unkind, draft.length)
        return ReplyRisk(
            risky = true,
            // The hurtful wording is the reason shown first; a phone number still gets its own advice.
            spans = spans.sortedBy { if (it.reasonKey == BE_KIND) 0 else 1 },
            suggestionKeys = (listOf(BE_KIND) + first.suggestionKeys).distinct()
        )
    }

    companion object {
        const val BE_KIND = "advice.reply.be_kind"
        private val OPTIONS = setOf(RegexOption.IGNORE_CASE)
        private const val RESOURCE = "kuuma/companion/unkind-words.txt"

        /** One term per line; `#` starts a comment. */
        fun parse(text: String): List<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

        fun bundledTerms(): List<String> = KindReplyChecker::class.java.classLoader?.getResourceAsStream(RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) }
            ?: emptyList()
    }
}
