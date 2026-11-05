package ug.kuuma.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.module.ModuleRegistry

class CompanionModuleTest {
    @Test fun `registers and publishes a contract-valid sample on its own topic`() {
        val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        val published = mutableListOf<String>()
        bus.subscribeAll { type, env ->
            @Suppress("UNCHECKED_CAST")
            published += ContractJson.encode(
                type as ug.kuuma.contracts.EventType<ug.kuuma.contracts.EventPayload>,
                env as ug.kuuma.contracts.Envelope<ug.kuuma.contracts.EventPayload>,
                ContractJson.strict
            )
        }
        ModuleRegistry(bus).register(CompanionModule())
        val json = published.single()
        val type = ContractAssert.validateEventJson(json)
        assertEquals(Namespace.COMPANION, type.publisher)
    }
}
