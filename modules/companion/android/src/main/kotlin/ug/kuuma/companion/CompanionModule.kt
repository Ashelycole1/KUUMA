package ug.kuuma.companion

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import ug.kuuma.ai.runtime.DummyReplyRiskChecker
import ug.kuuma.companion.content.ContentPack
import ug.kuuma.contracts.AdviceKey
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.CompanionAdviceShownPayload
import ug.kuuma.contracts.CompanionCaptureRequestedPayload
import ug.kuuma.contracts.CompanionFeedbackSubmittedPayload
import ug.kuuma.contracts.CompanionSosTriggeredPayload
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.FeedbackVerdict
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.contracts.Uuid
import ug.kuuma.contracts.ai.ReplyRiskChecker
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot
import ug.kuuma.core.permissions.GrantKind
import ug.kuuma.core.permissions.KuumaPermission

/** Things the victim screens and the keyboard react to. Called on the bus thread: post to your own. */
interface CompanionListener {
    /** A new or revised flag: show this advice (or the crisis screens when [AdvicePlan.crisis]). */
    fun onAdvice(plan: AdvicePlan) {}

    /** The child entered or left a flagged conversation; null means no banner. */
    fun onKeyboardContext(context: KeyboardContext?) {}
}

/**
 * Companion: what the victim sees and touches. Reacts to Sentinel's flags; never reads other
 * apps and never stores evidence. It keeps only IDs and enums in memory, nothing the child types.
 *
 * The Kuuma Keyboard ([ug.kuuma.companion.keyboard.KuumaKeyboardService]) runs in the same process
 * and reaches the registered module through [current].
 */
class CompanionModule(
    pack: ContentPack = ContentPack.bundled(),
    /** On-device reply checker from the AI build. The app may swap in the real one. */
    @Volatile var replyChecker: ReplyRiskChecker = DummyReplyRiskChecker(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : KuumaModule {
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

    val advice = AdviceEngine(pack)

    private lateinit var context: ModuleContext
    private val listeners = CopyOnWriteArrayList<CompanionListener>()
    private val lock = Any()

    // State: IDs and enums only (see ActiveFlag). Bounded so a long session can't grow it.
    private val flags = LinkedHashMap<Uuid, ActiveFlag>()
    private var conversation: SentinelConversationActivePayload? = null
    private var lastEscalatedCaseId: Uuid? = null
    private var sosCaseId: Uuid? = null
    private val adviceShown = LinkedHashSet<String>()

    @Volatile var ageBracket: AgeBracket? = null
        private set

    /** Language the child reads Kuuma in; null means "the language of the flagged message". */
    @Volatile var preferredLanguage: Language? = null

    override fun onRegister(context: ModuleContext) {
        this.context = context
        current = this
        val ev = context.events

        ev.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { env ->
            val p = env.payload
            val flag = ActiveFlag(
                p.flagId,
                p.category,
                p.severity,
                p.language,
                p.appSource,
                p.conversationRef,
                env.caseId
            )
            remember(flag)
            emitAdvice(flag)
            refreshKeyboard()
        }
        ev.subscribe(Events.SENTINEL_FLAG_UPDATED) { env ->
            val p = env.payload
            val updated = synchronized(lock) {
                val old = flags[p.flagId] ?: return@synchronized null
                old.copy(severity = p.severity, category = p.category, caseId = env.caseId ?: old.caseId)
                    .also { flags[p.flagId] = it }
            } ?: return@subscribe // a flag from before this process started: nothing to revise
            emitAdvice(updated)
            refreshKeyboard()
        }
        ev.subscribe(Events.SENTINEL_CONVERSATION_ACTIVE) { env ->
            synchronized(lock) { conversation = env.payload }
            refreshKeyboard()
        }
        ev.subscribe(Events.GUARDIAN_POLICY_UPDATED) { env -> ageBracket = env.payload.ageBracket }
        ev.subscribe(Events.GUARDIAN_PAIRING_COMPLETED) { env ->
            if (ageBracket == null) ageBracket = env.payload.ageBracket
        }
        ev.subscribe(Events.CASEFILE_CASE_ESCALATED) { env ->
            synchronized(lock) {
                lastEscalatedCaseId =
                    env.payload.caseId
            }
        }

        SafeLog.i("companion.registered", LogField.num("pack_format", advice.pack.format))
    }

    fun addListener(listener: CompanionListener): () -> Unit {
        listeners += listener
        listener.onKeyboardContext(keyboardContext())
        return { listeners -= listener }
    }

    /** Replace the content pack (e.g. after a verified download). */
    fun usePack(pack: ContentPack) {
        advice.pack = pack
        refreshKeyboard()
    }

    // ---- reads ---------------------------------------------------------------------------

    fun flag(flagId: Uuid): ActiveFlag? = synchronized(lock) { flags[flagId] }

    fun recentFlags(): List<ActiveFlag> = synchronized(lock) { flags.values.reversed() }

    fun planFor(flagId: Uuid): AdvicePlan? = flag(flagId)?.let { plan(it) }

    fun keyboardContext(language: Language? = null): KeyboardContext? {
        val (conv, flag) = synchronized(lock) {
            val c = conversation ?: return null
            c to flags.values.lastOrNull { it.conversationRef == c.conversationRef }
        }
        if (!conv.hasFlags && flag == null) return null
        val lang = language ?: preferredLanguage ?: flag?.language ?: Language.EN
        return advice.keyboardContext(conv.conversationRef, conv.appSource, conv.topSeverity, flag, ageBracket, lang)
    }

    // ---- actions (the only events Companion publishes) -----------------------------------

    /** The child tapped the shield key: ask Sentinel to capture the open conversation. */
    fun requestCapture(): Boolean {
        val ref = synchronized(lock) { conversation?.conversationRef } ?: return false
        context.publisher.publish(
            Events.COMPANION_CAPTURE_REQUESTED,
            CompanionCaptureRequestedPayload(conversationRef = ref, requestedAt = EventBus.iso(clock()))
        )
        SafeLog.i("companion.capture.requested")
        return true
    }

    /**
     * Quiet SOS. Uses the case of the conversation's newest flag, else the last escalated case,
     * else a new case id that stays the same for this session so repeated presses join up.
     */
    fun triggerSos(): Uuid {
        val caseId = synchronized(lock) {
            val ref = conversation?.conversationRef
            flags.values.lastOrNull { it.conversationRef == ref && it.caseId != null }?.caseId
                ?: lastEscalatedCaseId
                ?: sosCaseId
                ?: newId().also { sosCaseId = it }
        }
        context.publisher.publish(
            Events.COMPANION_SOS_TRIGGERED,
            CompanionSosTriggeredPayload(caseId = caseId, triggeredAt = EventBus.iso(clock())),
            caseId = caseId
        )
        SafeLog.i("companion.sos.triggered", LogField.id("case_id", caseId))
        return caseId
    }

    /** "This isn't abuse" and other verdicts on a flag. */
    fun submitFeedback(flagId: Uuid, verdict: FeedbackVerdict, consentToTrain: Boolean) {
        context.publisher.publish(
            Events.COMPANION_FEEDBACK_SUBMITTED,
            CompanionFeedbackSubmittedPayload(flagId = flagId, verdict = verdict, consentToTrain = consentToTrain)
        )
        SafeLog.i("companion.feedback.submitted", LogField.id("flag_id", flagId), LogField.enum("verdict", verdict))
    }

    /** Call when a card is actually on screen. Published once per flag and card. */
    fun adviceShown(flagId: Uuid, key: AdviceKey) {
        val first = synchronized(lock) {
            adviceShown.add("$flagId|$key").also { trim(adviceShown, MAX_SHOWN) }
        }
        if (!first) return
        context.publisher.publish(Events.COMPANION_ADVICE_SHOWN, CompanionAdviceShownPayload(flagId, key))
    }

    // ---- internals -----------------------------------------------------------------------

    private fun plan(flag: ActiveFlag) = advice.plan(flag, ageBracket, preferredLanguage ?: flag.language)

    private fun remember(flag: ActiveFlag) = synchronized(lock) {
        flags.remove(flag.flagId)
        flags[flag.flagId] = flag
        while (flags.size > MAX_FLAGS) flags.remove(flags.keys.first())
    }

    private fun emitAdvice(flag: ActiveFlag) {
        val start = clock()
        val plan = plan(flag)
        listeners.forEach { it.onAdvice(plan) }
        SafeLog.i(
            "companion.advice.planned",
            LogField.id("flag_id", flag.flagId),
            LogField.enum("category", flag.category),
            LogField.bool("crisis", plan.crisis),
            LogField.ms("took", clock() - start)
        )
    }

    private fun refreshKeyboard() {
        val ctx = keyboardContext()
        listeners.forEach { it.onKeyboardContext(ctx) }
    }

    private fun <T> trim(set: LinkedHashSet<T>, max: Int) {
        while (set.size > max) set.remove(set.first())
    }

    companion object {
        private const val MAX_FLAGS = 50
        private const val MAX_SHOWN = 500

        /** The registered module, for the keyboard service (same process). */
        @Volatile var current: CompanionModule? = null
            private set
    }
}
