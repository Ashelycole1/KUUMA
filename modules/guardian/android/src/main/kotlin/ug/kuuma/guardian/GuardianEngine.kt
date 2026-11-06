package ug.kuuma.guardian

import kotlinx.serialization.Serializable
import ug.kuuma.contracts.AlertKind
import ug.kuuma.contracts.AlertRequest
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.ApprovalDecision
import ug.kuuma.contracts.ApprovalKind
import ug.kuuma.contracts.ApprovalRequest
import ug.kuuma.contracts.ApprovalResult
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GuardianPairingCompletedPayload
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.GuardianReportRequestedPayload
import ug.kuuma.contracts.GuardianShareApprovedPayload
import ug.kuuma.contracts.GuardianShareDeclinedPayload
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.HeartbeatRequest
import ug.kuuma.contracts.HeartbeatResponse
import ug.kuuma.contracts.PairingRequest
import ug.kuuma.contracts.PairingResponse
import ug.kuuma.contracts.Policy
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.ReportOrder
import ug.kuuma.contracts.ReportOutcome
import ug.kuuma.contracts.ReportResultRequest
import ug.kuuma.contracts.Severity
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.bus.ModulePublisher
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog

/** /v1/guardian calls the child's phone makes. */
interface GuardianApi {
    fun pair(request: PairingRequest): PairingResponse
    fun heartbeat(request: HeartbeatRequest): HeartbeatResponse
    fun alert(request: AlertRequest)
    fun approvalRequest(request: ApprovalRequest)

    /** The child's phone built (or could not build) a parent's report. */
    fun reportResult(reportId: String, request: ReportResultRequest) {}
}

/** guardian.db: the policy, pairing keys and unsent requests. Key-value is enough. */
interface GuardianStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryGuardianStore : GuardianStore {
    private val map = HashMap<String, String>()

    @Synchronized override fun get(key: String) = map[key]

    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

enum class PairResult { PAIRED, BAD_CODE, BAD_POLICY_SIGNATURE, ALREADY_PAIRED, OFFLINE }

@Serializable
private data class Outbox(
    val alerts: List<AlertRequest> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val reports: List<PendingReportResult> = emptyList()
)

@Serializable
private data class PendingReportResult(val reportId: String, val result: ReportResultRequest)

/**
 * Guardian's agent on the child's phone, free of Android so it is unit-tested end to end.
 * Not thread-safe by design: the module calls it from one background thread.
 */
class GuardianEngine(
    private val publisher: ModulePublisher,
    private val store: GuardianStore,
    private val api: GuardianApi?,
    private val devicePublicKey: () -> String,
    private val missingPermissions: () -> List<String> = { emptyList() },
    private val appVersion: String = "0.1.0",
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class PendingFlag(
        val severity: Severity,
        val category: HarmCategory,
        val appSource: AppSource,
        val at: Long,
        var excerpt: String? = null,
        var caseId: String? = null,
        var sent: Boolean = false
    )

    private val flags = LinkedHashMap<String, PendingFlag>()
    private val caseSeverity = HashMap<String, Severity>()

    val isPaired get() = store.get("childId") != null
    val childId get() = store.get("childId")

    /** The server's id for this phone (used to sign API calls). */
    val serverDeviceId get() = store.get("deviceId")

    val policy: Policy? get() = store.get("policy")?.let { ContractJson.json.decodeFromString(Policy.serializer(), it) }

    private val crypto: FamilyCrypto? get() = store.get("familyKey")?.let {
        FamilyCrypto(ug.kuuma.core.crypto.Base64Url.decode(it))
    }

    /** At startup: tell every module the current policy (they do not keep it). */
    fun announce() {
        val p = policy ?: return
        publishPolicy(p)
    }

    // ------------------------------------------------------------------ setup

    fun recordConsent() = store.put("consentAt", EventBus.iso(clock()))

    val consentAt get() = store.get("consentAt")

    fun pair(qrText: String): PairResult {
        if (isPaired) return PairResult.ALREADY_PAIRED
        val qr = PairingQr.parse(qrText) ?: return PairResult.BAD_CODE
        val a = api ?: return PairResult.OFFLINE
        val crypto = FamilyCrypto(qr.familyKey)
        val response = try {
            a.pair(PairingRequest(qr.code, devicePublicKey(), ug.kuuma.contracts.AgeBracket.AGE_13_15))
        } catch (e: java.io.IOException) {
            return if (e.message?.contains("404") == true) PairResult.BAD_CODE else PairResult.OFFLINE
        }
        // The server cannot forge the parent's policy: reject anything the family key did not sign.
        if (!crypto.verifyPolicy(response.policy)) return PairResult.BAD_POLICY_SIGNATURE
        store.put("familyKey", ug.kuuma.core.crypto.Base64Url.encode(qr.familyKey))
        store.put("childId", response.childId)
        store.put("deviceId", response.deviceId)
        savePolicy(response.policy)
        publisher.publish(
            Events.GUARDIAN_PAIRING_COMPLETED,
            GuardianPairingCompletedPayload(response.childId, response.deviceId, response.policy.ageBracket)
        )
        publishPolicy(response.policy)
        SafeLog.i("guardian.paired", LogField.id("child_id", response.childId))
        return PairResult.PAIRED
    }

    // ------------------------------------------------------------------ alert decisions

    /** A flag alerts once it has a case to point to: from its own envelope, or when Casefile seals it. */
    fun onFlagged(
        flagId: String,
        severity: Severity,
        category: HarmCategory,
        appSource: AppSource,
        caseId: String? = null
    ) {
        flags[flagId] = PendingFlag(severity, category, appSource, clock(), caseId = caseId)
        while (flags.size > 200) flags.remove(flags.keys.first())
        maybeAlert(flagId)
    }

    fun onFlagUpdated(flagId: String, severity: Severity, category: HarmCategory, dismissed: Boolean) {
        val f = flags[flagId] ?: return
        if (dismissed) {
            flags.remove(flagId)
            return
        }
        flags[flagId] = PendingFlag(severity, category, f.appSource, f.at, f.excerpt, f.caseId, f.sent)
        maybeAlert(flagId)
    }

    /** Phone only: the offending message, so the parent can read it (sealed with the family key). */
    fun onCapture(flagId: String, offendingText: String?) {
        flags[flagId]?.let { if (it.excerpt == null) it.excerpt = offendingText?.take(1000) }
    }

    /** Casefile sealed a flag into a case: now the alert has a case to point to. */
    fun onEvidenceSealed(flagId: String, caseId: String?) {
        val f = flags[flagId] ?: return
        if (caseId == null || f.caseId != null) return
        f.caseId = caseId
        maybeAlert(flagId)
    }

    fun onCaseUpdated(caseId: String, topSeverity: Severity) {
        caseSeverity[caseId] = topSeverity
    }

    fun onEscalated(caseId: String) {
        if (store.get("escalated.$caseId") != null) return
        store.put("escalated.$caseId", "1")
        val severity = caseSeverity[caseId] ?: Severity.HIGH
        if (!qualifies(severity)) return
        send(AlertRequest(caseId, severity, kind = AlertKind.ESCALATION, occurredAt = EventBus.iso(clock())))
    }

    /** SOS always goes through. */
    fun onSos(caseId: String, triggeredAt: String) {
        send(AlertRequest(caseId, Severity.HIGH, kind = AlertKind.SOS, occurredAt = triggeredAt))
    }

    /** High severity always alerts; otherwise the parent's threshold decides. */
    private fun qualifies(s: Severity): Boolean {
        if (!isPaired) return false
        if (s == Severity.HIGH) return true
        val min = policy?.thresholds?.alertMinSeverity ?: Severity.MEDIUM
        return s.ordinal >= min.ordinal
    }

    private fun maybeAlert(flagId: String) {
        val f = flags[flagId] ?: return
        val caseId = f.caseId ?: return
        if (f.sent || !qualifies(f.severity)) return
        f.sent = true
        send(
            AlertRequest(
                caseId = caseId,
                severity = f.severity,
                category = f.category,
                kind = AlertKind.FLAG,
                appSource = f.appSource,
                occurredAt = EventBus.iso(f.at),
                sealedExcerpt = f.excerpt?.let { text -> crypto?.seal(text) }
            )
        )
    }

    private fun send(request: AlertRequest) {
        val out = outbox()
        saveOutbox(out.copy(alerts = out.alerts + request))
        flush()
    }

    // ------------------------------------------------------------------ approvals

    fun onShareRequested(requestId: String, caseId: String, recipient: RecipientType) {
        if (!isPaired) return
        val out = outbox()
        saveOutbox(
            out.copy(
                approvals =
                out.approvals + ApprovalRequest(requestId, caseId, ApprovalKind.SHARE, recipient)
            )
        )
        flush()
    }

    private fun onDecision(d: ApprovalResult) {
        val c = crypto ?: return
        if (!c.verifyDecision(d.approvalId, d.requestId, d.decision.wire, d.signature)) {
            SafeLog.w("guardian.decision.bad_signature", LogField.id("approval_id", d.approvalId))
            return
        }
        if (store.get("decision.${d.requestId}") != null) return
        store.put("decision.${d.requestId}", d.decision.wire)
        if (d.kind != null && d.kind != ApprovalKind.SHARE) return
        if (d.decision == ApprovalDecision.APPROVE) {
            publisher.publish(
                Events.GUARDIAN_SHARE_APPROVED,
                GuardianShareApprovedPayload(d.requestId, d.caseId, d.decidedAt),
                d.caseId
            )
        } else {
            publisher.publish(
                Events.GUARDIAN_SHARE_DECLINED,
                GuardianShareDeclinedPayload(d.requestId, d.caseId, d.decidedAt),
                d.caseId
            )
        }
    }

    // ------------------------------------------------------------------ heartbeat and delivery

    /** Checks in: reports missing permissions, collects a newer policy and the parent's decisions. */
    fun heartbeat() {
        val a = api ?: return
        val device = serverDeviceId ?: return
        flush()
        val missing = missingPermissions()
        val r = a.heartbeat(
            HeartbeatRequest(device, appVersion, policy?.policyVersion ?: 0, missing.isEmpty(), missing)
        )
        r.policy?.let { p ->
            val current = policy
            if (current == null || p.policyVersion > current.policyVersion) {
                if (crypto?.verifyPolicy(p) == true) {
                    savePolicy(p)
                    publishPolicy(p)
                } else {
                    SafeLog.w("guardian.policy.bad_signature")
                }
            }
        }
        r.decisions?.forEach(::onDecision)
        r.reports?.forEach(::onReportOrder)
    }

    // ------------------------------------------------------------------ parent reports

    /**
     * The parent asked to report a case. Only a request the family key signed is accepted, so
     * the server cannot make the phone share anything. The child's name (sealed by the parent)
     * is opened here and stays on the phone, so Casefile can leave it out.
     */
    private fun onReportOrder(o: ReportOrder) {
        val c = crypto ?: return
        val message = FamilyCrypto.reportMessage(o.reportId, o.caseId, o.recipientType.wire, o.anonymous)
        if (!c.verify(message, o.signature)) {
            SafeLog.w("guardian.report.bad_signature", LogField.id("report_id", o.reportId))
            return
        }
        if (store.get("report.${o.reportId}") != null) return
        store.put("report.${o.reportId}", "requested")
        val name = o.sealedChildName?.let { runCatching { c.open(it).trim() }.getOrNull() }?.takeIf { it.isNotEmpty() }
        publisher.publish(
            Events.GUARDIAN_REPORT_REQUESTED,
            GuardianReportRequestedPayload(o.reportId, o.caseId, o.recipientType, o.anonymous, listOfNotNull(name)),
            o.caseId
        )
        SafeLog.i("guardian.report.requested", LogField.id("report_id", o.reportId))
    }

    /** Casefile built the report: send the link back sealed, so only the parent's phone can open it. */
    fun onReportReady(requestId: String, status: ReportOutcome, link: String?) {
        if (store.get("report.$requestId") != "requested") return
        store.put("report.$requestId", status.wire)
        val sealed = if (status == ReportOutcome.READY && link != null) crypto?.seal(link) else null
        val result = ReportResultRequest(if (sealed == null) ReportOutcome.FAILED else status, sealed)
        val out = outbox()
        saveOutbox(out.copy(reports = out.reports + PendingReportResult(requestId, result)))
        flush()
    }

    /** Sends queued alerts and approval requests. Unsent ones stay in guardian.db for the next try. */
    fun flush() {
        val a = api ?: return
        if (serverDeviceId == null) return
        var out = outbox()
        while (out.alerts.isNotEmpty()) {
            try {
                a.alert(out.alerts.first())
            } catch (e: java.io.IOException) {
                break
            }
            out = out.copy(alerts = out.alerts.drop(1))
            saveOutbox(out)
            SafeLog.i("guardian.alert.delivered")
        }
        while (out.approvals.isNotEmpty()) {
            try {
                a.approvalRequest(out.approvals.first())
            } catch (e: java.io.IOException) {
                break
            }
            out = out.copy(approvals = out.approvals.drop(1))
            saveOutbox(out)
        }
        while (out.reports.isNotEmpty()) {
            try {
                out.reports.first().let { a.reportResult(it.reportId, it.result) }
            } catch (e: java.io.IOException) {
                break
            }
            out = out.copy(reports = out.reports.drop(1))
            saveOutbox(out)
        }
    }

    val pendingAlerts get() = outbox().alerts.size

    // ------------------------------------------------------------------ internals

    private fun outbox(): Outbox =
        store.get("outbox")?.let { ContractJson.json.decodeFromString(Outbox.serializer(), it) } ?: Outbox()

    private fun saveOutbox(o: Outbox) = store.put("outbox", ContractJson.json.encodeToString(Outbox.serializer(), o))

    private fun savePolicy(p: Policy) = store.put("policy", ContractJson.json.encodeToString(Policy.serializer(), p))

    private fun publishPolicy(p: Policy) {
        val child = childId ?: return
        publisher.publish(
            Events.GUARDIAN_POLICY_UPDATED,
            GuardianPolicyUpdatedPayload(child, p.ageBracket, p.watchedApps, p.thresholds, p.policyVersion)
        )
    }
}
