package ug.kuuma.fakes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.ai.runtime.DummyHarmClassifier
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.module.ModuleRegistry

class FakesTest {
    private val refs = OpaqueRefs(ByteArray(32) { 7 })

    private fun playAll(name: String, configure: (FakeSet) -> Unit = {}): List<Pair<EventType<*>, String>> {
        val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        val registry = ModuleRegistry(bus)
        val fakes = FakeSet(DummyHarmClassifier(), refs)
        fakes.all.forEach(registry::register)
        configure(fakes)
        val out = mutableListOf<Pair<EventType<*>, String>>()
        bus.subscribeAll { t, e ->
            @Suppress("UNCHECKED_CAST")
            out +=
                t to ContractJson.encode(t as EventType<EventPayload>, e as Envelope<EventPayload>, ContractJson.strict)
        }
        fakes.player.play(ScenarioLibrary.load(name))
        return out
    }

    @Test fun `three starting scenarios are bundled`() {
        assertTrue(ScenarioLibrary.names().containsAll(listOf("luganda-insults", "english-threat", "slow-grooming")))
    }

    @Test fun `the dummy classifier agrees with every scenario's expectations`() {
        val classifier = DummyHarmClassifier()
        for (name in ScenarioLibrary.names()) {
            for (m in ScenarioLibrary.load(name).messages.filter { it.sender != "child" }) {
                val v = classifier.classify(m.text, ClassifyContext())
                val where = "$name: \"${m.text}\""
                if (m.expect == null) {
                    assertFalse("$where should not be flagged", v.harmful)
                } else {
                    assertTrue("$where should be flagged", v.harmful)
                    assertEquals(where, m.expect!!.category, v.category!!.wire)
                    assertEquals(where, m.expect!!.severity, v.severity!!.wire)
                }
            }
        }
    }

    @Test fun `every scenario drives the full chain with contract-valid events`() {
        for (name in ScenarioLibrary.names()) {
            val events = playAll(name)
            val types = events.map { it.first.type }.toSet()
            assertTrue(
                name,
                types.containsAll(
                    listOf(
                        "sentinel.message.flagged",
                        "companion.advice.shown",
                        "casefile.case.updated",
                        "guardian.alert.sent"
                    )
                )
            )
            events.forEach { (_, json) -> ContractAssert.validateEventJson(json) }
        }
    }

    @Test fun `luganda insults escalate exactly once`() {
        val events = playAll("luganda-insults")
        assertEquals(4, events.count { it.first.type == "sentinel.message.flagged" })
        assertEquals(1, events.count { it.first.type == "casefile.case.escalated" })
    }

    @Test fun `a fake can be switched off`() {
        val events = playAll("english-threat") { it.guardian.enabled = false }
        assertFalse(events.any { it.first.type.startsWith("guardian.") })
    }
}
