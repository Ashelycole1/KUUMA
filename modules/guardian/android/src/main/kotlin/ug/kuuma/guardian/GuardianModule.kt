package ug.kuuma.guardian

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.KSerializer
import ug.kuuma.contracts.AlertRequest
import ug.kuuma.contracts.ApprovalRequest
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.HeartbeatRequest
import ug.kuuma.contracts.HeartbeatResponse
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.PairingRequest
import ug.kuuma.contracts.PairingResponse
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot
import ug.kuuma.core.permissions.GrantKind
import ug.kuuma.core.permissions.KuumaPermission
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the app shell gives Guardian. */
data class GuardianConfig(
    val androidContext: Context? = null,
    val api: GuardianApi? = null,
    /** Base64 X.509 public key of this phone's device key (core-android DeviceSigner). */
    val devicePublicKey: () -> String = { "" },
    /** Every module's declared permissions (Core's permission registry), for the setup wizard. */
    val permissions: () -> List<KuumaPermission> = { emptyList() },
    /** How often the phone checks in. PLACEHOLDER until the alert delivery time is agreed. */
    val heartbeatMinutes: Long = 15,
)

/**
 * Guardian on the child's phone: setup wizard, policy holder, alert decisions, approvals
 * relay and heartbeat. The child cannot change the policy: only parent-signed policies
 * (family key from the pairing QR) are accepted, and the child app has no policy editor.
 */
class GuardianModule(private val config: GuardianConfig = GuardianConfig()) : KuumaModule {
    override val namespace = Namespace.GUARDIAN
    override val displayName = "Guardian"
    override val permissions = listOf(
        KuumaPermission("android.permission.POST_NOTIFICATIONS", Namespace.GUARDIAN, "guardian.permission.notifications.reason", minSdk = 33),
        KuumaPermission("android.permission.CAMERA", Namespace.GUARDIAN, "guardian.permission.camera.reason", required = false),
    )
    override val screens = listOf(ScreenSlot("guardian.pairing", "child.settings", "guardian.pairing.title"))

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kuuma-guardian").apply { isDaemon = true } }
    private lateinit var engine: GuardianEngine

    override fun onRegister(context: ModuleContext) {
        worker.execute {
            val store: GuardianStore = if (config.androidContext != null) {
                SqlGuardianStore(context.openDatabase(1).db)
            } else {
                InMemoryGuardianStore()
            }
            engine = GuardianEngine(context.publisher, store, config.api, config.devicePublicKey, ::missingPermissions)
            engine.announce()
            SafeLog.i("guardian.ready", LogField.bool("paired", engine.isPaired))
        }
        context.events.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { e ->
            submit { engine.onFlagged(e.payload.flagId, e.payload.severity, e.payload.category, e.payload.appSource) }
        }
        context.events.subscribe(Events.SENTINEL_FLAG_UPDATED) { e ->
            submit { engine.onFlagUpdated(e.payload.flagId, e.payload.severity, e.payload.category, e.payload.dismissed == true) }
        }
        context.events.subscribe(Events.SENTINEL_CAPTURE_READY) { e ->
            val offending = e.payload.messages.lastOrNull { !it.fromChild }?.text
            submit { engine.onCapture(e.payload.flagId, offending) }
        }
        context.events.subscribe(Events.CASEFILE_EVIDENCE_SEALED) { e -> submit { engine.onEvidenceSealed(e.payload.flagId, e.caseId) } }
        context.events.subscribe(Events.CASEFILE_CASE_UPDATED) { e -> submit { engine.onCaseUpdated(e.payload.caseId, e.payload.topSeverity) } }
        context.events.subscribe(Events.CASEFILE_CASE_ESCALATED) { e -> submit { engine.onEscalated(e.payload.caseId) } }
        context.events.subscribe(Events.CASEFILE_SHARE_REQUESTED) { e ->
            submit { engine.onShareRequested(e.payload.requestId, e.payload.caseId, e.payload.recipientType) }
        }
        context.events.subscribe(Events.COMPANION_SOS_TRIGGERED) { e -> submit { engine.onSos(e.payload.caseId, e.payload.triggeredAt) } }
        if (config.api != null) worker.scheduleWithFixedDelay({ submit { engine.heartbeat() } }, 1, config.heartbeatMinutes, TimeUnit.MINUTES)
    }

    /** Calls from the setup wizard (kuuma_guardian). */
    override fun onCall(method: String, args: Map<String, Any?>): Any? = onWorker {
        when (method) {
            "status" -> mapOf(
                "paired" to engine.isPaired,
                "childId" to engine.childId,
                "consentAt" to engine.consentAt,
                "policyVersion" to engine.policy?.policyVersion?.toInt(),
                "ageBracket" to engine.policy?.ageBracket?.wire,
                "watchedApps" to engine.policy?.watchedApps?.map { it.wire },
                "pendingAlerts" to engine.pendingAlerts,
            )
            "consent" -> engine.recordConsent()
            "pair" -> engine.pair(args["qr"] as String).name.also { if (it == PairResult.PAIRED.name) runCatching { engine.heartbeat() } }
            "permissions" -> config.permissions().map { p ->
                mapOf(
                    "permission" to p.permission,
                    "module" to p.module.wire,
                    "reasonKey" to p.reasonKey,
                    "kind" to p.kind.name,
                    "required" to p.required,
                    "granted" to granted(p),
                )
            }
            "openSettings" -> openSettings(args["permission"] as String)
            "heartbeat" -> engine.heartbeat()
            else -> throw UnsupportedOperationException(method)
        }
    }

    fun <T> onWorker(work: () -> T): T = worker.submit<T> { work() }.get(30, TimeUnit.SECONDS)

    fun awaitIdle() {
        worker.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun submit(work: () -> Unit) = worker.execute {
        try {
            work()
        } catch (e: Exception) {
            SafeLog.e("guardian.work_failed", LogField.bool("io", e is IOException))
        }
    }

    // ------------------------------------------------------------------ permissions (Android)

    private fun missingPermissions(): List<String> = config.permissions().filter { it.required && !granted(it) }.map { it.permission }

    private fun granted(p: KuumaPermission): Boolean {
        val ctx = config.androidContext ?: return true
        if (!p.appliesTo(Build.VERSION.SDK_INT)) return true
        return when (p.permission) {
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" ->
                Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
            "android.permission.BIND_ACCESSIBILITY_SERVICE" ->
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(ctx.packageName) == true
            "android.permission.BIND_INPUT_METHOD" ->
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)?.contains(ctx.packageName) == true
            else -> p.kind == GrantKind.INSTALL ||
                Build.VERSION.SDK_INT < 23 ||
                ctx.checkSelfPermission(p.permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openSettings(permission: String): Boolean {
        val ctx = config.androidContext ?: return false
        val action = when (permission) {
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            "android.permission.BIND_ACCESSIBILITY_SERVICE" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "android.permission.BIND_INPUT_METHOD" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) intent.data = android.net.Uri.parse("package:${ctx.packageName}")
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }
}

/** guardian.db (SQLCipher via the module's own ModuleContext): one key-value table. */
class SqlGuardianStore(private val db: SupportSQLiteDatabase) : GuardianStore {
    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun get(key: String): String? = db.query("SELECT v FROM kv WHERE k = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun put(key: String, value: String?) {
        if (value == null) db.execSQL("DELETE FROM kv WHERE k = ?", arrayOf<Any>(key)) else db.execSQL("INSERT OR REPLACE INTO kv VALUES (?, ?)", arrayOf<Any>(key, value))
    }
}

/** /v1/guardian over HttpURLConnection. Signed calls use the server's device id for this phone. */
class HttpGuardianApi(
    private val baseUrl: String,
    private val deviceId: () -> String?,
    private val sign: (deviceId: String, method: String, path: String, body: ByteArray) -> Map<String, String>,
) : GuardianApi {
    override fun pair(request: PairingRequest): PairingResponse =
        call("/v1/guardian/pairings", request, PairingRequest.serializer(), PairingResponse.serializer(), signed = false)

    override fun heartbeat(request: HeartbeatRequest): HeartbeatResponse =
        call("/v1/guardian/heartbeat", request, HeartbeatRequest.serializer(), HeartbeatResponse.serializer())

    override fun alert(request: AlertRequest) {
        call<AlertRequest, Unit>("/v1/guardian/alerts", request, AlertRequest.serializer(), null)
    }

    override fun approvalRequest(request: ApprovalRequest) {
        call<ApprovalRequest, Unit>("/v1/guardian/approval-requests", request, ApprovalRequest.serializer(), null)
    }

    private fun <I, O> call(path: String, body: I, inSer: KSerializer<I>, outSer: KSerializer<O>?, signed: Boolean = true): O {
        val bytes = ContractJson.json.encodeToString(inSer, body).toByteArray()
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (signed) {
                val id = deviceId() ?: throw IOException("not paired")
                sign(id, "POST", path, bytes).forEach(conn::setRequestProperty)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("POST $path -> $code")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            @Suppress("UNCHECKED_CAST")
            return if (outSer == null) Unit as O else ContractJson.json.decodeFromString(outSer, out.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }
}
