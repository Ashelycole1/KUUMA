package ug.kuuma.casefile

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import ug.kuuma.casefile.export.RootTimestamp
import ug.kuuma.casefile.export.Translator
import ug.kuuma.casefile.vault.InMemoryVaultStore
import ug.kuuma.casefile.vault.ItemSigner
import ug.kuuma.casefile.vault.SqlVaultStore
import ug.kuuma.casefile.vault.VaultStore
import ug.kuuma.contracts.BundleRequest
import ug.kuuma.contracts.BundleResponse
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.ShareRequest
import ug.kuuma.contracts.ShareResponse
import ug.kuuma.contracts.TimestampRequest
import ug.kuuma.contracts.TimestampResponse
import ug.kuuma.contracts.TranslateRequest
import ug.kuuma.contracts.TranslateResponse
import ug.kuuma.core.crypto.DeviceSigner
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ScreenSlot

/** What the app shell gives Casefile. */
data class CasefileConfig(
    /** Null in JVM unit tests: an in-memory vault and a supplied signer are used. */
    val androidContext: Context? = null,
    val signer: ItemSigner? = null,
    val api: CasesApi? = null,
    /** Base of share links and the verification page, e.g. https://verify.kuuma.ug */
    val shareBaseUrl: String = "http://localhost:8080/verify",
    /** POST /v1/ai/translate for non-English exhibits; null keeps case files untranslated. */
    val translator: Translator? = null
)

/**
 * Casefile: the evidence vault. Seals everything Sentinel flags and captures into an
 * append-only, signed hash chain in vault.db, groups it into cases per offender, and
 * builds, exports and shares case files.
 */
class CasefileModule(private val config: CasefileConfig = CasefileConfig()) : KuumaModule {
    override val namespace = Namespace.CASEFILE
    override val displayName = "Casefile"
    override val screens = listOf(ScreenSlot("casefile.cases", "child.tab", "casefile.tab.title"))

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kuuma-casefile").apply { isDaemon = true }
    }
    private lateinit var engine: CasefileEngine

    override fun onRegister(context: ModuleContext) {
        worker.execute {
            val store: VaultStore = if (config.androidContext != null) {
                val db = context.openDatabase(SqlVaultStore.VERSION).db
                SqlVaultStore.create(db)
                SqlVaultStore(db)
            } else {
                InMemoryVaultStore()
            }
            val signer = config.signer ?: DeviceItemSigner(DeviceSigner(config.androidContext!!))
            engine = CasefileEngine(
                context.publisher,
                store,
                signer,
                config.api,
                config.shareBaseUrl,
                translator = config.translator
            )
            SafeLog.i("casefile.ready", LogField.num("chain_length", store.chain().size))
        }
        context.events.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { e -> submit { engine.onFlagged(e.payload) } }
        context.events.subscribe(Events.SENTINEL_FLAG_UPDATED) { e -> submit { engine.onFlagUpdated(e.payload) } }
        context.events.subscribe(Events.SENTINEL_CAPTURE_READY) { e -> submit { engine.onCapture(e.payload) } }
        context.events.subscribe(Events.GUARDIAN_PAIRING_COMPLETED) { submit { engine.onPaired() } }
        context.events.subscribe(Events.GUARDIAN_REPORT_REQUESTED) { e ->
            submit { engine.onReportRequested(e.payload) }
        }
        context.events.subscribe(Events.GUARDIAN_SHARE_APPROVED) { e ->
            submit { engine.onShareApproved(e.payload.requestId) }
        }
        context.events.subscribe(Events.GUARDIAN_SHARE_DECLINED) { e ->
            submit { engine.onShareDeclined(e.payload.requestId) }
        }
    }

    /** Calls from the kuuma_casefile screens. Everything runs on the vault's own thread. */
    override fun onCall(method: String, args: Map<String, Any?>): Any? = onWorker(
        // Exports and shares may translate messages first (seconds each on a local model).
        if (method == "export" || method == "requestShare") 300L else 30L
    ) {
        when (method) {
            "listCases" -> engine.cases().map { c ->
                val sender = engine.senderOf(c)
                mapOf(
                    "caseId" to c.caseId,
                    "offenderLabel" to c.offenderLabel,
                    "senderNames" to sender.displayNames,
                    "senderNumbers" to sender.phoneNumbers,
                    "incidentCount" to c.incidentCount.toInt(),
                    "messageCount" to c.messageCount,
                    "topSeverity" to c.topSeverity.wire,
                    "categories" to c.categories.map { it.wire },
                    "lastAt" to c.lastAt,
                    "lateNight" to c.lateNightCount(),
                    "groomingStages" to c.groomingStages.map { it.wire }
                )
            }
            "timeline" -> engine.timeline(args["caseId"] as String).map { t ->
                mapOf(
                    "at" to t.at,
                    "kind" to t.kind.name,
                    "appSource" to t.appSource,
                    "sender" to t.senderLabel,
                    "fromChild" to t.fromChild,
                    "category" to t.category?.wire,
                    "severity" to t.severity?.wire,
                    "text" to t.text,
                    // Machine translation already made for an export (never fetched just to view).
                    "translation" to engine.translationOf(t.itemId)?.text,
                    "translatedFrom" to engine.translationOf(t.itemId)?.from?.wire
                )
            }
            "verify" -> engine.verify().let { r ->
                mapOf(
                    "intact" to r.intact,
                    "length" to r.length.toInt(),
                    "root" to r.root,
                    "problems" to r.problems.size
                )
            }
            "export" -> {
                val file = engine.export(
                    args["caseId"] as String,
                    RecipientType.fromWire(args["recipientType"] as String)
                )
                val dir = File(config.androidContext!!.filesDir, "casefile/exports").apply { mkdirs() }
                val pdf = File(dir, "nilinde-case-${(args["caseId"] as String).take(8)}.pdf").apply {
                    writeBytes(file.pdf)
                }
                File(dir, pdf.nameWithoutExtension + ".json").writeText(file.json.toString())
                // The child asked for the file: a copy goes to Downloads/Nilinde, where they can find it.
                val download = runCatching { DeviceFiles.saveToDownloads(config.androidContext!!, pdf) }
                    .onFailure { SafeLog.w("casefile.export.download_failed") }
                    .getOrNull()
                mapOf("pdfPath" to pdf.absolutePath, "root" to file.root, "downloadUri" to download)
            }
            "openFile" -> DeviceFiles.open(config.androidContext!!, args["uri"] as String)
            "sendLink" -> DeviceFiles.sendText(
                config.androidContext!!,
                args["text"] as String,
                args["title"] as? String ?: ""
            )
            "requestShare" -> engine.requestShare(
                args["caseId"] as String,
                RecipientType.fromWire(args["recipientType"] as String)
            )
            "shares" -> engine.shares(args["caseId"] as String).map {
                mapOf(
                    "requestId" to it.requestId,
                    "recipientType" to it.recipient.wire,
                    "status" to it.status,
                    "link" to it.link,
                    // Set when the parent reported it ("anonymous" or "named").
                    "reportedByParent" to engine.reportKind(it.requestId)
                )
            }
            "deleteVault" -> engine.deleteVault(args["parentApproved"] as? Boolean ?: false).name
            else -> throw UnsupportedOperationException(method)
        }
    }

    /** For tests and the debug tools. */
    fun awaitIdle() {
        worker.submit {}.get(10, TimeUnit.SECONDS)
    }

    fun <T> onWorker(seconds: Long = 30, work: () -> T): T = worker.submit<T> { work() }.get(seconds, TimeUnit.SECONDS)

    private fun submit(work: () -> Unit) = worker.execute {
        try {
            work()
        } catch (e: Exception) {
            SafeLog.e("casefile.work_failed", LogField.bool("io", e is IOException))
        }
    }
}

/** Signs chain links with the device key: Keystore-backed on Android 6+, Core's fallback on 5.x. */
class DeviceItemSigner(private val device: DeviceSigner) : ItemSigner {
    override val protection: String get() = device.protection.name
    override fun sign(data: ByteArray) = device.sign(data)
    override fun publicKeyBase64() = device.publicKeyBase64()
    override fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val key = KeyFactory.getInstance(
            "EC"
        ).generatePublic(X509EncodedKeySpec(ug.kuuma.casefile.vault.Base64.decode(publicKeyBase64())))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }
}

/** /v1/cases over HttpURLConnection. */
class HttpCasesApi(
    private val baseUrl: String,
    private val headers: (method: String, path: String, body: ByteArray) -> Map<String, String>
) : CasesApi {
    override fun timestamp(hash: String): RootTimestamp {
        val r =
            call(
                "POST",
                "/v1/cases/timestamps",
                TimestampRequest(hash),
                TimestampRequest.serializer(),
                TimestampResponse.serializer()
            )
        return RootTimestamp(r.hash, r.timestamp, r.signature, r.authority)
    }

    override fun createBundle(caseId: String, request: BundleRequest): BundleResponse =
        call("POST", "/v1/cases/$caseId/bundles", request, BundleRequest.serializer(), BundleResponse.serializer())

    override fun createShare(caseId: String, request: ShareRequest): ShareResponse =
        call("POST", "/v1/cases/$caseId/shares", request, ShareRequest.serializer(), ShareResponse.serializer())

    override fun upload(url: String, blob: ByteArray) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(blob) }
            if (conn.responseCode !in 200..299) throw IOException("upload failed: ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    private fun <I, O> call(method: String, path: String, body: I, inSer: KSerializer<I>, outSer: KSerializer<O>): O {
        val bytes = ContractJson.json.encodeToString(inSer, body).toByteArray()
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            headers(method, path, bytes).forEach(conn::setRequestProperty)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) throw IOException("$method $path -> ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return ContractJson.json.decodeFromString(outSer, out.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }
}

/** POST /v1/ai/translate, signed like every other device call. Sunflower is slow: long timeout. */
class HttpTranslator(
    private val baseUrl: String,
    private val headers: (method: String, path: String, body: ByteArray) -> Map<String, String>,
    private val readTimeoutMillis: Int = 200_000
) : Translator {
    override fun translate(request: TranslateRequest): TranslateResponse {
        val path = "/v1/ai/translate"
        val bytes = ContractJson.json.encodeToString(TranslateRequest.serializer(), request).toByteArray()
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMillis
            conn.setRequestProperty("Content-Type", "application/json")
            headers("POST", path, bytes).forEach(conn::setRequestProperty)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) throw IOException("POST $path -> ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { it.copyTo(out) }
            return ContractJson.json.decodeFromString(TranslateResponse.serializer(), out.toString("UTF-8"))
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("translate timed out")
        } finally {
            conn.disconnect()
        }
    }
}
