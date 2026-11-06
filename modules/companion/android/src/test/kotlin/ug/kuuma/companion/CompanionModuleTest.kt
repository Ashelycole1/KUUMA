package ug.kuuma.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.CasefileCaseEscalatedPayload
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EscalationReason
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.FeedbackVerdict
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.contracts.SentinelFlagUpdatedPayload
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry

/** Stands in for another build, just to publish its events in tests. */
private class Peer(override val namespace: Namespace) : KuumaModule {
    override val displayName = namespace.wire
    override val isFake = true
    lateinit var ctx: ModuleContext
    override fun onRegister(context: ModuleContext) {
        ctx = context
    }
}

class CompanionModuleTest {
    private val conv = "or1_" + "c".repeat(43)
    private val otherConv = "or1_" + "d".repeat(43)
    private val ref = "or1_" + "m".repeat(43)
    private val flagId = "11111111-1111-4111-8111-111111111111"
    private val caseId = "22222222-2222-4222-8222-222222222222"

    private lateinit var bus: EventBus
    private lateinit var companion: CompanionModule
    private lateinit var sentinel: Peer
    private lateinit var guardian: Peer
    private lateinit var casefile: Peer
    private val published = mutableListOf<Pair<EventType<*>, String>>()
    private val plans = mutableListOf<AdvicePlan>()
    private val contexts = mutableListOf<KeyboardContext?>()
    private val logs = mutableListOf<String>()

    @Before fun setUp() {
        SafeLog.sink = ug.kuuma.core.log.LogSink { _, line -> logs += line }
        bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        bus.subscribeAll { type, env ->
            @Suppress("UNCHECKED_CAST")
            if (type.publisher == Namespace.COMPANION) {
                published += type to ContractJson.encode(
                    type as EventType<EventPayload>,
                    env as Envelope<EventPayload>,
                    ContractJson.strict
                )
            }
        }
        val registry = ModuleRegistry(bus)
        companion = CompanionModule(clock = { 1_793_000_000_000 }, newId = { "33333333-3333-4333-8333-333333333333" })
        registry.register(companion)
        sentinel = Peer(Namespace.SENTINEL).also(registry::register)
        guardian = Peer(Namespace.GUARDIAN).also(registry::register)
        casefile = Peer(Namespace.CASEFILE).also(registry::register)
        companion.addListener(object : CompanionListener {
            override fun onAdvice(plan: AdvicePlan) {
                plans += plan
            }
            override fun onKeyboardContext(context: KeyboardContext?) {
                contexts += context
            }
        })
    }

    private fun flag(
        category: HarmCategory,
        severity: Severity = Severity.HIGH,
        conversation: String = conv,
        language: Language = Language.EN,
        case: String? = null,
        id: String = flagId
    ) = sentinel.ctx.publisher.publish(
        Events.SENTINEL_MESSAGE_FLAGGED,
        SentinelMessageFlaggedPayload(
            id,
            ref,
            AppSource.WHATSAPP,
            conversation,
            ref,
            category,
            severity,
            0.9,
            language
        ),
        caseId = case
    )

    private fun enterChat(conversation: String = conv, hasFlags: Boolean = true, top: Severity? = Severity.HIGH) =
        sentinel.ctx.publisher.publish(
            Events.SENTINEL_CONVERSATION_ACTIVE,
            SentinelConversationActivePayload(conversation, AppSource.WHATSAPP, hasFlags, top)
        )

    @Test fun `publishes nothing until something happens`() {
        assertTrue(published.isEmpty())
    }

    @Test fun `a flag produces advice for its category, in the child's language`() {
        flag(HarmCategory.THREAT)
        val plan = plans.single()
        assertEquals("advice.threat.tell_adult", plan.cards.first().key)
        assertFalse(plan.crisis)
        assertTrue(plan.cards.all { it.text.isNotBlank() })
        assertTrue("WhatsApp block steps", plan.blockSteps.isNotEmpty())
    }

    @Test fun `self-harm signals open the crisis screens`() {
        flag(HarmCategory.SELF_HARM_ENCOURAGEMENT, Severity.LOW)
        assertTrue(plans.single().crisis)
    }

    @Test fun `a missing translation falls back to English`() {
        flag(HarmCategory.INSULT, language = Language.LG)
        val plan = plans.single()
        assertEquals(Language.LG, plan.language)
        assertEquals(
            companion.advice.pack.adviceText(plan.cards.first().key, Language.EN, null),
            plan.cards.first().text
        )
    }

    @Test fun `flag updates revise the advice`() {
        flag(HarmCategory.INSULT, Severity.LOW)
        sentinel.ctx.publisher.publish(
            Events.SENTINEL_FLAG_UPDATED,
            SentinelFlagUpdatedPayload(flagId, Severity.HIGH, HarmCategory.GROOMING, emptyList())
        )
        assertEquals(2, plans.size)
        assertEquals("advice.grooming.secrets_are_a_warning", plans.last().cards.first().key)
    }

    @Test fun `keyboard banner appears in a flagged chat and goes in a clean one`() {
        flag(HarmCategory.THREAT)
        enterChat()
        val ctx = contexts.last()!!
        assertEquals(Severity.HIGH, ctx.severity)
        assertEquals("advice.threat.tell_adult", ctx.tipKey)
        assertTrue("threats get a nudge, not replies", ctx.noReply)

        enterChat(otherConv, hasFlags = false, top = null)
        assertNull(contexts.last())
    }

    @Test fun `insults allow calm replies`() {
        flag(HarmCategory.INSULT, Severity.MEDIUM)
        enterChat(top = Severity.MEDIUM)
        assertFalse(contexts.last()!!.noReply)
        assertTrue(companion.advice.pack.safeReplies(Language.EN).isNotEmpty())
    }

    @Test fun `banner still shows when the keyboard opens after the flag was raised elsewhere`() {
        enterChat(top = Severity.MEDIUM)
        val ctx = contexts.last()!!
        assertNull(ctx.category)
        assertEquals(AdviceEngine.FALLBACK_TIP, ctx.tipKey)
        assertTrue(ctx.noReply)
    }

    @Test fun `capture needs an open watched chat, then publishes a contract-valid event`() {
        assertFalse(companion.requestCapture())
        enterChat()
        assertTrue(companion.requestCapture())
        val (type, json) = published.single()
        assertEquals(Events.COMPANION_CAPTURE_REQUESTED, type)
        ContractAssert.validateEventJson(json)
        assertTrue(json.contains(conv))
    }

    @Test fun `SOS uses the flag's case, then the escalated case, then one session case`() {
        assertEquals("33333333-3333-4333-8333-333333333333", companion.triggerSos())
        casefile.ctx.publisher.publish(
            Events.CASEFILE_CASE_ESCALATED,
            CasefileCaseEscalatedPayload("44444444-4444-4444-8444-444444444444", "Person A", EscalationReason.MANUAL)
        )
        assertEquals("44444444-4444-4444-8444-444444444444", companion.triggerSos())
        flag(HarmCategory.THREAT, case = caseId)
        enterChat()
        assertEquals(caseId, companion.triggerSos())
        published.forEach { (_, json) -> ContractAssert.validateEventJson(json) }
        assertTrue(published.all { it.first == Events.COMPANION_SOS_TRIGGERED })
    }

    @Test fun `feedback and advice-shown are contract-valid, advice-shown once per card`() {
        flag(HarmCategory.INSULT)
        companion.submitFeedback(flagId, FeedbackVerdict.NOT_HARMFUL, consentToTrain = false)
        companion.adviceShown(flagId, "advice.general.do_not_reply")
        companion.adviceShown(flagId, "advice.general.do_not_reply")
        assertEquals(
            listOf(Events.COMPANION_FEEDBACK_SUBMITTED, Events.COMPANION_ADVICE_SHOWN),
            published.map {
                it.first
            }
        )
        published.forEach { (_, json) -> ContractAssert.validateEventJson(json) }
    }

    @Test fun `age bracket comes from the guardian policy`() {
        assertNull(companion.ageBracket)
        guardian.ctx.publisher.publish(
            Events.GUARDIAN_POLICY_UPDATED,
            GuardianPolicyUpdatedPayload(
                caseId,
                AgeBracket.AGE_13_15,
                listOf(AppSource.WHATSAPP),
                Thresholds(0.5, Severity.MEDIUM, Severity.HIGH),
                3
            )
        )
        assertEquals(AgeBracket.AGE_13_15, companion.ageBracket)
    }

    @Test fun `remembers a bounded number of flags`() {
        repeat(60) { i -> flag(HarmCategory.INSULT, id = "11111111-1111-4111-8111-%012d".format(i)) }
        assertEquals(50, companion.recentFlags().size)
        assertNotNull(companion.flag("11111111-1111-4111-8111-%012d".format(59)))
        assertNull(companion.flag("11111111-1111-4111-8111-%012d".format(0)))
    }

    @Test fun `logs carry only ids, enums, numbers and timings`() {
        flag(HarmCategory.THREAT)
        enterChat()
        companion.requestCapture()
        companion.triggerSos()
        assertTrue(logs.isNotEmpty())
        val pack = companion.advice.pack
        val texts = pack.adviceKeys(HarmCategory.THREAT, Severity.HIGH).mapNotNull {
            pack.adviceText(it, Language.EN, null)
        }
        assertTrue(logs.none { line -> texts.any { line.contains(it) } || line.contains(conv) })
    }
}
