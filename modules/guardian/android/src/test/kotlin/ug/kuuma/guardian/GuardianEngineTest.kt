package ug.kuuma.guardian

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AlertKind
import ug.kuuma.contracts.AlertRequest
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.ApprovalDecision
import ug.kuuma.contracts.ApprovalKind
import ug.kuuma.contracts.ApprovalRequest
import ug.kuuma.contracts.ApprovalResult
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.HeartbeatRequest
import ug.kuuma.contracts.HeartbeatResponse
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.PairingRequest
import ug.kuuma.contracts.PairingResponse
import ug.kuuma.contracts.Policy
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.ReportOrder
import ug.kuuma.contracts.ReportOutcome
import ug.kuuma.contracts.ReportResultRequest
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.Base64Url
import ug.kuuma.core.log.LogSink
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry

/** A stand-in for the cloud: records calls, answers like /v1/guardian, can be switched off. */
private class FakeApi(private val pairing: () -> PairingResponse) : GuardianApi {
    var online = true
    var nextHeartbeat = HeartbeatResponse(1, "2026-11-05T10:00:00Z")
    val alerts = mutableListOf<AlertRequest>()
    val approvals = mutableListOf<ApprovalRequest>()
    val heartbeats = mutableListOf<HeartbeatRequest>()
    val pairings = mutableListOf<PairingRequest>()

    private fun check() {
        if (!online) throw IOException("offline")
    }

    override fun pair(request: PairingRequest): PairingResponse {
        check()
        pairings += request
        return pairing()
    }

    override fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        check()
        heartbeats += request
        return nextHeartbeat.also { nextHeartbeat = HeartbeatResponse(it.latestPolicyVersion, it.serverTime) }
    }

    override fun alert(request: AlertRequest) {
        check()
        alerts += request
    }

    override fun approvalRequest(request: ApprovalRequest) {
        check()
        approvals += request
    }

    val reportResults = mutableListOf<Pair<String, ReportResultRequest>>()

    override fun reportResult(reportId: String, request: ReportResultRequest) {
        check()
        reportResults += reportId to request
    }
}

private class Host : KuumaModule {
    override val namespace = Namespace.GUARDIAN
    override val displayName = "guardian-test"
    lateinit var ctx: ModuleContext
    override fun onRegister(context: ModuleContext) {
        ctx = context
    }
}

class GuardianEngineTest {
    private val familyKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val family = FamilyCrypto(familyKey)
    private val qr = "kuuma-pair:1:123456:" + Base64Url.encode(familyKey)
    private val childId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val deviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val caseId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val flagId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val requestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
    private val approvalId = "ffffffff-ffff-4fff-8fff-ffffffffffff"

    private var now = 1_793_000_000_000
    private val published = mutableListOf<Pair<EventType<*>, String>>()
    private val logs = mutableListOf<String>()
    private lateinit var api: FakeApi
    private lateinit var store: InMemoryGuardianStore
    private lateinit var engine: GuardianEngine
    private var missing = emptyList<String>()

    private fun policy(
        version: Long = 1,
        alertMin: Severity = Severity.MEDIUM,
        signedBy: FamilyCrypto? = family
    ): Policy {
        val p = Policy(
            AgeBracket.AGE_13_15,
            listOf(AppSource.WHATSAPP, AppSource.SMS),
            Thresholds(0.5, alertMin, Severity.MEDIUM),
            version
        )
        return signedBy?.let { p.copy(signature = it.sign(FamilyCrypto.policyMessage(p))) } ?: p
    }

    private var pairingPolicy = { policy() }

    @Before fun setUp() {
        SafeLog.sink = LogSink { _, line -> logs += line }
        val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        bus.subscribeAll { type, env ->
            @Suppress("UNCHECKED_CAST")
            if (type.publisher == Namespace.GUARDIAN) {
                published += type to ContractJson.encode(
                    type as EventType<EventPayload>,
                    env as Envelope<EventPayload>,
                    ContractJson.strict
                )
            }
        }
        val host = Host()
        ModuleRegistry(bus).register(host)
        api = FakeApi { PairingResponse(childId, deviceId, pairingPolicy()) }
        store = InMemoryGuardianStore()
        engine = GuardianEngine(host.ctx.publisher, store, api, { "device-public-key" }, { missing }, clock = { now })
    }

    private fun pair() = assertEquals(PairResult.PAIRED, engine.pair(qr))

    private fun decision(decision: ApprovalDecision, signedBy: FamilyCrypto = family): ApprovalResult {
        val at = "2026-11-05T10:05:00Z"
        return ApprovalResult(
            approvalId,
            requestId,
            caseId,
            decision,
            at,
            ApprovalKind.SHARE,
            signedBy.sign(FamilyCrypto.decisionMessage(approvalId, requestId, decision.wire))
        )
    }

    private fun reportOrder(signedBy: FamilyCrypto = family, anonymous: Boolean = true): ReportOrder {
        val id = "abababab-abab-4bab-8bab-abababababab"
        val sig = signedBy.sign(FamilyCrypto.reportMessage(id, caseId, RecipientType.POLICE.wire, anonymous))
        return ReportOrder(
            id,
            caseId,
            RecipientType.POLICE,
            anonymous,
            sig,
            family.seal("Amara"),
            "2026-11-06T09:00:00Z"
        )
    }

    @Test fun `a parent's signed report reaches Casefile with the child's name, and the link goes back sealed`() {
        pair()
        published.clear()
        val order = reportOrder()
        api.nextHeartbeat = HeartbeatResponse(1, "2026-11-06T09:00:00Z", reports = listOf(order))
        engine.heartbeat()
        val (type, json) = published.single()
        assertEquals("guardian.report.requested", type.type)
        assertTrue(json.contains("\"childNames\":[\"Amara\"]"))
        assertTrue(type.phoneOnly) // the name never leaves the phone
        // Delivered twice by mistake: built once.
        published.clear()
        api.nextHeartbeat = HeartbeatResponse(1, "2026-11-06T09:00:00Z", reports = listOf(order))
        engine.heartbeat()
        assertTrue(published.isEmpty())

        engine.onReportReady(order.reportId, ReportOutcome.READY, "http://kuuma/verify/s/x#k=secret")
        val (id, result) = api.reportResults.single()
        assertEquals(order.reportId, id)
        assertEquals(ReportOutcome.READY, result.status)
        assertEquals("http://kuuma/verify/s/x#k=secret", family.open(result.sealedLink!!))
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun `a report the family key did not sign is ignored`() {
        pair()
        published.clear()
        val forged = reportOrder(signedBy = FamilyCrypto(ByteArray(32) { 1 }))
        api.nextHeartbeat = HeartbeatResponse(1, "2026-11-06T09:00:00Z", reports = listOf(forged))
        engine.heartbeat()
        assertTrue(published.isEmpty())
        engine.onReportReady(forged.reportId, ReportOutcome.READY, "http://x#k=y")
        assertTrue(api.reportResults.isEmpty())
    }

    @Test fun `the family key signs, verifies and seals`() {
        val p = policy()
        assertTrue(family.verifyPolicy(p))
        assertFalse(family.verifyPolicy(p.copy(policyVersion = 2)))
        assertFalse(FamilyCrypto(ByteArray(32)).verifyPolicy(p))
        val sealed = family.seal("an offending message")
        assertTrue(Regex("^[A-Za-z0-9_-]{24,10000}$").matches(sealed))
        assertEquals("an offending message", family.open(sealed))
        assertEquals("age_13_15|sms,whatsapp|0.500|medium|medium|1", FamilyCrypto.policyMessage(p))
    }

    /** The same vectors are in kuuma_guardian's Dart tests: both phones must agree byte for byte. */
    @Test fun `matches the shared test vectors`() {
        assertEquals("AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw", Base64Url.encode(familyKey))
        assertEquals("1Z2SpGTAhTea1G_J43Odm0v-qGHapSnC8-dwt-zqCOs", policy().signature)
        assertEquals("Nkuba ku ssimu", family.open("AQIDBAUGBwgJCgsMHkkPkEcmYMuc3ecpeDg1Z9Np9K2c4ZXLkL4QaXM_"))
        val report = FamilyCrypto.reportMessage(
            "abababab-abab-4bab-8bab-abababababab",
            "c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a",
            "police",
            true
        )
        assertEquals("PU2xzpcn2JEBsap8txQTAKliN41666tZSX2ARLfISNo", family.sign(report))
    }

    @Test fun `pairing QR codes are parsed strictly`() {
        assertNotNull(PairingQr.parse(qr))
        assertNull(PairingQr.parse("kuuma-pair:1:12345:" + Base64Url.encode(familyKey)))
        assertNull(PairingQr.parse("https://example.org"))
        assertEquals(PairResult.BAD_CODE, engine.pair("not a kuuma code"))
        assertFalse(engine.isPaired)
    }

    @Test fun `pairing stores the policy and tells every module`() {
        pair()
        assertTrue(engine.isPaired)
        assertEquals(childId, engine.childId)
        assertEquals(
            listOf(Events.GUARDIAN_PAIRING_COMPLETED, Events.GUARDIAN_POLICY_UPDATED),
            published.map {
                it.first
            }
        )
        published.forEach { ContractAssert.validateEventJson(it.second) }
        assertEquals(PairResult.ALREADY_PAIRED, engine.pair(qr))
    }

    @Test fun `a policy the parent did not sign is refused at pairing`() {
        pairingPolicy = { policy(signedBy = FamilyCrypto(ByteArray(32) { 1 })) }
        assertEquals(PairResult.BAD_POLICY_SIGNATURE, engine.pair(qr))
        assertFalse(engine.isPaired)
        assertTrue(published.isEmpty())
    }

    @Test fun `pairing offline says so and keeps nothing`() {
        api.online = false
        assertEquals(PairResult.OFFLINE, engine.pair(qr))
        assertFalse(engine.isPaired)
    }

    @Test fun `the child cannot change the policy - only newer parent-signed ones are taken`() {
        pair()
        published.clear()
        api.nextHeartbeat = HeartbeatResponse(2, "2026-11-05T10:00:00Z", policy(2, signedBy = null))
        engine.heartbeat()
        assertEquals(1L, engine.policy!!.policyVersion)

        api.nextHeartbeat =
            HeartbeatResponse(2, "2026-11-05T10:00:00Z", policy(2, signedBy = FamilyCrypto(ByteArray(32))))
        engine.heartbeat()
        assertEquals(1L, engine.policy!!.policyVersion)
        assertTrue(published.isEmpty())

        api.nextHeartbeat = HeartbeatResponse(2, "2026-11-05T10:00:00Z", policy(2, Severity.HIGH))
        engine.heartbeat()
        assertEquals(2L, engine.policy!!.policyVersion)
        assertEquals(Events.GUARDIAN_POLICY_UPDATED, published.single().first)
    }

    @Test fun `announce repeats the stored policy at startup`() {
        pair()
        published.clear()
        engine.announce()
        assertEquals(Events.GUARDIAN_POLICY_UPDATED, published.single().first)
    }

    @Test fun `a high flag alerts once its case exists, with a sealed excerpt`() {
        pair()
        engine.onFlagged(flagId, Severity.HIGH, HarmCategory.THREAT, AppSource.WHATSAPP)
        engine.onCapture(flagId, "the offending message")
        assertTrue(api.alerts.isEmpty())
        engine.onEvidenceSealed(flagId, caseId)
        val alert = api.alerts.single()
        assertEquals(AlertKind.FLAG, alert.kind)
        assertEquals(caseId, alert.caseId)
        assertEquals(HarmCategory.THREAT, alert.category)
        assertEquals("the offending message", family.open(alert.sealedExcerpt!!))
        engine.onEvidenceSealed(flagId, caseId)
        assertEquals("one alert per flag", 1, api.alerts.size)
    }

    @Test fun `a flag that already carries a case alerts at once`() {
        pair()
        engine.onFlagged(flagId, Severity.HIGH, HarmCategory.SEXTORTION, AppSource.SMS, caseId)
        assertEquals(1, api.alerts.size)
    }

    @Test fun `high severity always alerts, lower ones follow the parent's threshold`() {
        api.nextHeartbeat = HeartbeatResponse(1, "2026-11-05T10:00:00Z")
        pairingPolicy = { policy(alertMin = Severity.HIGH) }
        pair()
        engine.onFlagged(flagId, Severity.MEDIUM, HarmCategory.INSULT, AppSource.SMS, caseId)
        assertTrue(api.alerts.isEmpty())
        engine.onFlagUpdated(flagId, Severity.HIGH, HarmCategory.THREAT, dismissed = false)
        assertEquals(Severity.HIGH, api.alerts.single().severity)
    }

    @Test fun `dismissed flags never alert`() {
        pair()
        engine.onFlagged(flagId, Severity.MEDIUM, HarmCategory.INSULT, AppSource.SMS)
        engine.onFlagUpdated(flagId, Severity.HIGH, HarmCategory.INSULT, dismissed = true)
        engine.onEvidenceSealed(flagId, caseId)
        assertTrue(api.alerts.isEmpty())
    }

    @Test fun `nothing is sent before pairing`() {
        engine.onFlagged(flagId, Severity.HIGH, HarmCategory.THREAT, AppSource.SMS, caseId)
        engine.onShareRequested(requestId, caseId, RecipientType.POLICE)
        assertTrue(api.alerts.isEmpty())
        assertTrue(api.approvals.isEmpty())
        assertEquals(0, engine.pendingAlerts)
    }

    @Test fun `SOS always goes through and escalations alert once`() {
        pair()
        engine.onSos(caseId, "2026-11-05T10:00:00Z")
        engine.onCaseUpdated(caseId, Severity.MEDIUM)
        engine.onEscalated(caseId)
        engine.onEscalated(caseId)
        assertEquals(listOf(AlertKind.SOS, AlertKind.ESCALATION), api.alerts.map { it.kind })
        assertEquals(Severity.HIGH, api.alerts.first().severity)
    }

    @Test fun `alerts wait in guardian db while offline and go on the next check-in`() {
        pair()
        api.online = false
        engine.onSos(caseId, "2026-11-05T10:00:00Z")
        assertEquals(1, engine.pendingAlerts)
        api.online = true
        engine.heartbeat()
        assertEquals(0, engine.pendingAlerts)
        assertEquals(AlertKind.SOS, api.alerts.single().kind)
    }

    @Test fun `heartbeat reports missing permissions`() {
        pair()
        missing = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE")
        engine.heartbeat()
        val hb = api.heartbeats.last()
        assertEquals(deviceId, hb.deviceId)
        assertEquals(false, hb.protectionActive)
        assertEquals(missing, hb.missingPermissions)
    }

    @Test fun `share requests go to the parent and signed decisions come back as events`() {
        pair()
        published.clear()
        engine.onShareRequested(requestId, caseId, RecipientType.POLICE)
        assertEquals(requestId, api.approvals.single().requestId)

        api.nextHeartbeat =
            HeartbeatResponse(1, "2026-11-05T10:00:00Z", decisions = listOf(decision(ApprovalDecision.APPROVE)))
        engine.heartbeat()
        val (type, json) = published.single()
        assertEquals(Events.GUARDIAN_SHARE_APPROVED, type)
        ContractAssert.validateEventJson(json)

        api.nextHeartbeat =
            HeartbeatResponse(1, "2026-11-05T10:00:00Z", decisions = listOf(decision(ApprovalDecision.APPROVE)))
        engine.heartbeat()
        assertEquals("a decision is acted on once", 1, published.size)
    }

    @Test fun `a decision the parent did not sign is ignored`() {
        pair()
        published.clear()
        api.nextHeartbeat = HeartbeatResponse(
            1,
            "2026-11-05T10:00:00Z",
            decisions = listOf(decision(ApprovalDecision.APPROVE, FamilyCrypto(ByteArray(32))))
        )
        engine.heartbeat()
        assertTrue(published.isEmpty())
        api.nextHeartbeat =
            HeartbeatResponse(1, "2026-11-05T10:00:00Z", decisions = listOf(decision(ApprovalDecision.DECLINE)))
        engine.heartbeat()
        assertEquals(Events.GUARDIAN_SHARE_DECLINED, published.single().first)
    }

    @Test fun `logs carry no message text or family key`() {
        pair()
        engine.onFlagged(flagId, Severity.HIGH, HarmCategory.THREAT, AppSource.WHATSAPP)
        engine.onCapture(flagId, "the offending message")
        engine.onEvidenceSealed(flagId, caseId)
        engine.heartbeat()
        assertTrue(logs.isNotEmpty())
        val key = Base64Url.encode(familyKey)
        assertTrue(logs.none { it.contains("offending") || it.contains(key) })
    }
}
