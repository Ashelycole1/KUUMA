package ug.kuuma.sentinel.screen

import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.ai.ContactContext
import ug.kuuma.core.redact.BasicRedactor
import ug.kuuma.core.redact.Redacted

/**
 * Sentinel's redactor: Core's BasicRedactor plus everyone Sentinel knows takes part in
 * the conversation (senders and the chat title), so names never reach /v1/ai.
 */
class SentinelRedactor : BasicRedactor() {
    fun redactMessage(text: String, participants: Collection<String>): Redacted =
        redact(text, participants.filter { it.length >= 2 && it != ug.kuuma.sentinel.ingest.NotificationParser.SELF })
}

/** The parent's policy as Sentinel needs it (from guardian.policy.updated / pairing). */
data class SentinelPolicy(
    val watchedApps: Set<AppSource> = setOf(AppSource.WHATSAPP, AppSource.SMS),
    val thresholds: Thresholds = Thresholds(0.6, Severity.MEDIUM, Severity.MEDIUM),
    val ageBracket: AgeBracket? = null,
    val policyVersion: Long = 0
)

/** Per-sender counters kept in sentinel.db. Counts and timestamps only. */
data class ContactStats(
    val senderRef: String,
    val fromThem: Int = 0,
    val fromChild: Int = 0,
    val flags: Int = 0,
    val dismissed: Int = 0,
    val firstSeen: Long,
    val lastSeen: Long
) {
    fun toContext(now: Long) = ContactContext(
        messagesFromThem = fromThem,
        messagesFromChild = fromChild,
        priorFlags = flags,
        dismissedFlags = dismissed,
        daysKnown = ((now - firstSeen) / 86_400_000L).toInt().coerceAtLeast(0)
    )
}

/** A flag Sentinel raised: refs, labels and numbers only, so it may be stored. */
data class FlagRecord(
    val flagId: String,
    val conversationRef: String,
    val senderRef: String,
    val messageRef: String,
    val category: HarmCategory,
    val severity: Severity,
    val confidence: Double,
    val modelVersion: String,
    val at: Long,
    val dismissed: Boolean = false
)

/** What to do with one verdict. */
enum class Route {
    /** Not harmful. */
    CLEAR,

    /** Harmful: flag now. */
    FLAG,

    /** Harmful and high risk: flag now, and ask the cloud to confirm. */
    FLAG_AND_CONFIRM,

    /** Uncertain: ask the cloud (redacted) before flagging. */
    ASK_CLOUD
}

object Screening {
    /** Below this, a harmful verdict is noise; between this and the threshold, the cloud decides. */
    const val UNCERTAIN_FLOOR = 0.35

    /** A "not harmful" verdict with less confidence than this is uncertain too. */
    const val CONFIDENT_CLEAR = 0.6

    /**
     * The confidence a flag needs for this sender. Each flag the child dismissed on this
     * sender raises the bar a little (feedback "lowers that contact's score"), up to 0.95.
     */
    fun threshold(policy: SentinelPolicy, stats: ContactStats?): Double =
        (policy.thresholds.flagMinConfidence + 0.1 * minOf(stats?.dismissed ?: 0, 3)).coerceAtMost(0.95)

    fun route(v: Verdict, threshold: Double): Route = when {
        v.harmful && v.category != null && v.severity != null && v.confidence >= threshold ->
            if (v.severity == Severity.HIGH) Route.FLAG_AND_CONFIRM else Route.FLAG
        v.harmful && v.confidence >= UNCERTAIN_FLOOR -> Route.ASK_CLOUD
        !v.harmful && v.confidence < CONFIDENT_CLEAR -> Route.ASK_CLOUD
        else -> Route.CLEAR
    }

    fun atLeast(s: Severity, min: Severity) = s.ordinal >= min.ordinal
}
