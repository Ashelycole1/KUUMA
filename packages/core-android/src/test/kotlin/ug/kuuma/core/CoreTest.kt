package ug.kuuma.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GuardianAlertSentPayload
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.CloudGate
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.bus.PhoneOnlyException
import ug.kuuma.core.bus.TopicOwnershipException
import ug.kuuma.core.crypto.Base64Url
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.LogSink
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry
import ug.kuuma.core.permissions.KuumaPermission
import ug.kuuma.core.redact.BasicRedactor

class CoreTest {
    private val device = "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b"
    private val refs = OpaqueRefs(ByteArray(32) { it.toByte() })

    private class Captured(val type: EventType<*>, val env: Envelope<*>)

    private class TestModule(override val namespace: Namespace, val perms: List<KuumaPermission> = emptyList()) :
        KuumaModule {
        override val displayName = namespace.wire
        override val permissions get() = perms
        lateinit var ctx: ModuleContext
        override fun onRegister(context: ModuleContext) {
            ctx = context
        }
    }

    private fun alertPayload() = GuardianAlertSentPayload(
        alertId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
        caseId = "c0a5e1d2-3b4c-4d5e-8f6a-7b8c9d0e1f2a",
        severity = Severity.HIGH
    )

    @Test fun `bus delivers typed events and they match the contract`() {
        val bus = EventBus({ device })
        val registry = ModuleRegistry(bus)
        val guardian = TestModule(Namespace.GUARDIAN).also(registry::register)
        val got = mutableListOf<Captured>()
        bus.subscribeAll { t, e -> got += Captured(t, e) }
        var typed: GuardianAlertSentPayload? = null
        bus.subscribe(Events.GUARDIAN_ALERT_SENT) { typed = it.payload }

        val env = guardian.ctx.publisher.publish(Events.GUARDIAN_ALERT_SENT, alertPayload())

        assertEquals(Severity.HIGH, typed!!.severity)
        assertEquals(1, got.size)
        ContractAssert.assertPublishes(Namespace.GUARDIAN, Events.GUARDIAN_ALERT_SENT, env)
    }

    @Test(expected = TopicOwnershipException::class)
    fun `a module cannot publish another module's topic`() {
        val registry = ModuleRegistry(EventBus({ device }))
        val companion = TestModule(Namespace.COMPANION).also(registry::register)
        companion.ctx.publisher.publish(Events.GUARDIAN_ALERT_SENT, alertPayload())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two real modules cannot claim one namespace`() {
        val registry = ModuleRegistry(EventBus({ device }))
        registry.register(TestModule(Namespace.SENTINEL))
        registry.register(TestModule(Namespace.SENTINEL))
    }

    @Test fun `phone-only events cannot pass the cloud gate`() {
        val registry = ModuleRegistry(EventBus({ device }))
        val sentinel = TestModule(Namespace.SENTINEL).also(registry::register)
        val env = sentinel.ctx.publisher.publish(
            Events.SENTINEL_CONVERSATION_ACTIVE,
            SentinelConversationActivePayload(refs.conversationRef("whatsapp", "c1"), AppSource.WHATSAPP, false)
        )
        try {
            @Suppress("UNCHECKED_CAST")
            CloudGate.toWire(
                Events.SENTINEL_CONVERSATION_ACTIVE as EventType<EventPayload>,
                env as Envelope<EventPayload>
            )
            error("should have thrown")
        } catch (e: PhoneOnlyException) {
            assertTrue(e.message!!.contains("phone only"))
        }
        val guardian = TestModule(Namespace.GUARDIAN).also(registry::register)
        val ok = guardian.ctx.publisher.publish(Events.GUARDIAN_ALERT_SENT, alertPayload())
        assertTrue(CloudGate.toWire(Events.GUARDIAN_ALERT_SENT, ok).contains("guardian.alert.sent"))
    }

    @Test fun `opaque refs match the contract pattern and are stable`() {
        val r = refs.senderRef("whatsapp", "+256772123456")
        assertTrue(r, Regex("^or1_[A-Za-z0-9_-]{43}$").matches(r))
        assertEquals(r, refs.senderRef("whatsapp", "+256772123456"))
        assertFalse(r.contains("772"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc".toByteArray())
        )
    }

    @Test fun `base64url round trips`() {
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 37).toByte() }
            assertTrue(bytes.contentEquals(Base64Url.decode(Base64Url.encode(bytes))))
            assertTrue(bytes.contentEquals(Base64Url.decode(Base64Url.encodeStd(bytes))))
        }
    }

    @Test fun `redactor replaces names numbers and emails`() {
        val r = BasicRedactor().redact(
            "Kato said call +256 772 123456 or 0772123456, or mail kato@example.com. Kato!",
            knownNames = listOf("Kato")
        )
        assertEquals("[CONTACT_1] said call [PHONE_1] or [PHONE_2], or mail [EMAIL_1]. [CONTACT_1]!", r.text)
        assertEquals("+256 772 123456", r.replacements["[PHONE_1]"])
    }

    @Test fun `redactor leaves short numbers and dates alone`() {
        assertEquals(
            "Meet at 16:05 on 2026-11-05, room 12",
            BasicRedactor().redact("Meet at 16:05 on 2026-11-05, room 12").text
        )
    }

    @Test fun `permission registry enforces module prefixes`() {
        val registry = ModuleRegistry(EventBus({ device }))
        registry.register(
            TestModule(
                Namespace.SENTINEL,
                listOf(
                    KuumaPermission(
                        "android.permission.BIND_ACCESSIBILITY_SERVICE",
                        Namespace.SENTINEL,
                        "sentinel.permission.accessibility.reason"
                    )
                )
            )
        )
        assertEquals(1, registry.permissions.forModule(Namespace.SENTINEL).size)
        try {
            registry.register(
                TestModule(Namespace.GUARDIAN, listOf(KuumaPermission("x", Namespace.GUARDIAN, "sentinel.bad")))
            )
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `safe log refuses free text where an id is expected`() {
        val lines = mutableListOf<String>()
        SafeLog.sink = LogSink { _, l -> lines += l }
        SafeLog.i(
            "bus.event",
            LogField.id("event_id", "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"),
            LogField.enum("severity", Severity.HIGH)
        )
        assertEquals("bus.event event_id=a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d severity=high", lines.single())
        try {
            LogField.id("sender", "Call me on 0772123456")
            error("should have thrown")
        } catch (_: IllegalArgumentException) {
        }
    }
}
