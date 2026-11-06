package ug.kuuma.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.contracts.testing.ContractViolation

class ContractsTest {
    private val ref = "or1_" + "A".repeat(43)
    private val now = "2026-11-05T09:30:00Z"

    private fun flagged() = Envelope(
        eventId = "3f1c6a2e-8b7d-4c1e-9a2b-1d2e3f4a5b6c",
        type = "sentinel.message.flagged",
        version = 1,
        occurredAt = now,
        deviceId = "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b",
        payload = SentinelMessageFlaggedPayload(
            flagId = "f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c",
            messageRef = ref,
            appSource = AppSource.WHATSAPP,
            conversationRef = ref,
            senderRef = ref,
            category = HarmCategory.INSULT,
            severity = Severity.HIGH,
            confidence = 0.9,
            language = Language.LG
        )
    )

    @Test fun `there are 22 events`() = assertEquals(22, Events.all.size)

    @Test fun `valid event passes the schema and round-trips`() {
        val json = ContractAssert.assertPublishes(Namespace.SENTINEL, Events.SENTINEL_MESSAGE_FLAGGED, flagged())
        assertFalse("optional caseId must be omitted, not null", json.contains("caseId"))
        val back = ContractAssert.assertConsumes(Events.SENTINEL_MESSAGE_FLAGGED, json)
        assertEquals(flagged(), back)
    }

    @Test(expected = ContractViolation::class)
    fun `another namespace may not publish`() {
        ContractAssert.assertPublishes(Namespace.GUARDIAN, Events.SENTINEL_MESSAGE_FLAGGED, flagged())
    }

    @Test(expected = ContractViolation::class)
    fun `schema rejects out of range confidence`() {
        val bad = flagged().copy(payload = flagged().payload.copy(confidence = 1.5))
        ContractAssert.assertPublishes(Namespace.SENTINEL, Events.SENTINEL_MESSAGE_FLAGGED, bad)
    }

    @Test fun `phone-only events are typed phone-only`() {
        assertTrue(Events.SENTINEL_CAPTURE_READY.phoneOnly)
        val payloadIsPhoneOnly: Any = SentinelCaptureReadyPayload(
            flagId = "f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c",
            conversationRef = ref,
            messages = listOf(CapturedMessage(ref, ref, null, "x", now, false)),
            capturedAt = now
        )
        assertTrue(payloadIsPhoneOnly is PhoneOnlyPayload)
        Events.all.forEach { assertEquals(it.type.substringBefore('.'), it.publisher.wire) }
    }

    @Test fun `alias patterns work`() {
        assertTrue(ContractPatterns.OPAQUE_REF.matches(ref))
        assertTrue(ContractPatterns.TIMESTAMP.matches(now))
        assertTrue(ContractPatterns.ADVICE_KEY.matches("advice.threat.tell_adult"))
        assertFalse(ContractPatterns.OPAQUE_REF.matches("nope"))
    }

    @Test fun `enums keep wire names`() {
        assertEquals("sexual_harassment", HarmCategory.SEXUAL_HARASSMENT.wire)
        assertEquals(Language.NYN, Language.fromWire("nyn"))
    }
}
