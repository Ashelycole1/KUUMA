package ug.kuuma.sentinel

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.permissions.GrantKind
import ug.kuuma.core.permissions.KuumaPermission
import ug.kuuma.sentinel.capture.ScreenSnapshot
import ug.kuuma.sentinel.cloud.AiGateway
import ug.kuuma.sentinel.cloud.DeviceState
import ug.kuuma.sentinel.ingest.NotificationSnapshot
import ug.kuuma.sentinel.normalize.LexiconUpdater
import ug.kuuma.sentinel.store.InMemorySentinelStore
import ug.kuuma.sentinel.store.SentinelStore
import ug.kuuma.sentinel.store.SqlSentinelStore

/** What the app shell gives Sentinel. Everything AI-related comes in through interfaces. */
data class SentinelConfig(
    val classifier: HarmClassifier,
    val refs: OpaqueRefs,
    /** Null in JVM unit tests: an in-memory store is used and the services are not available. */
    val androidContext: Context? = null,
    /** /v1/ai client; null means on-device screening only. */
    val gateway: AiGateway? = null,
    val deviceState: () -> DeviceState = { DeviceState() }
)

/**
 * Sentinel: reads incoming messages from notifications, screens them on the phone,
 * flags harmful ones and captures the thread. Phone only: no cloud service of its own.
 *
 * The Android services ([ug.kuuma.sentinel.android.SentinelNotificationListener] and
 * [ug.kuuma.sentinel.android.SentinelAccessibilityService]) hand snapshots to [current];
 * all work then runs on Sentinel's single background thread.
 */
class SentinelModule(private val config: SentinelConfig) : KuumaModule {
    override val namespace = Namespace.SENTINEL
    override val displayName = "Sentinel"

    override val permissions = listOf(
        KuumaPermission(
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            Namespace.SENTINEL,
            "sentinel.permission.notifications.reason",
            GrantKind.SPECIAL_ACCESS
        ),
        KuumaPermission(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            Namespace.SENTINEL,
            "sentinel.permission.accessibility.reason",
            GrantKind.SPECIAL_ACCESS
        )
    )

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kuuma-sentinel").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private lateinit var engine: SentinelEngine
    private var lexicon: LexiconUpdater? = null

    override fun onRegister(context: ModuleContext) {
        worker.execute {
            val store: SentinelStore = if (config.androidContext != null) {
                val db = context.openDatabase(SqlSentinelStore.VERSION).db
                SqlSentinelStore.create(db)
                SqlSentinelStore(db)
            } else {
                InMemorySentinelStore()
            }
            engine = SentinelEngine(context.publisher, config.classifier, config.refs, store, config.gateway)
            lexicon = config.gateway?.let { g ->
                LexiconUpdater(
                    g,
                    config.androidContext?.let {
                        File(it.filesDir, "sentinel")
                    },
                    engine.normaliser
                ).also { it.loadInstalled() }
            }
            SafeLog.i("sentinel.ready", LogField.bool("cloud", config.gateway != null))
        }
        context.events.subscribe(Events.COMPANION_CAPTURE_REQUESTED) { e ->
            submit { engine.onCaptureRequested(e.payload.conversationRef) }
        }
        context.events.subscribe(Events.COMPANION_FEEDBACK_SUBMITTED) { e ->
            submit { engine.onFeedback(e.payload.flagId, e.payload.verdict, e.payload.consentToTrain) }
        }
        context.events.subscribe(Events.GUARDIAN_POLICY_UPDATED) { e -> submit { engine.onPolicy(e.payload) } }
        context.events.subscribe(Events.GUARDIAN_PAIRING_COMPLETED) { e ->
            submit { engine.onPaired(e.payload.ageBracket) }
        }
        if (config.gateway != null) worker.schedule(::pumpCloud, 30, TimeUnit.SECONDS)
        current = this
    }

    fun submitNotification(n: NotificationSnapshot) = submit { engine.onNotification(n) }

    fun submitScreen(s: ScreenSnapshot) = submit { engine.onScreen(s) }

    fun submitHiddenNotification(n: NotificationSnapshot) = submit { engine.onHiddenNotification(n) }

    /** Blocks until queued work is done. For tests and the debug tools only. */
    fun awaitIdle(timeoutMillis: Long = 10_000) {
        worker.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun submit(work: () -> Unit) {
        worker.execute {
            try {
                work()
            } catch (e: Exception) {
                // Screening must never stop because of one bad message. Never log the message.
                SafeLog.e("sentinel.work_failed", LogField.enum("kind", ErrorKind.of(e)))
            }
        }
    }

    private fun pumpCloud() {
        var next = 60_000L
        try {
            val device = config.deviceState()
            lexicon?.maybeUpdate(device)
            next = engine.cloud?.pump(System.currentTimeMillis(), device) ?: next
        } catch (e: Exception) {
            SafeLog.e("sentinel.pump_failed", LogField.enum("kind", ErrorKind.of(e)))
        }
        worker.schedule(::pumpCloud, next.coerceIn(5_000L, 15 * 60_000L), TimeUnit.MILLISECONDS)
    }

    private enum class ErrorKind {
        IO,
        STATE,
        ARGUMENT,
        OTHER;

        companion object {
            fun of(e: Exception) = when (e) {
                is java.io.IOException -> IO
                is IllegalStateException -> STATE
                is IllegalArgumentException -> ARGUMENT
                else -> OTHER
            }
        }
    }

    companion object {
        /** The registered instance, for the Android services. */
        @Volatile var current: SentinelModule? = null
            private set
    }
}
