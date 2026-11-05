package ug.kuuma.ai.runtime

import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.RiskySpan
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.contracts.ai.ReplyRiskChecker

/**
 * Stand-in classifier: flags any message containing a word from a short TEST list as high
 * severity. It is not a model and must never ship to real users; the AI owner replaces it.
 *
 * The list is matched case-insensitively against whole words or phrases. It covers the
 * invented text in fakes/scenarios so the scenarios exercise the full chain.
 */
class DummyHarmClassifier(private val terms: Map<String, HarmCategory> = TEST_TERMS) : HarmClassifier {
    override val modelVersion = "dummy-0.0.0"

    private val patterns = terms.map { (term, category) ->
        Regex("(?<![\\p{L}])" + Regex.escape(term) + "(?![\\p{L}])", RegexOption.IGNORE_CASE) to category
    }

    override fun classify(text: String, context: ClassifyContext): Verdict {
        val hit = patterns.firstOrNull { (re, _) -> re.containsMatchIn(text) }
        return if (hit == null) {
            Verdict(harmful = false, confidence = 0.99)
        } else {
            Verdict(harmful = true, category = hit.second, severity = Severity.HIGH, confidence = 0.95)
        }
    }

    companion object {
        /** TEST terms only (invented scenarios). Not a lexicon: the real lexicon is packages/lang/lexicon. */
        val TEST_TERMS: Map<String, HarmCategory> = linkedMapOf(
            "musiru" to HarmCategory.INSULT,
            "kasiru" to HarmCategory.INSULT,
            "tolina mugaso" to HarmCategory.INSULT,
            "mjinga" to HarmCategory.INSULT,
            "stupid" to HarmCategory.INSULT,
            "hurt you" to HarmCategory.THREAT,
            "kill you" to HarmCategory.THREAT,
            "as a gift" to HarmCategory.GROOMING,
            "understand you like i do" to HarmCategory.GROOMING,
            "sexualised remark" to HarmCategory.GROOMING,
            "our secret" to HarmCategory.GROOMING,
            "request to meet" to HarmCategory.GROOMING
        )
    }
}

/** Stand-in reply checker: flags any draft containing a phone number. */
class DummyReplyRiskChecker : ReplyRiskChecker {
    override val modelVersion = "dummy-0.0.0"

    private val phone = Regex("""(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])""")

    override fun check(draft: String, language: Language): ReplyRisk {
        val spans = phone.findAll(draft)
            .filter { m -> m.value.count(Char::isDigit) in 9..15 }
            .map { RiskySpan(it.range.first.toLong(), (it.range.last + 1).toLong(), "advice.reply.no_phone_number") }
            .toList()
        return ReplyRisk(
            risky = spans.isNotEmpty(),
            spans = spans,
            suggestionKeys = if (spans.isEmpty()) emptyList() else listOf("advice.reply.no_phone_number")
        )
    }
}
