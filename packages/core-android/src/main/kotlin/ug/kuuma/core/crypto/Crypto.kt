package ug.kuuma.core.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SHA-256 helpers. */
object Hashing {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** 64 lowercase hex characters: the contract `Sha256` type. */
    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/**
 * Salted, non-reversible references for messages, conversations and senders (contract `OpaqueRef`):
 * `or1_` + base64url(HMAC-SHA256(salt, value)) without padding.
 *
 * The salt is per device and never leaves it ([DeviceSalt]), so the same sender gets the
 * same ref on this phone but refs cannot be joined across phones or reversed in the cloud.
 */
class OpaqueRefs(private val salt: ByteArray) {
    init {
        require(salt.size >= 16) { "salt must be at least 16 bytes" }
    }

    fun ref(value: String): String =
        "or1_" + Base64Url.encode(Hashing.hmacSha256(salt, value.toByteArray(Charsets.UTF_8)))

    fun messageRef(appSource: String, conversationId: String, messageId: String) =
        ref("m|$appSource|$conversationId|$messageId")
    fun conversationRef(appSource: String, conversationId: String) = ref("c|$appSource|$conversationId")
    fun senderRef(appSource: String, senderId: String) = ref("s|$appSource|$senderId")
}

fun ByteArray.toHex(): String {
    val chars = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(chars[v ushr 4]).append(chars[v and 0x0f])
    }
    return out.toString()
}

/** Base64url without padding. Hand-rolled because java.util.Base64 needs API 26 and android.util.Base64 is not on the JVM. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n =
                (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xff shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63]).append(
                    ALPHABET[
                        n ushr 6 and
                            63
                    ]
                )
            }
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "not base64" }
            buffer = ((buffer shl 6) or v) and 0xffffff
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer ushr bits and 0xff)
            }
        }
        return out.toByteArray()
    }

    fun encodeStd(bytes: ByteArray): String {
        val url = encode(bytes).replace('-', '+').replace('_', '/')
        return url + "=".repeat((4 - url.length % 4) % 4)
    }
}
