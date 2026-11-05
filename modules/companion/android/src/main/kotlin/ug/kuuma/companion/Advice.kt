package ug.kuuma.companion

import ug.kuuma.companion.content.ContentPack
import ug.kuuma.companion.content.PlatformAction
import ug.kuuma.contracts.AdviceKey
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.OpaqueRef
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Uuid

/**
 * A flag as Companion remembers it: IDs and enums only, never message text.
 * [caseId] is the envelope's case, when Sentinel or Casefile attached one.
 */
data class ActiveFlag(
    val flagId: Uuid,
    val category: HarmCategory,
    val severity: Severity,
    val language: Language,
    val appSource: AppSource,
    val conversationRef: OpaqueRef,
    val caseId: Uuid?
)

data class AdviceCard(val key: AdviceKey, val text: String, val steps: List<String>)

/** Everything the victim screens need to show for one flag, already in the child's language. */
data class AdvicePlan(
    val flagId: Uuid,
    val category: HarmCategory,
    val severity: Severity,
    val appSource: AppSource,
    val language: Language,
    /** Self-harm signals: open the crisis screens at once instead of ordinary advice. */
    val crisis: Boolean,
    val cards: List<AdviceCard>,
    val blockSteps: List<String>,
    val reportSteps: List<String>
)

/** What the keyboard shows above the keys while the child is in a flagged conversation. */
data class KeyboardContext(
    val conversationRef: OpaqueRef,
    val appSource: AppSource,
    val severity: Severity,
    val category: HarmCategory?,
    /** One-line tip: the first advice card for the flag's category. */
    val tip: String,
    val tipKey: AdviceKey?,
    /** True when the keyboard should not offer replies, only a nudge not to reply. */
    val noReply: Boolean
)

/** Picks advice from the content pack. Pure: no I/O, no state, cheap enough for the main thread. */
class AdviceEngine(@Volatile var pack: ContentPack) {
    fun plan(flag: ActiveFlag, bracket: AgeBracket?, language: Language = flag.language): AdvicePlan {
        val p = pack
        val cards = p.adviceKeys(flag.category, flag.severity).mapNotNull { key ->
            p.adviceText(key, language, bracket)?.let { AdviceCard(key, it, p.adviceSteps(key, language, bracket)) }
        }
        return AdvicePlan(
            flagId = flag.flagId,
            category = flag.category,
            severity = flag.severity,
            appSource = flag.appSource,
            language = language,
            crisis = p.isCrisis(flag.category),
            cards = cards,
            blockSteps = p.platformSteps(flag.appSource, PlatformAction.BLOCK, language, bracket),
            reportSteps = p.platformSteps(flag.appSource, PlatformAction.REPORT, language, bracket)
        )
    }

    /**
     * The keyboard banner for a flagged conversation. [flag] is the newest flag Companion knows
     * for it; it can be null when the keyboard opened after the flag (only the severity is known).
     */
    fun keyboardContext(
        conversationRef: OpaqueRef,
        appSource: AppSource,
        topSeverity: Severity?,
        flag: ActiveFlag?,
        bracket: AgeBracket?,
        language: Language
    ): KeyboardContext {
        val p = pack
        val severity = maxOf(topSeverity ?: Severity.LOW, flag?.severity ?: Severity.LOW)
        val key = flag?.let { p.adviceKeys(it.category, severity).firstOrNull() } ?: FALLBACK_TIP
        return KeyboardContext(
            conversationRef = conversationRef,
            appSource = appSource,
            severity = severity,
            category = flag?.category,
            tip = p.adviceText(key, language, bracket).orEmpty(),
            tipKey = key,
            // Without a category we can't be sure replies are safe, so default to the nudge.
            noReply = flag?.let { p.noReply(it.category) } ?: true
        )
    }

    companion object {
        const val FALLBACK_TIP: AdviceKey = "advice.general.do_not_reply"
    }
}
