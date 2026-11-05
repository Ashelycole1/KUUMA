package ug.kuuma.sentinel.cloud

import ug.kuuma.contracts.AnalyseRequest
import ug.kuuma.contracts.AnalyseResponse
import ug.kuuma.contracts.ArtifactManifest
import ug.kuuma.contracts.FeedbackRequest

/** The three /v1/ai calls Sentinel makes. HTTP implementation: [HttpAiGateway]. */
interface AiGateway {
    fun analyse(request: AnalyseRequest): AnalyseResponse
    fun feedback(request: FeedbackRequest)
    fun latestLexicon(): ArtifactManifest
    fun download(url: String): ByteArray
}

/** What the phone is doing right now, for the battery and data budget. */
data class DeviceState(
    val batteryPercent: Int = 100,
    val charging: Boolean = true,
    /** Wi-Fi or another unmetered network. */
    val unmetered: Boolean = true,
    val online: Boolean = true,
    /** Android's Data Saver is on (API 24+), or the user chose to limit data. */
    val dataSaver: Boolean = false
)

enum class Budget { SEND_NOW, BATCH, HOLD }

/**
 * Battery and data budget for cloud calls. On-device screening never stops; only the
 * cloud second opinion waits.
 *   - offline, or battery under 15% and not charging -> hold
 *   - metered network or data saver -> batch (send a few at a time, every 15 minutes)
 *   - otherwise -> send now
 */
object BudgetPolicy {
    const val LOW_BATTERY = 15
    const val BATCH_INTERVAL_MILLIS = 15 * 60_000L
    const val BATCH_SIZE = 5

    fun decide(d: DeviceState): Budget = when {
        !d.online -> Budget.HOLD
        d.batteryPercent < LOW_BATTERY && !d.charging -> Budget.HOLD
        !d.unmetered || d.dataSaver -> Budget.BATCH
        else -> Budget.SEND_NOW
    }
}

/** A pending cloud call. Analyse jobs hold redacted text only, and are never written to disk. */
sealed class CloudJob {
    abstract val createdAt: Long

    data class Analyse(val ticket: String, val request: AnalyseRequest, override val createdAt: Long) : CloudJob()
    data class Feedback(val request: FeedbackRequest, override val createdAt: Long) : CloudJob()
}

/**
 * Queue of cloud calls with batching and exponential backoff. [pump] is called by a
 * timer on the phone; it sends what the budget allows and returns how long to wait.
 */
class CloudQueue(
    private val gateway: AiGateway,
    private val onAnalysed: (ticket: String, AnalyseResponse) -> Unit,
    private val maxJobs: Int = 50,
    private val maxAgeMillis: Long = 24 * 3_600_000L
) {
    private val jobs = ArrayDeque<CloudJob>()
    private var backoffUntil = 0L
    private var backoffMillis = INITIAL_BACKOFF
    var sent = 0
        private set
    var failures = 0
        private set

    @Synchronized fun size() = jobs.size

    @Synchronized fun enqueue(job: CloudJob) {
        jobs.addLast(job)
        // When full, drop the oldest second opinion first: the local verdict stands for it.
        while (jobs.size > maxJobs) {
            val oldest = jobs.firstOrNull { it is CloudJob.Analyse } ?: jobs.first()
            jobs.remove(oldest)
        }
    }

    /** Send what the budget allows. Returns millis until the next useful pump. */
    @Synchronized fun pump(now: Long, device: DeviceState): Long {
        jobs.removeAll { now - it.createdAt > maxAgeMillis }
        if (jobs.isEmpty()) return IDLE
        if (now < backoffUntil) return backoffUntil - now
        val budget = BudgetPolicy.decide(device)
        val count = when (budget) {
            Budget.HOLD -> return HOLD_RECHECK
            Budget.BATCH -> {
                // Send a batch once it is full, or once its oldest job has waited the interval.
                val waited = now - jobs.first().createdAt
                if (jobs.size < BudgetPolicy.BATCH_SIZE && waited < BudgetPolicy.BATCH_INTERVAL_MILLIS) {
                    return BudgetPolicy.BATCH_INTERVAL_MILLIS - waited
                }
                BudgetPolicy.BATCH_SIZE
            }
            Budget.SEND_NOW -> jobs.size
        }
        repeat(minOf(count, jobs.size)) {
            val job = jobs.first()
            try {
                when (job) {
                    is CloudJob.Analyse -> onAnalysed(job.ticket, gateway.analyse(job.request))
                    is CloudJob.Feedback -> gateway.feedback(job.request)
                }
                jobs.removeFirst()
                sent++
                backoffMillis = INITIAL_BACKOFF
            } catch (e: Exception) {
                failures++
                backoffUntil = now + backoffMillis
                backoffMillis = minOf(backoffMillis * 2, MAX_BACKOFF)
                return backoffUntil - now
            }
        }
        return if (jobs.isEmpty()) IDLE else 0
    }

    companion object {
        const val INITIAL_BACKOFF = 30_000L
        const val MAX_BACKOFF = 30 * 60_000L
        const val HOLD_RECHECK = 10 * 60_000L
        const val IDLE = Long.MAX_VALUE
    }
}
