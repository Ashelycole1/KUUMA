package ug.kuuma.casefile.cases

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.VaultItem
import ug.kuuma.contracts.EscalationReason
import ug.kuuma.contracts.GroomingStage
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Severity

/** One flagged message in a case. */
data class Incident(
    val flagId: String,
    val at: Long,
    val category: HarmCategory,
    val severity: Severity,
    val appSource: String,
    val senderRef: String,
    val dismissed: Boolean,
    val groomingSignals: List<GroomingStage>
)

/** One line of a case timeline (shown in the app and in the case file). */
data class TimelineEntry(
    val at: Long,
    val itemId: String,
    val kind: ItemKind,
    val appSource: String?,
    val senderLabel: String?,
    val fromChild: Boolean,
    val category: HarmCategory?,
    val severity: Severity?,
    val text: String?
)

/** A case: everything one offender (one or more linked sender refs) sent the child. */
data class CaseState(
    val caseId: String,
    val offenderLabel: String,
    val senderRefs: Set<String>,
    val incidents: List<Incident>,
    val messageCount: Int,
    val groomingStages: List<GroomingStage>
) {
    val activeIncidents get() = incidents.filterNot { it.dismissed }
    val incidentCount get() = activeIncidents.size.toLong()
    val topSeverity get() = activeIncidents.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.LOW
    val categories get() = activeIncidents.map { it.category }.distinct()
    val firstAt get() = incidents.minOfOrNull { it.at }
    val lastAt get() = incidents.maxOfOrNull { it.at }

    /** Incidents between 22:00 and 05:00 phone time: late-night contact is an escalation marker. */
    fun lateNightCount(zone: TimeZone = TimeZone.getDefault()) =
        activeIncidents.count { Patterns.isLateNight(it.at, zone) }
}

object Patterns {
    const val REPEAT_COUNT = 3
    const val REPEAT_WINDOW = 7 * 24 * 3_600_000L

    fun isLateNight(at: Long, zone: TimeZone): Boolean {
        val h = Calendar.getInstance(zone).apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 5
    }

    /**
     * Escalation rules, checked after every change to a case. Each reason fires once per case.
     *  - severity_threshold: any active incident of high severity
     *  - repeated_incidents: 3+ active incidents within 7 days
     *  - grooming_detected: Sentinel reported grooming stages
     */
    fun reasons(c: CaseState): List<EscalationReason> = buildList {
        if (c.topSeverity == Severity.HIGH) add(EscalationReason.SEVERITY_THRESHOLD)
        val times = c.activeIncidents.map { it.at }.sorted()
        if (times.indices.any { i ->
                i + REPEAT_COUNT - 1 < times.size &&
                    times[i + REPEAT_COUNT - 1] - times[i] <= REPEAT_WINDOW
            }
        ) {
            add(EscalationReason.REPEATED_INCIDENTS)
        }
        if (c.groomingStages.isNotEmpty()) add(EscalationReason.GROOMING_DETECTED)
    }

    /** Pseudonymous labels: Person A ... Person Z, Person AA ... (contract OffenderLabel). */
    fun label(n: Int): String {
        require(n in 0 until 26 + 26 * 26)
        return if (n < 26) "Person ${'A' + n}" else "Person ${'A' + (n / 26 - 1)}${'A' + n % 26}"
    }
}

/** Rebuilds cases and timelines from the sealed items: the vault is the only source of truth. */
object CaseIndex {
    private val json = Json

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    fun parse(item: VaultItem): JsonObject = json.parseToJsonElement(item.contentJson).jsonObject

    /** When each captured message was sent, by messageRef. */
    private fun sentTimes(items: List<VaultItem>): Map<String, Long> =
        items.filter { it.kind == ItemKind.MESSAGE }.mapNotNull {
            val c = parse(it)
            val ref = c.str("messageRef") ?: return@mapNotNull null
            val at = c.str("sentAtMillis")?.toLongOrNull() ?: return@mapNotNull null
            ref to at
        }.toMap()

    /**
     * When an incident happened: the flagged message's sent time once its capture is in the
     * vault, otherwise when the flag arrived. Late-night and frequency rules use this.
     */
    private fun incidentTime(flag: JsonObject, sent: Map<String, Long>): Long =
        flag.str("messageRef")?.let(sent::get) ?: flag.str("flaggedAtMillis")!!.toLong()

    fun build(items: List<VaultItem>, labels: Map<String, String>, members: Map<String, Set<String>>): List<CaseState> {
        val byCase = items.filter { it.caseId != null }.groupBy { it.caseId!! }
        return byCase.map { (caseId, caseItems) ->
            val updates = caseItems.filter { it.kind == ItemKind.FLAG_UPDATE }.map(::parse).groupBy { it.str("flagId") }
            val sent = sentTimes(caseItems)
            val incidents = caseItems.filter { it.kind == ItemKind.FLAG }.map { item ->
                val c = parse(item)
                val flagId = c.str("flagId")!!
                var category = HarmCategory.fromWire(c.str("category")!!)
                var severity = Severity.fromWire(c.str("severity")!!)
                var dismissed = false
                val grooming = mutableListOf<GroomingStage>()
                for (u in updates[flagId].orEmpty()) {
                    u.str("category")?.let { category = HarmCategory.fromWire(it) }
                    u.str("severity")?.let { severity = Severity.fromWire(it) }
                    if (u["dismissed"]?.jsonPrimitive?.booleanOrNull == true) dismissed = true
                    u["groomingSignals"]?.jsonArray?.forEach {
                        grooming +=
                            GroomingStage.fromWire(it.jsonPrimitive.content)
                    }
                }
                Incident(
                    flagId,
                    incidentTime(c, sent),
                    category,
                    severity,
                    c.str("appSource")!!,
                    c.str("senderRef")!!,
                    dismissed,
                    grooming.distinct()
                )
            }.sortedBy { it.at }
            CaseState(
                caseId = caseId,
                offenderLabel = labels[caseId] ?: "Person A",
                senderRefs = members[caseId].orEmpty() + incidents.map { it.senderRef },
                incidents = incidents,
                messageCount = caseItems.count { it.kind == ItemKind.MESSAGE },
                groomingStages = incidents.flatMap { it.groomingSignals }.distinct().sortedBy { it.ordinal }
            )
        }.sortedByDescending { it.lastAt ?: 0 }
    }

    fun timeline(items: List<VaultItem>, caseId: String, label: String): List<TimelineEntry> {
        val sent = sentTimes(items.filter { it.caseId == caseId })
        return timelineEntries(items, caseId, label, sent)
            // A flag sits right after the message it flags.
            .sortedWith(compareBy<TimelineEntry> { it.at }.thenBy { if (it.kind == ItemKind.FLAG) 1 else 0 })
    }

    private fun timelineEntries(items: List<VaultItem>, caseId: String, label: String, sent: Map<String, Long>) =
        items.filter {
            it.caseId == caseId && it.kind in setOf(ItemKind.FLAG, ItemKind.MESSAGE, ItemKind.MEDIA)
        }.map { item ->
            val c = parse(item)
            when (item.kind) {
                ItemKind.FLAG -> TimelineEntry(
                    incidentTime(c, sent), item.itemId, item.kind, c.str("appSource"), label, false,
                    HarmCategory.fromWire(c.str("category")!!), Severity.fromWire(c.str("severity")!!), null
                )
                ItemKind.MESSAGE -> {
                    val fromChild = c["fromChild"]?.jsonPrimitive?.booleanOrNull == true
                    TimelineEntry(
                        c.str("sentAtMillis")!!.toLong(), item.itemId, item.kind, c.str("appSource"),
                        // On the child's own screen: the name or number their messaging app showed.
                        if (fromChild) null else c.str("senderDisplayName") ?: label,
                        fromChild, null, null, c.str("text")
                    )
                }
                else -> TimelineEntry(
                    item.storedAt, item.itemId, item.kind,
                    c.str(
                        "appSource"
                    ),
                    label, false, null, null, null
                )
            }
        }.sortedBy { it.at }

    fun confidence(item: VaultItem): Double? = parse(item)["confidence"]?.jsonPrimitive?.doubleOrNull

    fun iso(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone =
            TimeZone.getTimeZone("UTC")
    }.format(millis)
}
