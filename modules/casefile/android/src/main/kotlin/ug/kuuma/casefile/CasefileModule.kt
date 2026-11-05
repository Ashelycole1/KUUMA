package ug.kuuma.casefile

import ug.kuuma.contracts.CasefileCaseUpdatedPayload
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.Severity
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot

/**
 * Casefile module skeleton from Core. Evidence lives in vault.db (context.openDatabase()).
 *
 * Registered by the child app's module registry. Publish only `casefile.*` events through
 * [ModuleContext.publisher]; the bus refuses anything else.
 */
class CasefileModule : KuumaModule {
    override val namespace = Namespace.CASEFILE
    override val displayName = "Casefile"

    override val screens = listOf(
        ScreenSlot("casefile.cases", "child.tab", "casefile.tab.title")
    )

    lateinit var context: ModuleContext
        private set

    override fun onRegister(context: ModuleContext) {
        this.context = context
        SafeLog.i("casefile.registered", LogField.bool("hello", true))

        context.publisher.publish(
            Events.CASEFILE_CASE_UPDATED,
            CasefileCaseUpdatedPayload(
                caseId = SAMPLE_ID,
                offenderLabel = "Person A",
                incidentCount = 0,
                topSeverity = Severity.LOW
            )
        )
    }

    companion object {
        const val SAMPLE_ID = "00000000-0000-4000-8000-000000000001"
    }
}
