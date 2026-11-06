package ug.kuuma.sentinel

import java.util.UUID
import ug.kuuma.contracts.AnalyseRequest
import ug.kuuma.contracts.AnalyseResponse
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.CapturedMessage
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.FeedbackRequest
import ug.kuuma.contracts.FeedbackVerdict
import ug.kuuma.contracts.GuardianPolicyUpdatedPayload
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.SentinelCaptureReadyPayload
import ug.kuuma.contracts.SentinelConversationActivePayload
import ug.kuuma.contracts.SentinelFlagUpdatedPayload
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.Verdict
import ug.kuuma.contracts.VerdictSource
import ug.kuuma.contracts.ai.ClassifyContext
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.bus.ModulePublisher
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.sentinel.capture.ConversationBuffer
import ug.kuuma.sentinel.capture.ScreenParser
import ug.kuuma.sentinel.capture.ScreenSnapshot
import ug.kuuma.sentinel.cloud.AiGateway
import ug.kuuma.sentinel.cloud.CloudJob
import ug.kuuma.sentinel.cloud.CloudQueue
import ug.kuuma.sentinel.ingest.Deduplicator
import ug.kuuma.sentinel.ingest.IncomingMessage
import ug.kuuma.sentinel.ingest.NotificationParser
import ug.kuuma.sentinel.ingest.NotificationSnapshot
import ug.kuuma.sentinel.ingest.WatchedApps
import ug.kuuma.sentinel.normalize.LanguageGuesser
import ug.kuuma.sentinel.normalize.Normaliser
import ug.kuuma.sentinel.screen.FlagRecord
import ug.kuuma.sentinel.screen.Route
import ug.kuuma.sentinel.screen.Screening
import ug.kuuma.sentinel.screen.SentinelPolicy
import ug.kuuma.sentinel.screen.SentinelRedactor
import ug.kuuma.sentinel.store.SentinelStore

/**
 * Sentinel's pipeline, free of Android so it is unit-tested end to end:
 *
 *   notification -> parse -> dedupe -> normalise -> context -> classify (on device)
 *     -> flag now / flag and ask the cloud to confirm / ask the cloud first
 *     -> capture the thread (from memory now, from the screen when the chat is opened)
 *
 * Not thread-safe by design: the module calls it from one background thread.
 */
class SentinelEngine(
    private val publisher: ModulePublisher,
    private val classifier: HarmClassifier,
    private val refs: OpaqueRefs,
    private val store: SentinelStore,
    gateway: AiGateway? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    val normaliser: Normaliser = Normaliser(),
    private val redactor: SentinelRedactor = SentinelRedactor()
) {
    var policy = SentinelPolicy()
        private set

    val buffer = ConversationBuffer()
    private val dedupe = Deduplicator()
    val cloud: CloudQueue? = gateway?.let { CloudQueue(it, ::onCloudVerdict) }

    private data class Pending(val message: IncomingMessage, val language: Language, val flagId: String?)
    private data class Chat(val app: AppSource, val key: String)
    private data class PendingCapture(val flagId: String, val until: Long)

    private val pending = HashMap<String, Pending>()
    private val chats = HashMap<String, Chat>()
    private val screenCaptures = HashMap<String, PendingCapture>()

    /** Chats whose notification text Android hid: screen them from the screen when opened. */
    private val screenOnOpen = HashMap<String, Long>()
    private var activeConversation: String? = null

    // ------------------------------------------------------------------ inputs

    fun onNotification(n: NotificationSnapshot) {
        val app = WatchedApps.appFor(n.packageName) ?: return
        if (app !in policy.watchedApps) return
        for (m in NotificationParser.parse(n, app)) if (isNew(m)) onMessage(m)
    }

    /**
     * Screen each message once. Messaging apps re-post old messages in every new notification,
     * the phone re-reads active notifications when Nilinde restarts, and hidden chats are read
     * again from the screen: without this, one message would raise a new flag (and a new parent
     * alert) every time. The key leaves the sender out, because notifications and the screen
     * name the sender differently.
     */
    private fun isNew(m: IncomingMessage): Boolean {
        if (!dedupe.isNew(m)) return false
        val key = refs.messageRef(
            m.appSource.wire,
            m.conversationKey,
            "screened|" + Hashing.sha256Hex(m.text.trim().toByteArray())
        )
        return store.markScreened(key, clock(), SCREENED_WINDOW)
    }

    /**
     * Android 15+ hides the text of notifications it thinks are sensitive (any message with a
     * number that looks like a code or phone number) from apps like Nilinde. Such a message is
     * exactly the kind that can carry a threat, so when the child opens that chat, Nilinde screens
     * what is on the screen instead (read-only, through the accessibility service).
     */
    fun onHiddenNotification(n: NotificationSnapshot) {
        val app = WatchedApps.appFor(n.packageName) ?: return
        if (app !in policy.watchedApps) return
        val chat = (n.conversationTitle ?: n.title)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        screenOnOpen[conversationRef(app, chat)] = clock() + SCREEN_CAPTURE_WINDOW
        SafeLog.i("sentinel.notification.hidden", LogField.enum("app", app))
    }

    fun onMessage(m: IncomingMessage) {
        buffer.add(m)
        val conversationRef = conversationRef(m.appSource, m.conversationKey)
        chats[conversationRef] = Chat(m.appSource, m.conversationKey)
        val senderRef = senderRef(m)
        store.recordMessage(senderRef, m.fromChild, m.sentAt)
        if (m.fromChild) return

        val normalised = normaliser.normalise(m.text)
        val language = LanguageGuesser.guess(normalised)
        val stats = store.stats(senderRef)
        val context = ClassifyContext(
            language = language,
            appSource = m.appSource,
            previousMessages = buffer.previousTexts(m).map(normaliser::normalise),
            ageBracket = policy.ageBracket,
            contact = stats?.toContext(clock())
        )
        val started = clock()
        val verdict = classifier.classify(normalised, context)
        val route = Screening.route(verdict, Screening.threshold(policy, stats))
        SafeLog.d("sentinel.screened", LogField.enum("route", route), LogField.ms("took", clock() - started))

        when (route) {
            Route.CLEAR -> Unit
            Route.FLAG -> flag(m, verdict, language, classifier.modelVersion)
            Route.FLAG_AND_CONFIRM -> askCloud(
                m,
                language,
                verdict,
                flag(m, verdict, language, classifier.modelVersion)
            )
            Route.ASK_CLOUD -> askCloud(m, language, verdict, null)
        }
    }

    /** An accessibility snapshot of the window in front: drives conversation.active and screen capture. */
    fun onScreen(s: ScreenSnapshot) {
        val chat = ScreenParser.parse(s)
        if (chat == null || chat.appSource !in policy.watchedApps) {
            if (WatchedApps.appFor(s.packageName) == null) activeConversation = null
            return
        }
        val ref = conversationRef(chat.appSource, chat.title)
        chats[ref] = Chat(chat.appSource, chat.title)
        if (ref != activeConversation) {
            activeConversation = ref
            val flags = store.flagsIn(ref).filterNot { it.dismissed }
            publisher.publish(
                Events.SENTINEL_CONVERSATION_ACTIVE,
                SentinelConversationActivePayload(
                    ref,
                    chat.appSource,
                    flags.isNotEmpty(),
                    flags.maxByOrNull {
                        it.severity.ordinal
                    }?.severity
                )
            )
        }
        screenOnOpen.remove(ref)?.takeIf { it >= s.capturedAt }?.let {
            // The hidden messages are on screen now: screen them like any incoming message.
            for (sm in chat.messages) {
                val sender = sm.sender ?: if (sm.fromChild) null else chat.title
                val m = IncomingMessage(
                    chat.appSource, chat.title, chat.title, false,
                    sender ?: NotificationParser.SELF, sender.orEmpty(), sm.text, s.capturedAt, sm.fromChild
                )
                if (isNew(m)) onMessage(m)
            }
        }
        val wanted = screenCaptures[ref] ?: return
        screenCaptures.remove(ref)
        if (wanted.until < s.capturedAt) return
        // Merge what is on screen with what notifications already showed; screen order wins.
        val known = buffer.messages(chat.appSource, chat.title)
        val fromScreen = chat.messages.map { sm ->
            known.firstOrNull { it.text == sm.text && it.fromChild == sm.fromChild }
                ?: IncomingMessage(
                    chat.appSource, chat.title, chat.title, false,
                    sm.sender ?: NotificationParser.SELF, sm.sender.orEmpty(), sm.text, s.capturedAt, sm.fromChild
                )
        }
        val merged = (known.filterNot { k -> fromScreen.any { it === k } } + fromScreen)
        publishCapture(wanted.flagId, chat.appSource, chat.title, merged)
        SafeLog.i(
            "sentinel.capture.screen",
            LogField.id("flag_id", wanted.flagId),
            LogField.num("messages", merged.size)
        )
    }

    /** Companion asked for a capture (backup capture). */
    fun onCaptureRequested(conversationRef: String) {
        val chat = chats[conversationRef] ?: return
        val flagId = store.flagsIn(conversationRef).maxByOrNull { it.at }?.flagId ?: UUID.randomUUID().toString()
        publishCapture(flagId, chat.app, chat.key, buffer.messages(chat.app, chat.key))
        screenCaptures[conversationRef] = PendingCapture(flagId, clock() + SCREEN_CAPTURE_WINDOW)
    }

    fun onFeedback(flagId: String, verdict: FeedbackVerdict, consentToTrain: Boolean) {
        val flag = store.flag(flagId) ?: return
        if (verdict == FeedbackVerdict.NOT_HARMFUL) {
            store.markDismissed(flagId) // raises this sender's threshold next time
            publisher.publish(
                Events.SENTINEL_FLAG_UPDATED,
                SentinelFlagUpdatedPayload(flagId, flag.severity, flag.category, emptyList(), dismissed = true)
            )
        }
        if (consentToTrain) {
            cloud?.enqueue(
                CloudJob.Feedback(FeedbackRequest(flagId, verdict, flag.category, flag.modelVersion), clock())
            )
        }
    }

    fun onPolicy(p: GuardianPolicyUpdatedPayload) {
        if (p.policyVersion < policy.policyVersion) return
        policy =
            policy.copy(
                watchedApps = p.watchedApps.toSet(),
                thresholds = p.thresholds,
                ageBracket = p.ageBracket,
                policyVersion = p.policyVersion
            )
    }

    fun onPaired(ageBracket: ug.kuuma.contracts.AgeBracket) {
        policy = policy.copy(ageBracket = ageBracket)
    }

    // ------------------------------------------------------------------ flagging and capture

    private fun flag(m: IncomingMessage, v: Verdict, language: Language, modelVersion: String): String {
        val flagId = UUID.randomUUID().toString()
        val conversationRef = conversationRef(m.appSource, m.conversationKey)
        val record =
            FlagRecord(
                flagId, conversationRef,
                senderRef(
                    m
                ),
                messageRef(m), v.category!!, v.severity!!, v.confidence, modelVersion, clock()
            )
        store.recordFlag(record)
        publisher.publish(
            Events.SENTINEL_MESSAGE_FLAGGED,
            SentinelMessageFlaggedPayload(
                flagId, record.messageRef, m.appSource, conversationRef, record.senderRef, record.category,
                record.severity, v.confidence, language
            )
        )
        SafeLog.i(
            "sentinel.flag.raised",
            LogField.id("flag_id", flagId),
            LogField.enum("category", record.category),
            LogField.enum("severity", record.severity)
        )
        if (Screening.atLeast(record.severity, policy.thresholds.captureMinSeverity)) {
            publishCapture(flagId, m.appSource, m.conversationKey, buffer.messages(m.appSource, m.conversationKey))
            screenCaptures[conversationRef] = PendingCapture(flagId, clock() + SCREEN_CAPTURE_WINDOW)
        }
        if (activeConversation == conversationRef) {
            publisher.publish(
                Events.SENTINEL_CONVERSATION_ACTIVE,
                SentinelConversationActivePayload(conversationRef, m.appSource, true, record.severity)
            )
        }
        return flagId
    }

    private fun publishCapture(
        flagId: String,
        app: AppSource,
        conversationKey: String,
        messages: List<IncomingMessage>
    ) {
        if (messages.isEmpty()) return
        val captured = messages.map { m ->
            CapturedMessage(
                messageRef = messageRef(m),
                senderRef = senderRef(m),
                senderDisplayName = m.senderName.takeIf { it.isNotBlank() && !m.fromChild },
                text = m.text.take(10_000),
                sentAt = EventBus.iso(m.sentAt),
                fromChild = m.fromChild
            )
        }
        publisher.publish(
            Events.SENTINEL_CAPTURE_READY,
            SentinelCaptureReadyPayload(
                flagId,
                conversationRef(app, conversationKey),
                captured,
                null,
                EventBus.iso(clock())
            )
        )
    }

    // ------------------------------------------------------------------ cloud second opinion

    private fun askCloud(m: IncomingMessage, language: Language, onDevice: Verdict, flagId: String?) {
        val queue = cloud ?: return
        val participants = buffer.participants(m.appSource, m.conversationKey)
        val text = redactor.redactMessage(m.text, participants).text.take(4000)
        if (text.isBlank()) return
        val context = buffer.previousTexts(m).map {
            redactor.redactMessage(it, participants).text.take(4000)
        }.filter { it.isNotBlank() }
        val ticket = UUID.randomUUID().toString()
        pending[ticket] = Pending(m, language, flagId)
        queue.enqueue(CloudJob.Analyse(ticket, AnalyseRequest(text, language, m.appSource, context, onDevice), clock()))
    }

    private fun onCloudVerdict(ticket: String, response: AnalyseResponse) {
        val p = pending.remove(ticket) ?: return
        // Our own verdict sent back (the cloud only added an interpretation): nothing to change.
        if (response.verdictSource == VerdictSource.ON_DEVICE) return
        val v = response.verdict
        if (p.flagId == null) {
            // We were unsure; the cloud decides.
            val threshold = Screening.threshold(policy, store.stats(senderRef(p.message)))
            if (v.harmful && v.category != null && v.severity != null && v.confidence >= threshold) {
                val flagId = flag(p.message, v, p.language, response.modelVersion)
                if (response.groomingSignals.isNotEmpty()) {
                    publisher.publish(
                        Events.SENTINEL_FLAG_UPDATED,
                        SentinelFlagUpdatedPayload(flagId, v.severity!!, v.category!!, response.groomingSignals)
                    )
                }
            }
            return
        }
        val flag = store.flag(p.flagId) ?: return
        if (!v.harmful) {
            store.updateFlag(flag.copy(dismissed = true))
            publisher.publish(
                Events.SENTINEL_FLAG_UPDATED,
                SentinelFlagUpdatedPayload(flag.flagId, flag.severity, flag.category, emptyList(), dismissed = true)
            )
            return
        }
        val severity = v.severity ?: flag.severity
        val category = v.category ?: flag.category
        if (severity != flag.severity || category != flag.category || response.groomingSignals.isNotEmpty()) {
            store.updateFlag(flag.copy(severity = severity, category = category, modelVersion = response.modelVersion))
            publisher.publish(
                Events.SENTINEL_FLAG_UPDATED,
                SentinelFlagUpdatedPayload(flag.flagId, severity, category, response.groomingSignals)
            )
        }
    }

    // ------------------------------------------------------------------ refs

    private fun conversationRef(app: AppSource, key: String) = refs.conversationRef(app.wire, key)

    /** The same person keeps one ref (and one set of counters) across their chats in an app. */
    private fun senderRef(m: IncomingMessage) = refs.senderRef(m.appSource.wire, m.senderKey)

    private fun messageRef(m: IncomingMessage) = refs.messageRef(
        m.appSource.wire,
        m.conversationKey,
        "${m.senderKey}|${m.sentAt}|${Hashing.sha256Hex(m.text.toByteArray())}"
    )

    companion object {
        /** How long a flagged chat waits for the child to open it so the screen can be captured. */
        const val SCREEN_CAPTURE_WINDOW = 24 * 3_600_000L

        /** The same text in the same chat within this window is the same message. */
        const val SCREENED_WINDOW = 12 * 3_600_000L
    }
}
