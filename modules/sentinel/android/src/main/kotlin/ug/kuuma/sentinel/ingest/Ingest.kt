package ug.kuuma.sentinel.ingest

import ug.kuuma.contracts.AppSource
import ug.kuuma.core.crypto.Hashing

/**
 * What Sentinel reads from one status-bar notification, copied out of the Android
 * `StatusBarNotification` so the parsing logic runs (and is tested) without Android.
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val conversationTitle: String? = null,
    /** EXTRA_IS_GROUP_CONVERSATION (API 28+); null on older Android. */
    val isGroupConversation: Boolean? = null,
    val isGroupSummary: Boolean = false,
    /** NotificationCompat.MessagingStyle messages (EXTRA_MESSAGES), oldest first. */
    val messages: List<StyleMessage> = emptyList()
)

/** One MessagingStyle message. A null [sender] means the phone's owner (the child) sent it. */
data class StyleMessage(val text: String, val sender: String?, val time: Long)

/** One message, as Sentinel understands it. Holds real text: it never leaves the phone. */
data class IncomingMessage(
    val appSource: AppSource,
    /** Stable, app-local id of the chat (group title, or the other person for one-to-one). */
    val conversationKey: String,
    val conversationTitle: String,
    val isGroup: Boolean,
    /** Stable, app-local id of the sender (their name or number as the app shows it). */
    val senderKey: String,
    val senderName: String,
    val text: String,
    val sentAt: Long,
    val fromChild: Boolean = false
)

/** Package names per app. SMS also includes the phone's default SMS app, added at runtime. */
object WatchedApps {
    private val packages: Map<String, AppSource> = mapOf(
        "com.whatsapp" to AppSource.WHATSAPP,
        "com.whatsapp.w4b" to AppSource.WHATSAPP,
        "com.google.android.apps.messaging" to AppSource.SMS,
        "com.android.mms" to AppSource.SMS,
        "com.android.messaging" to AppSource.SMS,
        "com.samsung.android.messaging" to AppSource.SMS,
        "com.sonyericsson.conversations" to AppSource.SMS,
        "com.oneplus.mms" to AppSource.SMS,
        "com.facebook.orca" to AppSource.MESSENGER,
        "com.facebook.mlite" to AppSource.MESSENGER,
        "com.instagram.android" to AppSource.INSTAGRAM,
        "com.instagram.lite" to AppSource.INSTAGRAM,
        "com.zhiliaoapp.musically" to AppSource.TIKTOK,
        "com.ss.android.ugc.trill" to AppSource.TIKTOK,
        "com.twitter.android" to AppSource.X
    )

    @Volatile private var extraSms: Set<String> = emptySet()

    /** The default SMS app varies by phone maker (Tecno, Infinix, itel...); the service adds it. */
    fun addSmsPackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) extraSms = extraSms + packageName
    }

    fun appFor(packageName: String): AppSource? =
        packages[packageName] ?: if (packageName in extraSms) AppSource.SMS else null
}

/**
 * Turns notifications into messages. Handles MessagingStyle (modern WhatsApp, Google
 * Messages) and the older title/text layouts, including WhatsApp's group formats
 * "Group: Sender", "Sender @ Group" and "Sender: text".
 */
object NotificationParser {
    private val countSuffix = Regex("""\s*\(\d+ (new )?messages?\)\s*$""", RegexOption.IGNORE_CASE)
    private val noise = listOf(
        Regex("""^\d+ new messages?( from \d+ chats?)?$""", RegexOption.IGNORE_CASE),
        Regex("""^checking for new messages""", RegexOption.IGNORE_CASE),
        Regex("""^(incoming|missed|ongoing) (voice|video) call""", RegexOption.IGNORE_CASE),
        Regex("""^(calling|ringing)…?$""", RegexOption.IGNORE_CASE),
        Regex("""^this message was deleted$""", RegexOption.IGNORE_CASE),
        Regex("""^waiting for this message""", RegexOption.IGNORE_CASE)
    )

    fun isNoise(text: String): Boolean = text.isBlank() || noise.any { it.containsMatchIn(text.trim()) }

    fun parse(n: NotificationSnapshot, app: AppSource): List<IncomingMessage> {
        if (n.isGroupSummary) return emptyList()
        val title = n.title?.replace(countSuffix, "")?.trim().orEmpty()
        val convTitle = n.conversationTitle?.replace(countSuffix, "")?.trim()

        if (n.messages.isNotEmpty()) {
            val group =
                n.isGroupConversation ?: (convTitle != null && n.messages.mapNotNull { it.sender }.distinct().size > 1)
            val chat = convTitle ?: title
            return n.messages.filterNot { isNoise(it.text) }.map { m ->
                val sender = m.sender?.trim()
                IncomingMessage(
                    appSource = app,
                    conversationKey = chat.ifBlank { sender ?: "unknown" },
                    conversationTitle = chat.ifBlank { sender ?: "" },
                    isGroup = group,
                    senderKey = sender ?: SELF,
                    senderName = sender ?: "",
                    text = m.text.trim(),
                    sentAt = m.time,
                    fromChild = sender == null
                )
            }
        }

        val text = n.text?.trim().orEmpty()
        if (title.isBlank() || isNoise(text)) return emptyList()

        // Legacy WhatsApp group layouts.
        if (app == AppSource.WHATSAPP) {
            title.split(" @ ", limit = 2).takeIf { it.size == 2 }?.let { (sender, group) ->
                return listOf(message(app, group, true, sender, text, n.postTime))
            }
            title.split(": ", limit = 2).takeIf { it.size == 2 }?.let { (group, sender) ->
                return listOf(message(app, group, true, sender, text, n.postTime))
            }
            if (convTitle != null && convTitle != title) {
                return listOf(message(app, convTitle, true, title, text, n.postTime))
            }
            text.split(": ", limit = 2).takeIf {
                it.size == 2 && n.isGroupConversation == true
            }?.let { (sender, body) ->
                return listOf(message(app, title, true, sender, body, n.postTime))
            }
        }
        return listOf(message(app, title, n.isGroupConversation == true, title, text, n.postTime))
    }

    private fun message(app: AppSource, chat: String, group: Boolean, sender: String, text: String, at: Long) =
        IncomingMessage(app, chat.trim(), chat.trim(), group, sender.trim(), sender.trim(), text.trim(), at)

    /** senderKey used for the child's own messages. */
    const val SELF = "\u0000self"
}

/**
 * Drops messages already seen. Apps re-post every unread message each time a new one
 * arrives; the same text from the same sender within [windowMillis] is one message.
 */
class Deduplicator(private val capacity: Int = 1000, private val windowMillis: Long = 120_000) {
    private val seen = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > capacity
    }

    @Synchronized
    fun isNew(m: IncomingMessage): Boolean {
        val id = Hashing.sha256Hex("${m.appSource.wire}|${m.conversationKey}|${m.senderKey}|${m.text}".toByteArray())
        val previous = seen[id]
        seen[id] = m.sentAt
        return previous == null || kotlin.math.abs(m.sentAt - previous) > windowMillis
    }
}
