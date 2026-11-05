package ug.kuuma.sentinel.capture

import ug.kuuma.contracts.AppSource
import ug.kuuma.sentinel.ingest.IncomingMessage

/**
 * The last messages of each conversation, held in memory only (never written to disk:
 * sentinel.db holds counters and verdicts, not text). This is what a capture is built
 * from straight away, before the chat is ever on screen.
 */
class ConversationBuffer(private val perConversation: Int = 40, private val maxConversations: Int = 200) {
    private val chats = object : LinkedHashMap<String, ArrayDeque<IncomingMessage>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<IncomingMessage>>) =
            size > maxConversations
    }

    private fun key(app: AppSource, conversationKey: String) = "${app.wire}|$conversationKey"

    @Synchronized fun add(m: IncomingMessage) {
        val q = chats.getOrPut(key(m.appSource, m.conversationKey)) { ArrayDeque() }
        q.addLast(m)
        while (q.size > perConversation) q.removeFirst()
    }

    @Synchronized fun messages(app: AppSource, conversationKey: String): List<IncomingMessage> =
        chats[key(app, conversationKey)]?.toList().orEmpty()

    /** Earlier texts of the chat, oldest first, as classifier context. */
    @Synchronized fun previousTexts(m: IncomingMessage, max: Int = 10): List<String> =
        messages(m.appSource, m.conversationKey).dropLast(1).takeLast(max).map { it.text }

    @Synchronized fun participants(app: AppSource, conversationKey: String): Set<String> =
        messages(app, conversationKey).map { it.senderName }.filter { it.isNotBlank() }.toSet() + conversationKey

    @Synchronized fun clear() = chats.clear()
}

/** A copy of an accessibility node tree, so parsing runs (and is tested) without Android. */
data class ScreenNode(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val children: List<ScreenNode> = emptyList()
) {
    fun walk(): Sequence<ScreenNode> = sequence {
        yield(this@ScreenNode)
        for (c in children) yieldAll(c.walk())
    }
}

data class ScreenSnapshot(val packageName: String, val root: ScreenNode, val capturedAt: Long)

/** A chat as it appears on screen: who it is with and the visible messages. */
data class ScreenChat(val appSource: AppSource, val title: String, val messages: List<ScreenMessage>)

data class ScreenMessage(val sender: String?, val text: String, val fromChild: Boolean)

/**
 * Where an app keeps things in its chat screen. View ids change between app releases, so
 * they are data, not code: update this table when an app changes its layout (and add a
 * fixture test). Ids marked UNVERIFIED must be confirmed on a real phone.
 */
data class AppScreenSpec(
    val appSource: AppSource,
    val packages: Set<String>,
    /** Toolbar title of an open chat. Its presence means "a chat is open". */
    val titleIds: Set<String>,
    /** Each message's text. */
    val messageTextIds: Set<String>,
    /** A view inside the message row that only outgoing messages have (e.g. the delivery ticks). */
    val outgoingMarkerIds: Set<String>,
    /** Sender name shown above messages in group chats. */
    val groupSenderIds: Set<String>,
    /** The row container holding one message, used to tie text, sender and marker together. */
    val rowIds: Set<String>
)

object ScreenSpecs {
    val WHATSAPP = AppScreenSpec(
        AppSource.WHATSAPP,
        setOf("com.whatsapp", "com.whatsapp.w4b"),
        titleIds = setOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name"),
        messageTextIds = setOf("com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text"),
        outgoingMarkerIds = setOf("com.whatsapp:id/status", "com.whatsapp.w4b:id/status"),
        groupSenderIds = setOf("com.whatsapp:id/name_in_group_tv", "com.whatsapp.w4b:id/name_in_group_tv"),
        rowIds = setOf("com.whatsapp:id/main_layout", "com.whatsapp.w4b:id/main_layout")
    )

    /** Google Messages. UNVERIFIED ids: confirm on a real phone before relying on them. */
    val GOOGLE_MESSAGES = AppScreenSpec(
        AppSource.SMS,
        setOf("com.google.android.apps.messaging"),
        titleIds = setOf("com.google.android.apps.messaging:id/conversation_title"),
        messageTextIds = setOf("com.google.android.apps.messaging:id/message_text"),
        outgoingMarkerIds = setOf("com.google.android.apps.messaging:id/message_status"),
        groupSenderIds = setOf("com.google.android.apps.messaging:id/sender_name"),
        rowIds = setOf("com.google.android.apps.messaging:id/message_content")
    )

    val ALL = listOf(WHATSAPP, GOOGLE_MESSAGES)

    fun forPackage(packageName: String) = ALL.firstOrNull { packageName in it.packages }
}

/**
 * Reads an open chat from the screen. It only reads what is already displayed: it never
 * clicks, scrolls or opens anything, so it cannot cause read receipts or typing indicators.
 */
object ScreenParser {
    fun parse(snapshot: ScreenSnapshot, specs: List<AppScreenSpec> = ScreenSpecs.ALL): ScreenChat? {
        val spec = specs.firstOrNull { snapshot.packageName in it.packages } ?: return null
        val nodes = snapshot.root.walk().toList()
        val title =
            nodes.firstOrNull { it.viewId in spec.titleIds }?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val rows = snapshot.root.walk().filter { it.viewId in spec.rowIds }.toList()
        val messages = if (rows.isNotEmpty()) {
            rows.mapNotNull { row ->
                val inRow = row.walk().toList()
                val text =
                    inRow.firstOrNull { it.viewId in spec.messageTextIds }?.text?.trim() ?: return@mapNotNull null
                val outgoing = inRow.any { it.viewId in spec.outgoingMarkerIds }
                val sender = inRow.firstOrNull { it.viewId in spec.groupSenderIds }?.text?.trim()
                ScreenMessage(if (outgoing) null else sender ?: title, text, outgoing)
            }
        } else {
            // No row containers: keep text order, attribute to the chat title.
            nodes.filter { it.viewId in spec.messageTextIds }.mapNotNull { n ->
                n.text?.trim()?.takeIf { it.isNotEmpty() }?.let { ScreenMessage(title, it, false) }
            }
        }
        return ScreenChat(spec.appSource, title, messages.filter { it.text.isNotEmpty() })
    }
}
