package ug.kuuma.contracts.ai

import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.Verdict

/**
 * On-device AI interfaces. Modules code against these; the AI owner provides the
 * implementations in `ai-runtime-android` (dummies today, real models later).
 *
 * Both calls may be slow on low-end phones: never call them on the main thread.
 */
interface HarmClassifier {
    /** Identifies the model, e.g. "dummy-0.0.0"; sent with feedback so it can be traced. */
    val modelVersion: String

    fun classify(text: String, context: ClassifyContext = ClassifyContext()): Verdict
}

/** What the classifier knows besides the message itself. */
data class ClassifyContext(
    val language: Language? = null,
    val appSource: AppSource? = null,
    /** Earlier messages in the same conversation, oldest first. Phone only. */
    val previousMessages: List<String> = emptyList(),
    val ageBracket: AgeBracket? = null,
    /** How this sender and the child usually talk, so banter can be told from abuse. Phone only. */
    val contact: ContactContext? = null
)

/**
 * Local counters about one sender, kept by Sentinel on the phone and never sent anywhere.
 * Counts only: no names, numbers or text.
 */
data class ContactContext(
    /** Messages this sender sent the child. */
    val messagesFromThem: Int,
    /** Messages the child sent this sender (two-way talk suggests a real relationship). */
    val messagesFromChild: Int,
    /** Earlier flags raised on this sender. */
    val priorFlags: Int,
    /** Earlier flags on this sender that the child marked "not harmful". */
    val dismissedFlags: Int,
    /** Days since Sentinel first saw this sender. */
    val daysKnown: Int
)

/** Checks a reply the child is typing before it is sent (used by the Companion keyboard). */
interface ReplyRiskChecker {
    val modelVersion: String

    fun check(draft: String, language: Language): ReplyRisk
}
