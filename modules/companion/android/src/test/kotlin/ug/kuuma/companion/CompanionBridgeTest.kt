package ug.kuuma.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.Severity
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry

class CompanionBridgeTest {
    private class Sentinel : KuumaModule {
        override val namespace = Namespace.SENTINEL
        override val displayName = "sentinel"
        override val isFake = true
        lateinit var ctx: ModuleContext
        override fun onRegister(context: ModuleContext) {
            ctx = context
        }
    }

    private val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
    private val module = CompanionModule()
    private val sentinel = Sentinel()
    private val dialled = mutableListOf<String>()
    private val bridge: CompanionBridge
    private val published = mutableListOf<String>()

    init {
        ModuleRegistry(bus).apply {
            register(module)
            register(sentinel)
        }
        bus.subscribeAll { type, _ -> if (type.publisher == Namespace.COMPANION) published += type.type }
        bridge = CompanionBridge(module) { dialled += it }
    }

    private fun flag(category: HarmCategory) = sentinel.ctx.publisher.publish(
        Events.SENTINEL_MESSAGE_FLAGGED,
        SentinelMessageFlaggedPayload(
            "11111111-1111-4111-8111-111111111111",
            "or1_" + "m".repeat(43),
            AppSource.SMS,
            "or1_" + "c".repeat(43),
            "or1_" + "s".repeat(43),
            category,
            Severity.HIGH,
            0.9,
            Language.EN
        )
    )

    @Test fun `plans stream and list as the maps the Dart side decodes`() {
        val streamed = mutableListOf<Map<String, Any?>>()
        val stop = bridge.listen { streamed += it }
        flag(HarmCategory.GROOMING)
        stop()
        flag(HarmCategory.THREAT)

        val plan = streamed.single()
        assertEquals("grooming", plan["category"])
        assertEquals("sms", plan["appSource"])
        assertEquals(false, plan["crisis"])
        @Suppress("UNCHECKED_CAST")
        val cards = plan["cards"] as List<Map<String, Any?>>
        assertEquals("advice.grooming.secrets_are_a_warning", cards.first()["key"])
        assertTrue((cards.first()["steps"] as List<*>).isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val recent = bridge.handle("recent", emptyMap<Any, Any>()) as List<Map<String, Any?>>
        assertEquals("threat", recent.single()["category"])
    }

    @Test fun `actions publish through the module`() {
        bridge.handle(
            "feedback",
            mapOf(
                "flagId" to "11111111-1111-4111-8111-111111111111",
                "verdict" to "not_harmful",
                "consentToTrain" to false
            )
        )
        bridge.handle(
            "adviceShown",
            mapOf(
                "flagId" to "11111111-1111-4111-8111-111111111111",
                "adviceKey" to "advice.general.do_not_reply"
            )
        )
        assertEquals(false, bridge.handle("capture", emptyMap<Any, Any>()))
        assertTrue((bridge.handle("sos", emptyMap<Any, Any>()) as String).isNotEmpty())
        assertEquals(
            listOf("companion.feedback.submitted", "companion.advice.shown", "companion.sos.triggered"),
            published
        )
    }

    @Test fun `crisis content comes from the pack and only helplines can be dialled`() {
        val crisis = bridge.handle("crisis", emptyMap<Any, Any>()) as Map<*, *>
        assertFalse((crisis["title"] as String).isBlank())
        val number = ((crisis["helplines"] as List<*>).first() as Map<*, *>)["number"] as String
        bridge.handle("dial", mapOf("number" to number))
        assertEquals(listOf(number), dialled)
        try {
            bridge.handle("dial", mapOf("number" to "12345"))
            error("should refuse")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, dialled.size)
    }

    @Test(expected = CompanionBridge.UnknownMethod::class)
    fun `unknown methods are refused`() {
        bridge.handle("nope", emptyMap<Any, Any>())
    }
}
