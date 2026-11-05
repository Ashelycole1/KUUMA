package ug.kuuma.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.ai.runtime.DummyHarmClassifier
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AnalyseRequest
import ug.kuuma.contracts.AnalyseResponse
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.FeedbackRequest
import ug.kuuma.contracts.FeedbackVerdict
import ug.kuuma.contracts.GroomingStage
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelCaptureReadyPayload
import ug.kuuma.contracts.SentinelFlagUpdatedPayload
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry
import ug.kuuma.fakes.ScenarioLibrary
import ug.kuuma.sentinel.capture.ScreenNode
import ug.kuuma.sentinel.capture.ScreenSnapshot
import ug.kuuma.sentinel.cloud.AiGateway
import ug.kuuma.sentinel.cloud.DeviceState
import ug.kuuma.sentinel.ingest.NotificationSnapshot
import ug.kuuma.sentinel.store.InMemorySentinelStore

class SentinelEngineTest {
    private class Captured(val type: EventType<*>, val env: Envelope<*>)

    private class Host : KuumaModule {
        override val namespace = Namespace.SENTINEL
        override val displayName = "test"
        lateinit var ctx: ModuleContext
        override fun onRegister(context: ModuleContext) {
            ctx = context
        }
    }

    private class Gateway(var verdict: (AnalyseRequest) -> AnalyseResponse) : AiGateway {
        val analysed = mutableListOf<AnalyseRequest>()
        val feedback = mutableListOf<FeedbackRequest>()
        override fun analyse(request: AnalyseRequest) = verdict(request).also { analysed += request }
        override fun feedback(request: FeedbackRequest) {
            feedback += request
        }
        override fun latestLexicon() = error("unused")
        override fun download(url: String) = error("unused")
    }

    private var now = 1_762_300_000_000L
    private val events = mutableListOf<Captured>()
    private val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" }, clock = { now })
    private val store = InMemorySentinelStore()

    init {
        bus.subscribeAll { t, e -> events += Captured(t, e) }
    }

    private fun engine(classifier: HarmClassifier = DummyHarmClassifier(), gateway: AiGateway? = null): SentinelEngine {
        val host = Host()
        val registry = ModuleRegistry(bus)
        registry.register(host)
        return SentinelEngine(
            host.ctx.publisher,
            classifier,
            OpaqueRefs(
                ByteArray(32) {
                    3
                }
            ),
            store,
            gateway,
            clock = { now }
        )
    }

    private fun notification(pkg: String, title: String, text: String, at: Long = now) =
        NotificationSnapshot("key-$at", pkg, at, title, text)

    private inline fun <reified P : EventPayload> payloads(type: EventType<P>) = events.filter {
        it.type === type
    }.map { it.env.payload as P }

    @Suppress("UNCHECKED_CAST")
    private fun json(c: Captured) =
        ContractJson.encode(c.type as EventType<EventPayload>, c.env as Envelope<EventPayload>, ContractJson.strict)

    private fun pkgFor(app: String) = when (app) {
        "whatsapp" -> "com.whatsapp"
        "sms" -> "com.google.android.apps.messaging"
        "instagram" -> "com.instagram.android"
        else -> error(app)
    }

    @Test fun `correct flags on every Core scenario`() {
        for (name in ScenarioLibrary.names()) {
            events.clear()
            val e = engine()
            val s = ScenarioLibrary.load(name)
            e.onPolicy(
                GuardianPolicyUpdatedPayload(
                    "3f1c6a2e-8b7d-4c1e-9a2b-1d2e3f4a5b6c",
                    AgeBracket.AGE_PLACEHOLDER_B,
                    listOf(AppSource.fromWire(s.appSource)),
                    Thresholds(0.6, Severity.MEDIUM, Severity.MEDIUM),
                    1
                )
            )
            s.messages.forEachIndexed { i, m ->
                now += 60_000
                if (m.sender == "child") return@forEachIndexed
                e.onNotification(
                    notification(
                        pkgFor(s.appSource),
                        s.participants.getValue(m.sender).displayName,
                        m.text,
                        now + i
                    )
                )
            }
            val expected = s.messages.filter { it.expect != null }
            val flags = payloads(Events.SENTINEL_MESSAGE_FLAGGED)
            assertEquals(name, expected.size, flags.size)
            expected.zip(flags).forEach { (m, f) ->
                assertEquals(name, m.expect!!.category, f.category.wire)
                assertEquals(name, m.expect!!.severity, f.severity.wire)
            }
            assertEquals(name, flags.size, payloads(Events.SENTINEL_CAPTURE_READY).size)
            events.forEach { ContractAssert.validateEventJson(json(it)) }
        }
    }

    @Test fun `luganda scenario is detected as Luganda and captures the thread`() {
        val e = engine()
        e.onNotification(notification("com.whatsapp", "Classmate", "Wasuze otya? Homework y'olwaleero ebadde ki?"))
        now += 1000
        e.onNotification(notification("com.whatsapp", "Classmate", "Oli musiru nnyo, toyinza kumanya."))
        val flag = payloads(Events.SENTINEL_MESSAGE_FLAGGED).single()
        assertEquals(ug.kuuma.contracts.Language.LG, flag.language)
        val capture = payloads(Events.SENTINEL_CAPTURE_READY).single()
        assertEquals(flag.flagId, capture.flagId)
        assertEquals(2, capture.messages.size) // the earlier message is part of the thread
        assertEquals("Classmate", capture.messages.last().senderDisplayName)
    }

    @Test fun `apps outside the parent's policy are ignored`() {
        val e = engine()
        e.onNotification(notification("com.instagram.android", "Someone", "I will hurt you"))
        assertTrue(events.isEmpty())
    }

    @Test fun `nothing but phone-only events carries text or names`() {
        val e = engine()
        e.onNotification(
            notification("com.whatsapp", "Kato Mukasa", "Kato Mukasa says I will hurt you, call 0772 123 456")
        )
        for (c in events.filterNot { it.type.phoneOnly }) {
            val text = json(c)
            assertFalse(text, text.contains("Kato") || text.contains("772") || text.contains("hurt"))
        }
    }

    @Test fun `high risk flags are confirmed by the cloud, redacted`() {
        val gw =
            Gateway {
                AnalyseResponse(
                    Verdict(true, HarmCategory.THREAT, Severity.HIGH, 0.97),
                    listOf(GroomingStage.SECRECY),
                    "cloud-1"
                )
            }
        val e = engine(gateway = gw)
        e.onNotification(notification("com.whatsapp", "Kato Mukasa", "Kato Mukasa will hurt you. Call 0772 123 456"))
        e.cloud!!.pump(now, DeviceState())
        val sent = gw.analysed.single()
        assertFalse(sent.text, sent.text.contains("Kato") || sent.text.contains("772"))
        assertTrue(sent.text.contains("[CONTACT_1]") && sent.text.contains("[PHONE_1]"))
        val update = payloads(Events.SENTINEL_FLAG_UPDATED).single()
        assertEquals(listOf(GroomingStage.SECRECY), update.groomingSignals)
    }

    @Test fun `a cloud verdict of not harmful dismisses the flag`() {
        val gw = Gateway { AnalyseResponse(Verdict(false, confidence = 0.9), emptyList(), "cloud-1") }
        val e = engine(gateway = gw)
        e.onNotification(notification("com.whatsapp", "Kato", "haha I will hurt you at FIFA tonight"))
        e.cloud!!.pump(now, DeviceState())
        assertEquals(true, payloads(Events.SENTINEL_FLAG_UPDATED).single().dismissed)
    }

    @Test fun `uncertain messages are flagged only if the cloud agrees`() {
        val unsure = object : HarmClassifier {
            override val modelVersion = "unsure"
            override fun classify(text: String, context: ClassifyContext) =
                Verdict(true, HarmCategory.HARASSMENT, Severity.MEDIUM, 0.5)
        }
        val gw =
            Gateway { r ->
                if (r.text.contains(
                        "go away"
                    )
                ) {
                    AnalyseResponse(
                        Verdict(true, HarmCategory.HARASSMENT, Severity.MEDIUM, 0.8),
                        emptyList(),
                        "c"
                    )
                } else {
                    AnalyseResponse(Verdict(false, confidence = 0.9), emptyList(), "c")
                }
            }
        val e = engine(unsure, gw)
        e.onNotification(notification("com.whatsapp", "Kato", "go away nobody wants you"))
        now += 1000
        e.onNotification(notification("com.whatsapp", "Kato", "see you at lunch"))
        assertTrue(payloads(Events.SENTINEL_MESSAGE_FLAGGED).isEmpty()) // nothing until the cloud answers
        e.cloud!!.pump(now, DeviceState())
        assertEquals(1, payloads(Events.SENTINEL_MESSAGE_FLAGGED).size)
    }

    @Test fun `on-device screening keeps working when the cloud is held`() {
        val gw = Gateway { error("must not be called") }
        val e = engine(gateway = gw)
        e.onNotification(notification("com.whatsapp", "Kato", "I will hurt you"))
        e.cloud!!.pump(now, DeviceState(batteryPercent = 5, charging = false))
        assertEquals(1, payloads(Events.SENTINEL_MESSAGE_FLAGGED).size)
        assertEquals(1, e.cloud!!.size())
    }

    @Test fun `feedback dismisses, lowers the contact's score and is forwarded with consent`() {
        val gw = Gateway { AnalyseResponse(Verdict(true, HarmCategory.INSULT, Severity.HIGH, 0.95), emptyList(), "c") }
        val e = engine(gateway = gw)
        e.onNotification(notification("com.whatsapp", "Kato", "you are stupid"))
        val flag = payloads(Events.SENTINEL_MESSAGE_FLAGGED).single()
        e.onFeedback(flag.flagId, FeedbackVerdict.NOT_HARMFUL, consentToTrain = true)
        assertEquals(true, payloads(Events.SENTINEL_FLAG_UPDATED).last().dismissed)
        assertEquals(1, store.stats(flag.senderRef)!!.dismissed)
        e.cloud!!.pump(now, DeviceState())
        assertEquals(flag.flagId, gw.feedback.single().flagId)
        e.onFeedback(flag.flagId, FeedbackVerdict.CORRECT, consentToTrain = false)
        assertEquals(1, gw.feedback.size)
    }

    @Test fun `backup capture on request and screen capture when the chat is opened`() {
        val e = engine()
        e.onNotification(notification("com.whatsapp", "Kato", "Oli musiru nnyo"))
        val flag = payloads(Events.SENTINEL_MESSAGE_FLAGGED).single()
        events.clear()
        e.onCaptureRequested(flag.conversationRef)
        assertEquals(flag.flagId, payloads(Events.SENTINEL_CAPTURE_READY).single().flagId)

        events.clear()
        fun row(text: String, out: Boolean = false) = ScreenNode(
            "com.whatsapp:id/main_layout",
            children = listOfNotNull(
                ScreenNode("com.whatsapp:id/message_text", text),
                if (out) ScreenNode("com.whatsapp:id/status") else null
            )
        )
        val screen =
            ScreenNode(
                children = listOf(
                    ScreenNode("com.whatsapp:id/conversation_contact_name", "Kato"),
                    row("earlier message"),
                    row("Oli musiru nnyo"),
                    row("Leka", true)
                )
            )
        e.onScreen(ScreenSnapshot("com.whatsapp", screen, now))
        val active = payloads(Events.SENTINEL_CONVERSATION_ACTIVE).single()
        assertTrue(active.hasFlags)
        assertEquals(Severity.HIGH, active.topSeverity)
        val capture: SentinelCaptureReadyPayload = payloads(Events.SENTINEL_CAPTURE_READY).single()
        assertEquals(listOf("earlier message", "Oli musiru nnyo", "Leka"), capture.messages.map { it.text })
        assertTrue(capture.messages.last().fromChild)

        // Re-reading the same chat does not re-publish.
        events.clear()
        e.onScreen(ScreenSnapshot("com.whatsapp", screen, now + 5_000))
        assertTrue(events.isEmpty())
    }

    @Test fun `stored data holds no text`() {
        val e = engine()
        e.onNotification(notification("com.whatsapp", "Kato", "I will hurt you"))
        val f = payloads(Events.SENTINEL_MESSAGE_FLAGGED).single()
        val record = store.flag(f.flagId)!!
        assertFalse(record.toString().contains("hurt") || record.toString().contains("Kato"))
        val updated: List<SentinelFlagUpdatedPayload> = payloads(Events.SENTINEL_FLAG_UPDATED)
        assertTrue(updated.isEmpty())
        val flagged: List<SentinelMessageFlaggedPayload> = payloads(Events.SENTINEL_MESSAGE_FLAGGED)
        assertEquals(1, flagged.size)
    }
}

class SentinelModuleTest {
    @Test fun `module screens notifications on its own thread`() {
        val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        val flagged = mutableListOf<SentinelMessageFlaggedPayload>()
        bus.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { flagged += it.payload }
        val module = SentinelModule(SentinelConfig(DummyHarmClassifier(), OpaqueRefs(ByteArray(32) { 9 })))
        ModuleRegistry(bus).register(module)
        module.submitNotification(NotificationSnapshot("k", "com.whatsapp", 1, "Kato", "I will hurt you"))
        module.awaitIdle()
        assertEquals(HarmCategory.THREAT, flagged.single().category)
        assertEquals(module, SentinelModule.current)
    }
}
