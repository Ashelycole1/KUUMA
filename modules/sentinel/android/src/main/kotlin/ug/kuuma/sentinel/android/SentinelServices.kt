package ug.kuuma.sentinel.android

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ug.kuuma.sentinel.SentinelModule
import ug.kuuma.sentinel.capture.ScreenNode
import ug.kuuma.sentinel.capture.ScreenSnapshot
import ug.kuuma.sentinel.capture.ScreenSpecs
import ug.kuuma.sentinel.cloud.DeviceState
import ug.kuuma.sentinel.ingest.NotificationSnapshot
import ug.kuuma.sentinel.ingest.StyleMessage
import ug.kuuma.sentinel.ingest.WatchedApps

/**
 * Reads incoming notifications. SMS is read from its notification too, so Kuuma needs no
 * SMS permission. The service only copies text out; it never dismisses, replies to or
 * otherwise touches the notification, so the sender sees nothing.
 */
class SentinelNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        WatchedApps.addSmsPackage(runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (WatchedApps.appFor(sbn.packageName) == null) return
        val module = SentinelModule.current ?: return
        if (hiddenByAndroid(sbn)) {
            module.submitHiddenNotification(snapshot(sbn))
            return
        }
        module.submitNotification(snapshot(sbn))
    }

    /** Android 15+: the system replaced this notification's text because it looked sensitive. */
    private fun hiddenByAndroid(sbn: StatusBarNotification): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val r = Ranking()
            // Not in the public SDK yet, so looked up by name; absent means "not hidden".
            currentRanking.getRanking(sbn.key, r) &&
                Ranking::class.java.getMethod("hasSensitiveContent").invoke(r) == true
        }.getOrDefault(false)
    }

    companion object {
        fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
            val n = sbn.notification
            val extras = n.extras ?: Bundle()
            val self = extras.getCharSequence("android.selfDisplayName")?.toString()

            @Suppress("DEPRECATION")
            val raw: Array<Parcelable>? = extras.getParcelableArray("android.messages")
            val messages = raw.orEmpty().mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = senderName(b)?.takeUnless { it == self }
                StyleMessage(text, sender, b.getLong("time", sbn.postTime))
            }
            return NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                postTime = sbn.postTime,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString(),
                isGroupConversation = if (extras.containsKey(
                        "android.isGroupConversation"
                    )
                ) {
                    extras.getBoolean("android.isGroupConversation")
                } else {
                    null
                },
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                messages = messages
            )
        }

        private fun senderName(b: Bundle): String? {
            b.getCharSequence("sender")?.let { return it.toString() }
            if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                val person = b.getParcelable<android.app.Person>("sender_person")
                person?.name?.let { return it.toString() }
            }
            return null
        }
    }
}

/**
 * Reads the chat on screen. It only reads what the app already shows: it performs no
 * actions (no clicks, scrolls, typing or window changes), so it cannot cause read
 * receipts or typing indicators. Windows of other apps are ignored straight away.
 */
class SentinelAccessibilityService : AccessibilityService() {
    private var lastSnapshot = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val module = SentinelModule.current ?: return
        if (WatchedApps.appFor(pkg) == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                module.submitScreen(ScreenSnapshot(pkg, ScreenNode(), System.currentTimeMillis()))
            }
            return
        }
        if (ScreenSpecs.forPackage(pkg) == null) return
        val now = System.currentTimeMillis()
        // Content changes fire constantly while a chat scrolls; read at most every 1.5 s.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastSnapshot < 1_500) return
        lastSnapshot = now
        val root = rootInActiveWindow ?: return
        val tree = try {
            copy(root, 0, intArrayOf(0))
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) root.recycle()
        }
        module.submitScreen(ScreenSnapshot(pkg, tree, now))
    }

    override fun onInterrupt() = Unit

    /** Copies the node tree (bounded, for low-memory phones) and releases each node. */
    private fun copy(node: AccessibilityNodeInfo, depth: Int, count: IntArray): ScreenNode {
        count[0]++
        val children = ArrayList<ScreenNode>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                if (count[0] >= MAX_NODES) break
                val child = node.getChild(i) ?: continue
                try {
                    children += copy(child, depth + 1, count)
                } finally {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT < 33) child.recycle()
                }
            }
        }
        return ScreenNode(
            node.viewIdResourceName,
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.className?.toString(),
            children
        )
    }

    companion object {
        const val MAX_DEPTH = 30
        const val MAX_NODES = 800
    }
}

/** Battery and network, for Sentinel's cloud budget. Works on Android 5+. */
class DeviceStateReader(private val context: Context) {
    fun read(): DeviceState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Suppress("DEPRECATION")
        val online = cm.activeNetworkInfo?.isConnected == true
        val saver =
            Build.VERSION.SDK_INT >= 24 &&
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return DeviceState(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = plugged,
            unmetered = online && !cm.isActiveNetworkMetered,
            online = online,
            dataSaver = saver
        )
    }
}
