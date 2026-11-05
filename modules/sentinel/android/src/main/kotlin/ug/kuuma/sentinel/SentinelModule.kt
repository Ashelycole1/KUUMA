package ug.kuuma.sentinel

import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.permissions.GrantKind
import ug.kuuma.core.permissions.KuumaPermission

/**
 * Sentinel module skeleton from Core. Phone only: Sentinel has no cloud API and no Flutter screens.
 *
 * Registered by the child app's module registry. Publish only `sentinel.*` events through
 * [ModuleContext.publisher]; the bus refuses anything else.
 */
class SentinelModule : KuumaModule {
    override val namespace = Namespace.SENTINEL
    override val displayName = "Sentinel"

    override val permissions = listOf(
        KuumaPermission(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            Namespace.SENTINEL,
            "sentinel.permission.accessibility.reason",
            GrantKind.SPECIAL_ACCESS
        ),
        KuumaPermission(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            Namespace.SENTINEL,
            "sentinel.permission.notifications.reason",
            GrantKind.SPECIAL_ACCESS
        )
    )

    lateinit var context: ModuleContext
        private set

    override fun onRegister(context: ModuleContext) {
        this.context = context
        SafeLog.i("sentinel.registered", LogField.bool("hello", true))

        // Hello-world: one sample event on our own topic. Replace with real detection.
        context.publisher.publish(
            Events.SENTINEL_CONVERSATION_ACTIVE,
            SentinelConversationActivePayload(
                conversationRef = SAMPLE_REF,
                appSource = AppSource.WHATSAPP,
                hasFlags = false
            )
        )
    }

    companion object {
        const val SAMPLE_REF = "or1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
