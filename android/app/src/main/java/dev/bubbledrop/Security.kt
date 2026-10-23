package dev.bubbledrop

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * This device's TLS identity: a P-256 key that never leaves the Android Keystore, with the
 * self-signed certificate the Keystore issues for it. Peers know us by the certificate's SHA-256.
 */
class TlsIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String = fingerprintOf(certificate)

    /** An SSLContext presenting this identity. [trustManager] decides what to accept from the peer. */
    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(privateKey, arrayOf(certificate))), arrayOf(trustManager), SecureRandom())
        }

    companion object {
        private const val ALIAS = "bubbledrop-tls"

        fun loadOrCreate(): TlsIdentity {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                val now = System.currentTimeMillis()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(
                                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
                            )
                            .setCertificateSubject(X500Principal("CN=BubbleDrop"))
                            .setCertificateSerialNumber(BigInteger.valueOf(now))
                            .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
                            .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 3600 * 1000))
                            .build()
                    )
                    generateKeyPair()
                }
            }
            val key = keyStore.getKey(ALIAS, null) as PrivateKey
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
            return TlsIdentity(key, certificate)
        }
    }
}

fun fingerprintOf(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

/** Always presents our one EC key, for both client and server roles. */
private class SingleKeyManager(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    private fun aliasFor(keyType: String?) = if (keyType == null || keyType.startsWith("EC")) ALIAS else null

    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        if (keyTypes == null || keyTypes.any { it.startsWith("EC") }) ALIAS else null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = aliasFor(keyType)
    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        chooseClientAlias(keyTypes, issuers, null)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        aliasFor(keyType)
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = chain
    override fun getPrivateKey(alias: String?) = key

    companion object {
        const val ALIAS = "bubbledrop"
    }
}

/**
 * Certificates are self-signed, so chain validation is replaced by fingerprint pinning:
 * with [pinned] set, any other server certificate fails the handshake; otherwise any is
 * accepted and recorded in [seen] for the caller to decide. Client certificates are always
 * accepted here and checked against the trust store per request.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
class PinningTrustManager(private val pinned: String? = null) : X509TrustManager {
    @Volatile var seen: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val fingerprint = chain.firstOrNull()?.let(::fingerprintOf) ?: throw CertificateException("no certificate")
        if (pinned != null && fingerprint != pinned) throw CertificateException("certificate does not match pairing")
        seen = fingerprint
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
        seen = chain.firstOrNull()?.let(::fingerprintOf) ?: throw CertificateException("no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Commit-then-reveal numeric comparison (PROTOCOL.md "Pairing"). */
object PairingMath {
    fun commitment(clientFingerprint: String, serverFingerprint: String, serverNonce: ByteArray): ByteArray =
        sha256("bubbledrop-commit-v2".toByteArray() + clientFingerprint.toByteArray() +
            serverFingerprint.toByteArray() + serverNonce)

    fun code(clientFingerprint: String, serverFingerprint: String, clientNonce: ByteArray, serverNonce: ByteArray): String {
        val digest = sha256("bubbledrop-code-v2".toByteArray() + clientFingerprint.toByteArray() +
            serverFingerprint.toByteArray() + clientNonce + serverNonce)
        val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }

    fun nonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun sha256(input: ByteArray) = MessageDigest.getInstance("SHA-256").digest(input)
}

data class TrustedDevice(
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val platform: String,
    val pairedAt: Long,
)

/** Paired devices, keyed by certificate fingerprint. */
class TrustStore(context: Context) {
    private val prefs = context.getSharedPreferences("trust", Context.MODE_PRIVATE)
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<TrustedDevice>> = _devices

    fun isTrusted(fingerprint: String) = _devices.value.any { it.fingerprint == fingerprint }

    fun device(fingerprint: String) = _devices.value.firstOrNull { it.fingerprint == fingerprint }

    /** Trusts [fingerprint], replacing any earlier certificate of the same device. */
    @Synchronized
    fun add(fingerprint: String, device: DeviceInfo) = save(
        _devices.value.filterNot { it.fingerprint == fingerprint || it.deviceId == device.id } +
            TrustedDevice(fingerprint, device.id, device.name, device.platform, System.currentTimeMillis())
    )

    @Synchronized
    fun remove(fingerprint: String) = save(_devices.value.filterNot { it.fingerprint == fingerprint })

    private fun save(devices: List<TrustedDevice>) {
        val json = JSONArray(devices.map {
            JSONObject().put("fingerprint", it.fingerprint).put("deviceId", it.deviceId).put("name", it.name)
                .put("platform", it.platform).put("pairedAt", it.pairedAt)
        })
        prefs.edit().putString("devices", json.toString()).apply()
        _devices.value = devices
    }

    private fun load(): List<TrustedDevice> {
        val json = JSONArray(prefs.getString("devices", "[]"))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            TrustedDevice(o.getString("fingerprint"), o.getString("deviceId"), o.getString("name"),
                o.optString("platform"), o.optLong("pairedAt"))
        }
    }
}
