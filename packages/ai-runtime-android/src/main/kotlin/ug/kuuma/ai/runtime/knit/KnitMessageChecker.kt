package ug.kuuma.ai.runtime.knit

import android.content.Context
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier

enum class CheckStatus { VALID, UNSUPPORTED_LANGUAGE, TOO_LONG, INVALID, MODEL_UNAVAILABLE }

/** A missing decision is never a clear verdict. Scores are uncalibrated model outputs. */
data class MessageCheck(
    val status: CheckStatus,
    val needsReview: Boolean? = null,
    val threatScore: Double? = null,
    val insultScore: Double? = null
)

fun interface MessageChecker {
    fun check(text: String, language: Language?): MessageCheck
}

/** English-only port of the phone-tested prototype. No network, storage or logging. */
class KnitMessageChecker(context: Context) :
    MessageChecker,
    AutoCloseable {
    private val classifier = KnitTextClassifier(context.applicationContext)

    @Synchronized
    override fun check(text: String, language: Language?): MessageCheck {
        // An explicit language is required; neither a short lexicon nor this model detects language.
        if (language != Language.EN) return MessageCheck(CheckStatus.UNSUPPORTED_LANGUAGE)
        if (text.length > 640) return MessageCheck(CheckStatus.TOO_LONG)
        return try {
            val result = classifier.classify(text)
            when (result.outputStatus) {
                "VALID" -> MessageCheck(CheckStatus.VALID, result.label == "NEEDS_REVIEW", result.threat, result.insult)
                "TOO_LONG" -> MessageCheck(CheckStatus.TOO_LONG)
                else -> MessageCheck(CheckStatus.INVALID)
            }
        } catch (_: Exception) {
            MessageCheck(CheckStatus.MODEL_UNAVAILABLE)
        } catch (_: LinkageError) {
            MessageCheck(CheckStatus.MODEL_UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            MessageCheck(CheckStatus.MODEL_UNAVAILABLE)
        }
    }

    @Synchronized
    override fun close() = classifier.close()

    companion object {
        const val MODEL_VERSION = "knit-albert-915e702-english-0.1"
    }
}

/** Consumers must handle this as not checked, not a safe message. Contains no message text. */
class CheckUnavailableException(val status: CheckStatus) : IllegalStateException("AI check unavailable: $status")

/** Opt-in contract adapter. Not installed into the child app's default runtime automatically. */
class KnitHarmClassifier(private val checker: MessageChecker) : HarmClassifier {
    override val modelVersion = KnitMessageChecker.MODEL_VERSION

    override fun classify(text: String, context: ClassifyContext): Verdict {
        if (context.language != Language.EN) throw CheckUnavailableException(CheckStatus.UNSUPPORTED_LANGUAGE)
        val checked = checker.check(text, context.language)
        if (checked.status != CheckStatus.VALID || checked.needsReview == null) {
            throw CheckUnavailableException(checked.status)
        }
        val threat = checked.threatScore ?: throw CheckUnavailableException(CheckStatus.INVALID)
        val insult = checked.insultScore ?: throw CheckUnavailableException(CheckStatus.INVALID)
        if (!threat.isFinite() || !insult.isFinite() || threat !in 0.0..1.0 || insult !in 0.0..1.0) {
            throw CheckUnavailableException(CheckStatus.INVALID)
        }
        val score = maxOf(threat, insult)
        // Preserve the prototype's 0.50 decision boundary, not a self-reported LLM certainty.
        val harmful = score >= 0.50
        return Verdict(
            harmful = harmful,
            category = if (!harmful) {
                null
            } else if (threat >= insult) {
                HarmCategory.THREAT
            } else {
                HarmCategory.INSULT
            },
            severity = if (harmful) Severity.MEDIUM else null,
            confidence = if (harmful) score else 1.0 - score
        )
    }
}
