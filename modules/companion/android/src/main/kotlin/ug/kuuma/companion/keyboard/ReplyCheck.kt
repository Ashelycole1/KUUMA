package ug.kuuma.companion.keyboard

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.RiskySpan
import ug.kuuma.contracts.ai.ReplyRiskChecker
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog

/**
 * Runs the AI build's [ReplyRiskChecker] off the main thread and delivers only the newest result.
 *
 * The draft lives only in memory for the duration of the check; it is never stored or logged.
 */
class ReplyCheckRunner(
    private val checker: () -> ReplyRiskChecker,
    private val background: Executor,
    private val deliver: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val generation = AtomicLong()

    /** Check [draft]; [onResult] runs via [deliver] unless a newer draft was submitted meanwhile. */
    fun submit(draft: String, language: Language, onResult: (ReplyRisk) -> Unit) {
        val gen = generation.incrementAndGet()
        if (draft.isBlank()) {
            deliver(Runnable { if (generation.get() == gen) onResult(SAFE) })
            return
        }
        background.execute {
            val start = clock()
            val risk = runCatching { checker().check(draft, language) }.getOrElse {
                SafeLog.w("companion.reply_check.failed")
                SAFE
            }
            SafeLog.d(
                "companion.reply_check.done",
                LogField.bool("risky", risk.risky),
                LogField.ms(
                    "took",
                    clock() - start
                )
            )
            val clean = risk.copy(spans = validSpans(risk.spans, draft.length))
            deliver(Runnable { if (generation.get() == gen) onResult(clean) })
        }
    }

    /** Forget any pending check (e.g. the keyboard closed). */
    fun cancel() {
        generation.incrementAndGet()
    }

    companion object {
        val SAFE = ReplyRisk(risky = false, spans = emptyList(), suggestionKeys = emptyList())

        /** Drops spans outside the draft and merges overlaps, so callers can trust the offsets. */
        fun validSpans(spans: List<RiskySpan>, length: Int): List<RiskySpan> {
            val ok = spans.filter { it.start >= 0 && it.end <= length && it.start < it.end }.sortedBy { it.start }
            val out = ArrayList<RiskySpan>()
            for (s in ok) {
                val last = out.lastOrNull()
                if (last != null && s.start <= last.end) {
                    out[out.size - 1] = last.copy(end = maxOf(last.end, s.end))
                } else {
                    out += s
                }
            }
            return out
        }

        /**
         * The safer wording the keyboard offers: the draft with the risky parts taken out and the
         * spacing tidied. E.g. "text me on <number> later" becomes "text me on later"; the child
         * edits from there. It never adds words of its own.
         */
        fun saferWording(draft: String, spans: List<RiskySpan>): String {
            val valid = validSpans(spans, draft.length)
            if (valid.isEmpty()) return draft
            val sb = StringBuilder()
            var at = 0
            for (s in valid) {
                sb.append(draft, at, s.start.toInt())
                at = s.end.toInt()
            }
            sb.append(draft, at, draft.length)
            return sb.toString()
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex(" +([,.!?])"), "$1")
                .trim()
        }
    }
}
