package ug.kuuma.casefile.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ug.kuuma.core.crypto.Hashing

/** What a sealed item is. */
enum class ItemKind {
    /** A Sentinel flag (labels and refs). */
    FLAG,

    /** A later change to a flag: new labels, grooming signals or a dismissal. */
    FLAG_UPDATE,

    /** One captured message, with its text. */
    MESSAGE,

    /** A screenshot or other media: hash and metadata only, never the media itself. */
    MEDIA,

    /** A chain-of-custody entry: who viewed, exported or shared what, and when. */
    CUSTODY
}

/** One stored item. [contentJson] is canonical JSON, exactly the bytes that were hashed. */
data class VaultItem(
    val itemId: String,
    val kind: ItemKind,
    val caseId: String?,
    val flagId: String?,
    val contentJson: String,
    val storedAt: Long
)

/** One link of the append-only hash chain. */
data class ChainEntry(
    val index: Long,
    val itemId: String,
    val itemHash: String,
    val prevHash: String,
    val chainHash: String,
    /** Base64 DER ECDSA-P256-SHA256 signature over [chainHash] (ASCII). */
    val signature: String,
    val sealedAt: Long
)

/** Signs chain links with the device key (core-android DeviceSigner on the phone). */
interface ItemSigner {
    fun sign(data: ByteArray): ByteArray
    fun verify(data: ByteArray, signature: ByteArray): Boolean

    /** Base64 X.509 SubjectPublicKeyInfo, for recipients to check signatures. */
    fun publicKeyBase64(): String

    /** e.g. KEYSTORE_HARDWARE or SOFTWARE: written into every case file. */
    val protection: String
}

/** Storage for the vault (vault.db on the phone). Append-only for items and chain links. */
interface VaultStore {
    fun append(item: VaultItem, entry: ChainEntry)
    fun items(): List<VaultItem>
    fun item(itemId: String): VaultItem?
    fun chain(): List<ChainEntry>
    fun head(): ChainEntry?
    fun meta(key: String): String?
    fun setMeta(key: String, value: String)

    /** Permanent deletion of everything (retention). The only way anything is ever removed. */
    fun wipe()
}

/**
 * The tamper-evident vault.
 *
 *   itemHash  = sha256("kuuma-vault/1\n" + kind + "\n" + contentJson)
 *   chainHash = sha256(prevHash + "|" + itemHash + "|" + index), prevHash of the first link = 64 zeros
 *   signature = ECDSA-P256-SHA256(chainHash)
 *
 * The same formulas are in modules/casefile/web (the verification page) and the case file.
 */
class Vault(
    private val store: VaultStore,
    private val signer: ItemSigner,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun seal(itemId: String, kind: ItemKind, caseId: String?, flagId: String?, content: JsonObject): ChainEntry {
        val json = canonical(content)
        val item = VaultItem(itemId, kind, caseId, flagId, json, clock())
        val head = store.head()
        val index = (head?.index ?: -1) + 1
        val prev = head?.chainHash ?: GENESIS
        val itemHash = itemHash(kind, json)
        val chainHash = chainHash(prev, itemHash, index)
        val sig = Base64.encode(signer.sign(chainHash.toByteArray(Charsets.US_ASCII)))
        val entry = ChainEntry(index, itemId, itemHash, prev, chainHash, sig, item.storedAt)
        store.append(item, entry)
        store.setMeta(META_LENGTH, (index + 1).toString())
        return entry
    }

    /** The current chain root (hash of the last link), or genesis for an empty vault. */
    fun root(): String = store.head()?.chainHash ?: GENESIS

    /** Recomputes every hash, link and signature. Any edit, deletion or reordering shows up here. */
    fun verify(): ChainReport {
        val chain = store.chain().sortedBy { it.index }
        val items = store.items().associateBy { it.itemId }
        val problems = mutableListOf<ChainProblem>()
        var prev = GENESIS
        chain.forEachIndexed { position, e ->
            if (e.index !=
                position.toLong()
            ) {
                problems += ChainProblem(position.toLong(), ChainProblem.Kind.LINK_MISSING)
            }
            if (e.prevHash != prev) problems += ChainProblem(e.index, ChainProblem.Kind.CHAIN_BROKEN)
            if (chainHash(e.prevHash, e.itemHash, e.index) !=
                e.chainHash
            ) {
                problems += ChainProblem(e.index, ChainProblem.Kind.CHAIN_BROKEN)
            }
            val ok = runCatching {
                signer.verify(e.chainHash.toByteArray(Charsets.US_ASCII), Base64.decode(e.signature))
            }.getOrDefault(false)
            if (!ok) problems += ChainProblem(e.index, ChainProblem.Kind.BAD_SIGNATURE)
            val item = items[e.itemId]
            when {
                item == null -> problems += ChainProblem(e.index, ChainProblem.Kind.ITEM_MISSING)
                itemHash(item.kind, item.contentJson) != e.itemHash ->
                    problems +=
                        ChainProblem(e.index, ChainProblem.Kind.ITEM_ALTERED)
            }
            prev = e.chainHash
        }
        val chained = chain.map { it.itemId }.toSet()
        items.keys.filterNot { it in chained }.forEach {
            problems +=
                ChainProblem(-1, ChainProblem.Kind.ITEM_NOT_CHAINED)
        }
        val recordedLength = store.meta(META_LENGTH)?.toLongOrNull()
        if (recordedLength != null &&
            recordedLength != chain.size.toLong()
        ) {
            problems +=
                ChainProblem(chain.size.toLong(), ChainProblem.Kind.LINK_MISSING)
        }
        return ChainReport(problems.isEmpty(), chain.size.toLong(), prev, problems.distinct())
    }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"
        const val FORMAT = "kuuma-vault/1"
        private const val META_LENGTH = "chain.length"

        fun itemHash(kind: ItemKind, contentJson: String) =
            Hashing.sha256Hex("$FORMAT\n${kind.name}\n$contentJson".toByteArray(Charsets.UTF_8))

        fun chainHash(prev: String, itemHash: String, index: Long) =
            Hashing.sha256Hex("$prev|$itemHash|$index".toByteArray(Charsets.US_ASCII))

        /** Canonical JSON: object keys sorted, no whitespace, so the same content always hashes the same. */
        fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) ->
                JsonPrimitive(k).toString() +
                    ":" +
                    canonical(v)
            }
            is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            is JsonNull -> "null"
            is JsonPrimitive -> e.toString()
        }
    }
}

data class ChainProblem(val index: Long, val kind: Kind) {
    enum class Kind { ITEM_ALTERED, ITEM_MISSING, ITEM_NOT_CHAINED, CHAIN_BROKEN, LINK_MISSING, BAD_SIGNATURE }
}

data class ChainReport(val intact: Boolean, val length: Long, val root: String, val problems: List<ChainProblem>)

/** Standard base64 with padding (JVM and Android 5 safe). */
object Base64 {
    fun encode(b: ByteArray): String = ug.kuuma.core.crypto.Base64Url.encodeStd(b)
    fun decode(s: String): ByteArray = ug.kuuma.core.crypto.Base64Url.decode(s)
}
