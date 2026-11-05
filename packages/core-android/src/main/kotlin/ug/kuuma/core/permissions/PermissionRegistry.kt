package ug.kuuma.core.permissions

import java.util.concurrent.CopyOnWriteArrayList
import ug.kuuma.contracts.Namespace

/** How a permission is granted. */
enum class GrantKind {
    /** A normal runtime permission dialog (API 23+); granted at install on API 21–22. */
    RUNTIME,

    /** A system settings screen the user must visit, e.g. accessibility or notification access. */
    SPECIAL_ACCESS,

    /** Granted at install; listed so the setup wizard can explain it. */
    INSTALL
}

/**
 * One Android permission a module needs. Guardian's setup wizard reads all of these and
 * walks the parent and child through them in order.
 *
 * @param reasonKey kuuma_ui translation key explaining why, e.g. "sentinel.permission.accessibility.reason".
 */
data class KuumaPermission(
    val permission: String,
    val module: Namespace,
    val reasonKey: String,
    val kind: GrantKind = GrantKind.RUNTIME,
    val required: Boolean = true,
    val minSdk: Int = 21,
    val maxSdk: Int? = null
) {
    fun appliesTo(sdk: Int) = sdk >= minSdk && (maxSdk == null || sdk <= maxSdk)
}

class PermissionRegistry {
    private val entries = CopyOnWriteArrayList<KuumaPermission>()

    fun register(module: Namespace, permissions: List<KuumaPermission>) {
        permissions.forEach {
            require(it.module == module) { "${module.wire} may only declare its own permissions" }
            require(it.reasonKey.startsWith(module.wire + ".")) { "reasonKey must use the ${module.wire}. prefix" }
        }
        entries.addAll(permissions)
    }

    fun all(sdk: Int? = null): List<KuumaPermission> = entries.filter { sdk == null || it.appliesTo(sdk) }

    fun forModule(module: Namespace): List<KuumaPermission> = entries.filter { it.module == module }

    /** Distinct Android permission strings, for checking against the merged manifest. */
    fun androidPermissions(): Set<String> = entries.map { it.permission }.toSet()
}
