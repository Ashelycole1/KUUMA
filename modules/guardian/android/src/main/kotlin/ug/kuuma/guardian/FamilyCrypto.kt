package ug.kuuma.guardian

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import ug.kuuma.contracts.Policy
import ug.kuuma.core.crypto.Base64Url

/**
 * The family key: 32 random bytes the parent's phone makes when adding a child and passes
 * only inside the pairing QR code. The server never has it, so it can neither read alert
 * excerpts nor forge a policy or an approval.
 *
 * The same formulas are in kuuma_guardian (Dart) on the parent's phone.
 */
class FamilyCrypto(private val key: ByteArray) {
    init {
        require(key.size == 32) { "family key must be 32 bytes" }
    }

    fun sign(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64Url.encode(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun verify(message: String, signature: String?): Boolean =
        signature != null && MessageDigest.isEqual(sign(message).toByteArray(), signature.toByteArray())

    fun verifyPolicy(p: Policy) = verify(policyMessage(p), p.signature)

    fun verifyDecision(approvalId: String, requestId: String, decision: String, signature: String?) =
        verify(decisionMessage(approvalId, requestId, decision), signature)

    /** Seals an excerpt for the parent's phone: base64url(IV || AES-256-GCM ciphertext). */
    fun seal(text: String, random: SecureRandom = SecureRandom()): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64Url.encode(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun open(sealed: String): String {
        val raw = Base64Url.decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    }

    companion object {
        fun policyMessage(p: Policy): String = listOf(
            p.ageBracket.wire,
            p.watchedApps.map { it.wire }.sorted().joinToString(","),
            String.format(Locale.US, "%.3f", p.thresholds.flagMinConfidence),
            p.thresholds.alertMinSeverity.wire,
            p.thresholds.captureMinSeverity.wire,
            p.policyVersion.toString()
        ).joinToString("|")

        /** Not decidedAt: the server stamps that after the parent signs. */
        fun decisionMessage(approvalId: String, requestId: String, decision: String) =
            "$approvalId|$requestId|$decision"

        /** What the parent signs to report a case (same formula in kuuma_guardian). */
        fun reportMessage(reportId: String, caseId: String, recipient: String, anonymous: Boolean) =
            "report|$reportId|$caseId|$recipient|$anonymous"
    }
}

/** The pairing QR payload: `kuuma-pair:1:<6-digit code>:<base64url family key>`. */
data class PairingQr(val code: String, val familyKey: ByteArray) {
    companion object {
        private val FORMAT = Regex("^kuuma-pair:1:([0-9]{6}):([A-Za-z0-9_-]{43})$")

        fun parse(text: String): PairingQr? = FORMAT.matchEntire(text.trim())?.let { m ->
            PairingQr(m.groupValues[1], Base64Url.decode(m.groupValues[2]))
        }
    }
}
