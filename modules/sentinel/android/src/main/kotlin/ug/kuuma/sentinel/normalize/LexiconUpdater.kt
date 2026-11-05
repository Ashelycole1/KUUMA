package ug.kuuma.sentinel.normalize

import java.io.File
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.sentinel.cloud.AiGateway
import ug.kuuma.sentinel.cloud.DeviceState

/**
 * Keeps the lexicon current from GET /v1/ai/lexicon/latest.
 *  - checks at most daily on Wi-Fi, weekly on mobile data (Wi-Fi where possible);
 *  - installs only if the version is newer and the download's SHA-256 matches the manifest,
 *    which the gateway served over a device-signed request.
 */
class LexiconUpdater(
    private val gateway: AiGateway,
    private val dir: File?,
    private val normaliser: Normaliser,
    private val clock: () -> Long = System::currentTimeMillis
) {
    var installedVersion: String = "0.0.0"
        private set
    private var lastCheck = 0L

    /** Load a previously installed bundle, if any. */
    fun loadInstalled() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching { LexiconBundle.parse(f.readText()) }.onSuccess {
            normaliser.update(it)
            installedVersion = it.version
        }
    }

    /** Returns true if a new lexicon was installed. Never throws. */
    fun maybeUpdate(device: DeviceState): Boolean {
        val now = clock()
        val interval = if (device.unmetered && !device.dataSaver) DAY else WEEK
        if (!device.online || now - lastCheck < interval) return false
        lastCheck = now
        return try {
            val manifest = gateway.latestLexicon()
            if (compareVersions(manifest.version, installedVersion) <= 0) return false
            val bytes = gateway.download(manifest.url)
            if (Hashing.sha256Hex(bytes) != manifest.sha256) {
                SafeLog.w("sentinel.lexicon.hash_mismatch")
                return false
            }
            val bundle = LexiconBundle.parse(String(bytes, Charsets.UTF_8))
            normaliser.update(bundle)
            installedVersion = bundle.version
            file()?.let { f ->
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            SafeLog.i("sentinel.lexicon.installed", LogField.num("entries", bundle.files.sumOf { it.entries.size }))
            true
        } catch (e: Exception) {
            SafeLog.w("sentinel.lexicon.update_failed")
            false
        }
    }

    private fun file(): File? = dir?.let { File(it, "lexicon.json") }

    companion object {
        const val DAY = 24 * 3_600_000L
        const val WEEK = 7 * DAY

        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
