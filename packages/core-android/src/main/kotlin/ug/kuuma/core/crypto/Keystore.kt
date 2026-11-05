package ug.kuuma.core.crypto

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * How a key is protected on this phone. Recorded so support and audits know which
 * guarantee a device actually has (Android 5.x phones cannot do everything 6.0+ can).
 */
enum class KeyProtection {
    /** Android 6.0+: AES-GCM / EC key inside the Keystore (TEE or StrongBox where present). */
    KEYSTORE_HARDWARE,

    /** Android 5.x: Keystore RSA/EC key via the legacy KeyPairGeneratorSpec. */
    KEYSTORE_LEGACY,

    /** Keystore unusable on this device: key kept in app-private storage. */
    SOFTWARE
}

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val PREFS = "kuuma_core_keys"

@Suppress("DEPRECATION")
internal fun legacyKeyPair(context: Context, alias: String, algorithm: String): Pair<PrivateKey, PublicKey> {
    val start = Calendar.getInstance()
    val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
    val spec = KeyPairGeneratorSpec.Builder(context)
        .setAlias(alias)
        .setKeyType(algorithm)
        .setSubject(X500Principal("CN=$alias"))
        .setSerialNumber(BigInteger.ONE)
        .setStartDate(start.time)
        .setEndDate(end.time)
        .build()
    val gen = KeyPairGenerator.getInstance(algorithm, ANDROID_KEYSTORE)
    gen.initialize(spec)
    val pair = gen.generateKeyPair()
    return pair.private to pair.public
}

/**
 * Wraps small secrets (database passphrases, the OpaqueRef salt) with a Keystore key.
 * Uses AES-GCM on API 23+, RSA on API 21–22, and plain app-private storage as a last resort.
 */
class SecretWrapper(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns the secret named [name], creating [size] random bytes the first time. */
    fun getOrCreate(name: String, size: Int = 32): Pair<ByteArray, KeyProtection> {
        val stored = prefs.getString("secret.$name", null)
        val protection = prefs.getString("protection.$name", null)?.let { KeyProtection.valueOf(it) }
        if (stored != null && protection != null) return unwrap(name, stored, protection) to protection
        val secret = ByteArray(size).also { SecureRandom().nextBytes(it) }
        val (wrapped, used) = wrap(name, secret)
        prefs.edit().putString("secret.$name", wrapped).putString("protection.$name", used.name).apply()
        return secret to used
    }

    private fun alias(name: String) = "kuuma.wrap.$name"

    private fun wrap(name: String, secret: ByteArray): Pair<String, KeyProtection> {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                gen.init(
                    KeyGenParameterSpec.Builder(
                        alias(name),
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, gen.generateKey())
                return (Base64Url.encode(cipher.iv) + "." + Base64Url.encode(cipher.doFinal(secret))) to
                    KeyProtection.KEYSTORE_HARDWARE
            }
            val pair = legacyKeyPair(context, alias(name), "RSA")
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pair.second)
            return Base64Url.encode(cipher.doFinal(secret)) to KeyProtection.KEYSTORE_LEGACY
        } catch (e: Exception) {
            // Some Android 5 devices ship a broken Keystore. Fall back, and record that we did.
            return Base64Url.encode(secret) to KeyProtection.SOFTWARE
        }
    }

    private fun unwrap(name: String, stored: String, protection: KeyProtection): ByteArray {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return when (protection) {
            KeyProtection.KEYSTORE_HARDWARE -> {
                val (iv, body) = stored.split(".")
                val key = ks.getKey(alias(name), null) as SecretKey
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64Url.decode(iv)))
                cipher.doFinal(Base64Url.decode(body))
            }
            KeyProtection.KEYSTORE_LEGACY -> {
                val key = ks.getKey(alias(name), null) as PrivateKey
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.DECRYPT_MODE, key)
                cipher.doFinal(Base64Url.decode(stored))
            }
            KeyProtection.SOFTWARE -> Base64Url.decode(stored)
        }
    }
}

/**
 * The device's ECDSA P-256 signing key, used to sign API requests (see kuuma-server-common auth).
 * Keystore-backed where possible; on failure a software key in app-private storage.
 */
class DeviceSigner(private val context: Context) {
    private val alias = "kuuma.device.sign"
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val protection: KeyProtection
        get() = prefs.getString("protection.signer", null)?.let { KeyProtection.valueOf(it) } ?: ensure()

    private fun ensure(): KeyProtection {
        val existing = prefs.getString("protection.signer", null)
        if (existing != null) return KeyProtection.valueOf(existing)
        val used = try {
            if (Build.VERSION.SDK_INT >= 23) {
                val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                gen.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                gen.generateKeyPair()
                KeyProtection.KEYSTORE_HARDWARE
            } else {
                legacyKeyPair(context, alias, "EC")
                KeyProtection.KEYSTORE_LEGACY
            }
        } catch (e: Exception) {
            softwareKey()
            KeyProtection.SOFTWARE
        }
        prefs.edit().putString("protection.signer", used.name).apply()
        return used
    }

    private fun softwareKey() {
        if (prefs.getString("signer.sw.private", null) != null) return
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        prefs.edit()
            .putString("signer.sw.private", Base64Url.encode(pair.private.encoded))
            .putString("signer.sw.public", Base64Url.encode(pair.public.encoded))
            .apply()
    }

    private fun privateKey(): PrivateKey = when (protection) {
        KeyProtection.KEYSTORE_HARDWARE, KeyProtection.KEYSTORE_LEGACY ->
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as PrivateKey
        KeyProtection.SOFTWARE -> KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64Url.decode(prefs.getString("signer.sw.private", null)!!)))
    }

    /** Base64 X.509 SubjectPublicKeyInfo, as sent in PairingRequest.devicePublicKey. */
    fun publicKeyBase64(): String {
        val encoded = when (protection) {
            KeyProtection.SOFTWARE -> Base64Url.decode(prefs.getString("signer.sw.public", null)!!)
            else -> KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getCertificate(alias).publicKey.encoded
        }
        return Base64Url.encodeStd(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)).encoded)
    }

    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey())
        update(data)
        sign()
    }

    /** Headers for a signed request, matching kuuma-server-common's canonical request. */
    fun requestHeaders(
        deviceId: String,
        method: String,
        path: String,
        body: ByteArray,
        nowSeconds: Long
    ): Map<String, String> {
        val ts = nowSeconds.toString()
        val canonical = "${method.uppercase()}\n$path\n$ts\n${Hashing.sha256Hex(body)}".toByteArray()
        return mapOf(
            "X-Kuuma-Device-Id" to deviceId,
            "X-Kuuma-Timestamp" to ts,
            "X-Kuuma-Signature" to Base64Url.encodeStd(sign(canonical))
        )
    }
}
