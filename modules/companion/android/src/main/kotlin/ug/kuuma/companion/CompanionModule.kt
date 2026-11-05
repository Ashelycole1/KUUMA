package ug.kuuma.companion

import ug.kuuma.contracts.CompanionAdviceShownPayload
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot
import ug.kuuma.core.permissions.GrantKind
import ug.kuuma.core.permissions.KuumaPermission

/**
 * Companion module skeleton from Core. Screens live in the kuuma_companion Flutter package (modules/companion/flutter).
 *
 * Registered by the child app's module registry. Publish only `companion.*` events through
 * [ModuleContext.publisher]; the bus refuses anything else.
 */
class CompanionModule : KuumaModule {
    override val namespace = Namespace.COMPANION
    override val displayName = "Companion"

    override val permissions = listOf(
        KuumaPermission(
            "android.permission.BIND_INPUT_METHOD",
            Namespace.COMPANION,
            "companion.permission.keyboard.reason",
            GrantKind.SPECIAL_ACCESS,
            required = false
        )
    )

    override val screens = listOf(
        ScreenSlot("companion.home", "child.tab", "companion.tab.title")
    )

    lateinit var context: ModuleContext
        private set

    override fun onRegister(context: ModuleContext) {
        this.context = context
        SafeLog.i("companion.registered", LogField.bool("hello", true))

        context.publisher.publish(
            Events.COMPANION_ADVICE_SHOWN,
            CompanionAdviceShownPayload(flagId = SAMPLE_ID, adviceKey = "advice.general.you_are_not_alone")
        )
    }

    companion object {
        const val SAMPLE_ID = "00000000-0000-4000-8000-000000000001"
    }
}
