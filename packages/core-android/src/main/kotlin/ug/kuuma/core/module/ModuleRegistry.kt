package ug.kuuma.core.module

import java.util.concurrent.CopyOnWriteArrayList
import ug.kuuma.contracts.Namespace
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.bus.EventSource
import ug.kuuma.core.bus.ModulePublisher
import ug.kuuma.core.permissions.KuumaPermission
import ug.kuuma.core.permissions.PermissionRegistry
import ug.kuuma.core.storage.EncryptedStorage
import ug.kuuma.core.storage.KuumaDatabase

/** A setting a module exposes in the child app's settings screen. */
data class ModuleSetting(
    val key: String,
    val titleKey: String,
    val kind: Kind,
    val default: String,
    val debugOnly: Boolean = false
) {
    enum class Kind { TOGGLE, CHOICE, NUMBER }
}

/**
 * A Flutter screen a module provides. The Flutter package registers the widget under
 * the same [id] in kuuma_ui's navigation slots; the shell matches the two up.
 */
data class ScreenSlot(val id: String, val slot: String, val titleKey: String)

/**
 * What every module (and every fake) implements to plug into the child app.
 *
 * The shell registers modules at startup; registration is the module's only way to get
 * a [ModulePublisher], so publishing is always scoped to its own namespace.
 */
interface KuumaModule {
    val namespace: Namespace
    val displayName: String
    val permissions: List<KuumaPermission> get() = emptyList()
    val settings: List<ModuleSetting> get() = emptyList()
    val screens: List<ScreenSlot> get() = emptyList()

    /** True for fakes; fakes can be switched on and off in the debug settings. */
    val isFake: Boolean get() = false

    /** Subscribe handlers and keep [context] for publishing. Called once. */
    fun onRegister(context: ModuleContext)

    /**
     * A call from this module's own Flutter screens, over the channel
     * `ug.kuuma/module/<namespace>` (kuuma_ui `ModuleChannel`). Runs on a background
     * thread. Return only what Flutter's message codec carries: null, Boolean, Number,
     * String, List and Map. Throw to report an error to the screen.
     */
    fun onCall(method: String, args: Map<String, Any?>): Any? =
        throw UnsupportedOperationException("${namespace.wire} has no method $method")
}

/** Everything a module may touch, scoped to its namespace. */
class ModuleContext internal constructor(
    val namespace: Namespace,
    val publisher: ModulePublisher,
    val events: EventSource,
    private val storage: Lazy<EncryptedStorage?>
) {
    /** Opens this module's own encrypted database (and only that one). */
    fun openDatabase(version: Int = 1): KuumaDatabase =
        (storage.value ?: error("no Android context: encrypted storage is unavailable in this runtime"))
            .open(namespace, version)
}

class ModuleRegistry(
    private val bus: EventBus,
    val permissions: PermissionRegistry = PermissionRegistry(),
    storage: Lazy<EncryptedStorage?> = lazy { null }
) {
    private val storage = storage
    private val real = LinkedHashMap<Namespace, KuumaModule>()
    private val fakes = CopyOnWriteArrayList<KuumaModule>()

    val modules: List<KuumaModule> get() = real.values.toList() + fakes

    @Synchronized
    fun register(module: KuumaModule) {
        if (module.isFake) {
            fakes.add(module)
        } else {
            require(module.namespace !in real) { "a ${module.namespace.wire} module is already registered" }
            real[module.namespace] = module
            permissions.register(module.namespace, module.permissions)
        }
        module.onRegister(ModuleContext(module.namespace, bus.publisherFor(module.namespace), bus, storage))
    }

    fun screens(): List<ScreenSlot> = real.values.flatMap { it.screens }
}
