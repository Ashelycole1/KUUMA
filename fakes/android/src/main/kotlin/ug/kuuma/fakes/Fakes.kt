package ug.kuuma.fakes

import java.util.UUID
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.CapturedMessage
import ug.kuuma.contracts.CasefileCaseEscalatedPayload
import ug.kuuma.contracts.CasefileCaseUpdatedPayload
import ug.kuuma.contracts.CasefileEvidenceSealedPayload
import ug.kuuma.contracts.CompanionAdviceShownPayload
import ug.kuuma.contracts.EscalationReason
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GroomingStage
import ug.kuuma.contracts.GuardianAlertSentPayload
import ug.kuuma.contracts.GuardianPairingCompletedPayload
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.GuardianShareApprovedPayload
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelCaptureReadyPayload
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.contracts.SentinelFlagUpdatedPayload
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext

/**
 * Base fake for one module: emits that module's events with schema-valid sample data.
 * Switch on/off with [enabled] (the child app's debug settings do this per module).
 * Module owners keep their fake working as the real module grows (rule 6).
 */
abstract class BaseFake(final override val namespace: Namespace) : KuumaModule {
    override val displayName get() = "Fake ${namespace.wire}"
    override val isFake = true

    @Volatile var enabled = true
    protected lateinit var context: ModuleContext

    override fun onRegister(context: ModuleContext) {
        this.context = context
        subscribe()
    }

    protected open fun subscribe() {}

    protected fun <P : EventPayload> publish(type: EventType<P>, payload: P, caseId: String? = null) {
        if (enabled) context.publisher.publish(type, payload, caseId)
    }

    protected fun <P : EventPayload> on(type: EventType<P>, handler: (P, String?) -> Unit) {
        context.events.subscribe(type) { if (enabled) handler(it.payload, it.caseId) }
    }
}

/** Shared state the fakes use to agree on the current case (stands in for Casefile's vault). */
class FakeWorld {
    @Volatile var caseId: String? = null
}

/** Receives scripted messages as if Sentinel had read them from WhatsApp/SMS, and runs the classifier. */
class FakeSentinel(private val classifier: HarmClassifier, private val refs: OpaqueRefs) :
    BaseFake(Namespace.SENTINEL) {
    fun onIncoming(scenario: Scenario, index: Int, message: ScriptedMessage, sentAt: String) {
        val app = AppSource.fromWire(scenario.appSource)
        val conv = refs.conversationRef(scenario.appSource, scenario.conversation)
        val verdict = if (message.sender == "child") {
            null
        } else {
            classifier.classify(message.text, ClassifyContext(Language.fromWire(scenario.language), app))
        }
        val harmful = verdict?.harmful == true
        publish(
            Events.SENTINEL_CONVERSATION_ACTIVE,
            SentinelConversationActivePayload(conv, app, harmful, if (harmful) verdict!!.severity else null)
        )
        if (!harmful) return
        val flagId = UUID.randomUUID().toString()
        val msgRef = refs.messageRef(scenario.appSource, scenario.conversation, index.toString())
        val sender = refs.senderRef(scenario.appSource, message.sender)
        val category = verdict!!.category!!
        val severity = verdict.severity!!
        publish(
            Events.SENTINEL_MESSAGE_FLAGGED,
            SentinelMessageFlaggedPayload(
                flagId, msgRef, app, conv, sender, category, severity, verdict.confidence,
                Language.fromWire(
                    scenario.language
                )
            )
        )
        message.stage?.let {
            publish(
                Events.SENTINEL_FLAG_UPDATED,
                SentinelFlagUpdatedPayload(flagId, severity, category, listOf(GroomingStage.fromWire(it)))
            )
        }
        val displayName = scenario.participants[message.sender]?.displayName
        publish(
            Events.SENTINEL_CAPTURE_READY,
            SentinelCaptureReadyPayload(
                flagId,
                conv,
                listOf(CapturedMessage(msgRef, sender, displayName, message.text, sentAt, false)),
                null,
                sentAt
            )
        )
    }
}

class FakeCompanion : BaseFake(Namespace.COMPANION) {
    override fun subscribe() = on(Events.SENTINEL_MESSAGE_FLAGGED) { p, _ ->
        val key = when (p.category) {
            HarmCategory.THREAT -> "advice.threat.tell_adult"
            HarmCategory.GROOMING -> "advice.grooming.secrets_are_a_warning"
            HarmCategory.SEXTORTION -> "advice.sextortion.do_not_pay"
            else -> "advice.general.do_not_reply"
        }
        publish(Events.COMPANION_ADVICE_SHOWN, CompanionAdviceShownPayload(p.flagId, key))
    }
}

class FakeCasefile(private val world: FakeWorld) : BaseFake(Namespace.CASEFILE) {
    private var chainIndex = 0L
    private var incidents = 0L
    private var top = Severity.LOW
    private var escalated = false
    private var lastFlag: SentinelMessageFlaggedPayload? = null

    override fun subscribe() {
        on(Events.SENTINEL_MESSAGE_FLAGGED) { p, _ -> lastFlag = p }
        on(Events.SENTINEL_CAPTURE_READY) { p, _ ->
            val caseId = world.caseId ?: UUID.randomUUID().toString().also { world.caseId = it }
            val hash = Hashing.sha256Hex("${p.flagId}|$chainIndex".toByteArray())
            publish(
                Events.CASEFILE_EVIDENCE_SEALED,
                CasefileEvidenceSealedPayload(UUID.randomUUID().toString(), p.flagId, hash, chainIndex++),
                caseId
            )
            incidents++
            val sev = lastFlag?.severity ?: Severity.MEDIUM
            if (sev.ordinal > top.ordinal) top = sev
            publish(
                Events.CASEFILE_CASE_UPDATED,
                CasefileCaseUpdatedPayload(caseId, "Person A", incidents, top),
                caseId
            )
            if (!escalated && (top == Severity.HIGH || incidents >= 3)) {
                escalated = true
                val reason = if (lastFlag?.category ==
                    HarmCategory.GROOMING
                ) {
                    EscalationReason.GROOMING_DETECTED
                } else {
                    EscalationReason.SEVERITY_THRESHOLD
                }
                publish(
                    Events.CASEFILE_CASE_ESCALATED,
                    CasefileCaseEscalatedPayload(caseId, "Person A", reason),
                    caseId
                )
            }
        }
    }
}

class FakeGuardian : BaseFake(Namespace.GUARDIAN) {
    /**
     * A sample child, paired with a sample policy, for modules that react to Guardian's
     * policy. Called by tests or the debug console, never on its own, so it cannot overwrite
     * a real parent's policy.
     */
    fun announceSampleChild(childId: String = SAMPLE_CHILD, deviceId: String = SAMPLE_DEVICE) {
        val bracket = AgeBracket.AGE_13_15
        publish(Events.GUARDIAN_PAIRING_COMPLETED, GuardianPairingCompletedPayload(childId, deviceId, bracket))
        publish(
            Events.GUARDIAN_POLICY_UPDATED,
            GuardianPolicyUpdatedPayload(
                childId,
                bracket,
                listOf(AppSource.WHATSAPP, AppSource.SMS),
                Thresholds(0.5, Severity.MEDIUM, Severity.MEDIUM),
                1
            )
        )
    }

    override fun subscribe() {
        on(Events.CASEFILE_CASE_ESCALATED) { p, caseId ->
            publish(
                Events.GUARDIAN_ALERT_SENT,
                GuardianAlertSentPayload(UUID.randomUUID().toString(), p.caseId, Severity.HIGH),
                caseId
            )
        }
        // A fake parent who approves every share request at once.
        on(Events.CASEFILE_SHARE_REQUESTED) { p, caseId ->
            publish(
                Events.GUARDIAN_SHARE_APPROVED,
                GuardianShareApprovedPayload(p.requestId, p.caseId, EventBus.iso(System.currentTimeMillis())),
                caseId
            )
        }
    }

    companion object {
        const val SAMPLE_CHILD = "5a3c1e2d-7b4f-4a6e-9c8d-1f2e3d4c5b6a"
        const val SAMPLE_DEVICE = "6b4d2f3e-8c5a-4b7f-8d9e-2a3f4e5d6c7b"
    }
}

/**
 * Plays a scenario through the fakes. Message times are laid out from (now - scenario length)
 * so a three-day scenario looks like the last three days.
 */
class ScenarioPlayer(private val sentinel: FakeSentinel, private val clock: () -> Long = System::currentTimeMillis) {
    fun play(scenario: Scenario, delayMillis: Long = 0, onMessage: (Int) -> Unit = {}) {
        val days = scenario.messages.maxOf { it.day }
        val start = clock() - days * 24L * 3600 * 1000
        scenario.messages.forEachIndexed { i, m ->
            sentinel.onIncoming(scenario, i, m, EventBus.iso(start - start % 86_400_000 + m.offsetMillis()))
            onMessage(i)
            if (delayMillis > 0) Thread.sleep(delayMillis)
        }
    }
}

/** All four fakes, wired together, for the shell's debug settings. */
class FakeSet(classifier: HarmClassifier, refs: OpaqueRefs) {
    val world = FakeWorld()
    val sentinel = FakeSentinel(classifier, refs)
    val companion = FakeCompanion()
    val casefile = FakeCasefile(world)
    val guardian = FakeGuardian()
    val all: List<BaseFake> = listOf(sentinel, companion, casefile, guardian)
    val player = ScenarioPlayer(sentinel)
}
