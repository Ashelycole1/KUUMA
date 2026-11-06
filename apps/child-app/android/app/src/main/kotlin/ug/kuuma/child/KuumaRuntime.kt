package ug.kuuma.child

import android.app.Application
import android.content.Context
import ug.kuuma.ai.runtime.StarterHarmClassifier
import ug.kuuma.casefile.CasefileConfig
import ug.kuuma.casefile.CasefileModule
import ug.kuuma.casefile.HttpCasesApi
import ug.kuuma.casefile.HttpTranslator
import ug.kuuma.companion.CompanionModule
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.crypto.SecretWrapper
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleRegistry
import ug.kuuma.core.storage.EncryptedStorage
import ug.kuuma.guardian.GuardianConfig
import ug.kuuma.guardian.GuardianModule
import ug.kuuma.guardian.HttpGuardianApi
import ug.kuuma.sentinel.SentinelConfig
import ug.kuuma.sentinel.SentinelModule
import ug.kuuma.sentinel.android.DeviceStateReader
import ug.kuuma.sentinel.cloud.HttpAiGateway
import ug.kuuma.sentinel.cloud.RequestSigner
import ug.kuuma.core.crypto.DeviceSigner
import java.util.UUID

/** Debug-only tools (fakes, scenario player), present only in debug builds (src/debug). */
interface DebugHooks {
    fun install(context: Context, registry: ModuleRegistry, classifier: HarmClassifier, refs: OpaqueRefs)
    fun scenarios(): List<String>
    fun play(name: String, delayMillis: Long)
    fun setFakeEnabled(namespace: String, enabled: Boolean)
    fun fakeEnabled(module: KuumaModule): Boolean
}

/**
 * The child app's runtime: one event bus, one module registry, every module registered.
 * To add a module, add its Kotlin half here and its Flutter half in lib/main.dart.
 */
object KuumaRuntime {
    lateinit var bus: EventBus
        private set
    lateinit var registry: ModuleRegistry
        private set
    var debug: DebugHooks? = null
        private set

    /** Last events, replayed to the debug console when it opens (debug builds only). */
    val recent = ArrayDeque<Map<String, Any?>>()
    private val listeners = mutableListOf<(Map<String, Any?>) -> Unit>()

    /**
     * The API gateway. Debug builds talk to the local stack (`make dev`); 10.0.2.2 is the
     * laptop as seen from the Android emulator. A real phone on USB reaches it on 127.0.0.1
     * after `adb reverse tcp:8080 tcp:8080`. Release builds have no cloud until staging exists.
     */
    private val apiBaseUrl: String? =
        when {
            !BuildConfig.DEBUG -> null
            isEmulator() -> "http://10.0.2.2:8080"
            else -> "http://127.0.0.1:8080"
        }

    private const val DEBUG_SHARE_BASE = "http://127.0.0.1:8080"

    private fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.startsWith("generic") ||
            android.os.Build.PRODUCT.startsWith("sdk_") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish")

    private fun modules(
        app: Application,
        deviceId: String,
        classifier: HarmClassifier,
        refs: OpaqueRefs,
        registry: ModuleRegistry,
    ): List<KuumaModule> {
        val signer = DeviceSigner(app)
        val gateway = apiBaseUrl?.let { url ->
            HttpAiGateway(url, RequestSigner { method, path, body -> signer.requestHeaders(deviceId, method, path, body, System.currentTimeMillis() / 1000) })
        }
        val reader = DeviceStateReader(app)
        val sentinel = SentinelModule(SentinelConfig(classifier, refs, app, gateway, reader::read))
        val headers = { method: String, path: String, body: ByteArray ->
            signer.requestHeaders(deviceId, method, path, body, System.currentTimeMillis() / 1000)
        }
        val casefile = CasefileModule(
            CasefileConfig(
                androidContext = app,
                api = apiBaseUrl?.let { HttpCasesApi(it, headers) },
                translator = apiBaseUrl?.let { HttpTranslator(it, headers) },
                // Debug: 127.0.0.1 (through adb reverse) on the emulator too, because browsers only
                // allow the page's decryption on https:// or localhost, never on 10.0.2.2.
                shareBaseUrl = (if (apiBaseUrl != null) DEBUG_SHARE_BASE else "https://verify.kuuma.ug") + "/verify",
            ),
        )
        // Guardian signs with the device id the Guardian service issued at pairing.
        lateinit var guardian: GuardianModule
        guardian = GuardianModule(
            GuardianConfig(
                androidContext = app,
                api = apiBaseUrl?.let { url ->
                    HttpGuardianApi(url, { guardian.serverDeviceId }) { id, method, path, body ->
                        signer.requestHeaders(id, method, path, body, System.currentTimeMillis() / 1000)
                    }
                },
                devicePublicKey = signer::publicKeyBase64,
                permissions = { registry.permissions.all() },
            ),
        )
        return listOf(sentinel, CompanionModule(), casefile, guardian)
    }

    fun init(app: Application) {
        val prefs = app.getSharedPreferences("kuuma_runtime", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
        bus = EventBus({ deviceId })
        registry = ModuleRegistry(bus, storage = lazy { EncryptedStorage(app) })

        if (BuildConfig.DEBUG) {
            bus.subscribeAll { type, env -> record(DebugEventMapper.toMap(type, env)) }
        }

        // The starter lexicon until the AI owner's Knit model ships. Not DummyHarmClassifier: its TEST
        // terms ("as a gift", "our secret") flag harmless messages on a real phone.
        val classifier: HarmClassifier = StarterHarmClassifier()
        val refs = OpaqueRefs(SecretWrapper(app).getOrCreate("opaque-ref-salt").first)

        modules(app, deviceId, classifier, refs, registry).forEach(registry::register)

        if (BuildConfig.DEBUG) {
            debug = runCatching {
                Class.forName("ug.kuuma.child.debug.DebugToolsImpl").getDeclaredConstructor().newInstance() as DebugHooks
            }.getOrNull()?.also { it.install(app, registry, classifier, refs) }
        }
    }

    @Synchronized private fun record(event: Map<String, Any?>) {
        recent.addLast(event)
        while (recent.size > 300) recent.removeFirst()
        listeners.toList().forEach { it(event) }
    }

    @Synchronized fun listen(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        recent.forEach(listener)
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }
}

/** Turns events into plain maps for the Flutter debug console, removing message text and names. */
object DebugEventMapper {
    private val hidden = setOf("text", "senderDisplayName")

    fun toMap(type: EventType<*>, env: Envelope<*>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val json = ContractJson.encode(type as EventType<EventPayload>, env as Envelope<EventPayload>)
        @Suppress("UNCHECKED_CAST")
        return convert(org.json.JSONObject(json)) as Map<String, Any?>
    }

    private fun convert(value: Any?, key: String? = null): Any? = when {
        key in hidden -> "[hidden]"
        value is org.json.JSONObject -> value.keys().asSequence().associateWith { convert(value.get(it), it) }
        value is org.json.JSONArray -> (0 until value.length()).map { convert(value.get(it)) }
        value == org.json.JSONObject.NULL -> null
        else -> value
    }
}
