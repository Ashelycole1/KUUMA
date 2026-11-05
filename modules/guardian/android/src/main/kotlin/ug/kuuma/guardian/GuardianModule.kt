package ug.kuuma.guardian

import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Thresholds
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot
import ug.kuuma.core.permissions.KuumaPermission

/**
 * Guardian module skeleton from Core. Parent screens live in kuuma_guardian (modules/guardian/flutter) and show in the parent app.
 *
 * Registered by the child app's module registry. Publish only `guardian.*` events through
 * [ModuleContext.publisher]; the bus refuses anything else.
 */
class GuardianModule : KuumaModule {
    override val namespace = Namespace.GUARDIAN
    override val displayName = "Guardian"

    override val permissions = listOf(
        KuumaPermission(
            "android.permission.POST_NOTIFICATIONS",
            Namespace.GUARDIAN,
            "guardian.permission.notifications.reason",
            minSdk = 33
        )
    )

    override val screens = listOf(
        ScreenSlot("guardian.pairing", "child.settings", "guardian.pairing.title")
    )

    lateinit var context: ModuleContext
        private set

    override fun onRegister(context: ModuleContext) {
        this.context = context
        SafeLog.i("guardian.registered", LogField.bool("hello", true))

        context.publisher.publish(
            Events.GUARDIAN_POLICY_UPDATED,
            GuardianPolicyUpdatedPayload(
                childId = SAMPLE_ID,
                ageBracket = AgeBracket.AGE_PLACEHOLDER_B,
                watchedApps = listOf(AppSource.WHATSAPP, AppSource.SMS),
                thresholds = Thresholds(0.6, Severity.MEDIUM, Severity.MEDIUM),
                policyVersion = 1
            )
        )
    }

    companion object {
        const val SAMPLE_ID = "00000000-0000-4000-8000-000000000001"
    }
}
