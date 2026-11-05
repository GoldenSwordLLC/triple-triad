package com.goldenswordllc.triad.net

import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object TriadCrypto {
    private val bc by lazy { BouncyCastleProvider() }

    fun ensureIdentity(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences("triad", Context.MODE_PRIVATE)
        prefs.getString("id_pub", null)?.let { pub ->
            return pub to (prefs.getString("id_priv", null) ?: regenerate(context))
        }
        val (pub, priv) = generatePair()
        prefs.edit().putString("id_pub", pub).putString("id_priv", priv).apply()
        return pub to priv
    }

    private fun regenerate(context: Context): String {
        val (pub, priv) = generatePair()
        context.getSharedPreferences("triad", Context.MODE_PRIVATE).edit()
            .putString("id_pub", pub).putString("id_priv", priv).apply()
        return priv
    }

    private fun generatePair(): Pair<String, String> {
        val kp = try {
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        } catch (_: Exception) {
            KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair()
        }
        return b64(kp.public.encoded) to b64(kp.private.encoded)
    }

    /// Hex signature (what the relay's identity protocol expects).
    fun signHex(privB64: String, message: String): String {
        val sig = sign(privB64, message.toByteArray())
        return Base64.getDecoder().decode(sig).joinToString("") { "%02x".format(it) }
    }

    fun sign(privB64: String, msg: ByteArray): String = runCatching {
        signJca(privB64, msg)
    }.recoverCatching {
        signBc(privB64, msg)
    }.getOrThrow()

    fun verify(pubB64: String, sigB64: String, msg: ByteArray): Boolean =
        runCatching { verifyJca(pubB64, sigB64, msg) }.getOrDefault(false) ||
            runCatching { verifyBc(pubB64, sigB64, msg) }.getOrDefault(false)

    private fun signJca(privB64: String, msg: ByteArray): String {
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(unb64(privB64)))
        return b64(Signature.getInstance("Ed25519").run { initSign(key); update(msg); sign() })
    }
    private fun signBc(privB64: String, msg: ByteArray): String {
        val key = KeyFactory.getInstance("Ed25519", bc).generatePrivate(PKCS8EncodedKeySpec(unb64(privB64)))
        return b64(Signature.getInstance("Ed25519", bc).run { initSign(key); update(msg); sign() })
    }
    private fun verifyJca(pubB64: String, sigB64: String, msg: ByteArray): Boolean {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(unb64(pubB64)))
        return Signature.getInstance("Ed25519").run { initVerify(key); update(msg); verify(unb64(sigB64)) }
    }
    private fun verifyBc(pubB64: String, sigB64: String, msg: ByteArray): Boolean {
        val key = KeyFactory.getInstance("Ed25519", bc).generatePublic(X509EncodedKeySpec(unb64(pubB64)))
        return Signature.getInstance("Ed25519", bc).run { initVerify(key); update(msg); verify(unb64(sigB64)) }
    }

    fun peerResolver(context: Context): (String, String) -> Boolean = { pub, name ->
        val prefs = context.getSharedPreferences("triad", Context.MODE_PRIVATE)
        val k = "peer:$pub"
        if (prefs.contains(k)) true
        else { prefs.edit().putString(k, name).apply(); false }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}


/** The relay identity, hardware-backed where the device allows it.
 *
 * Three tiers: Android Keystore ed25519 (API 33+ AND a KeyMint HAL that actually
 * supports it — upgraded devices often run Keymaster 4.x, which rejects ed25519
 * generation at runtime, so the attempt itself is the feature check), falling
 * back to the persisted SPKI/PKCS8 pair in SharedPreferences. Keystore keys
 * cannot be imported, so an install that already had an SP key ROTATES once to
 * a Keystore identity when support is available; the SP key material is then
 * erased. A one-time self-test signature proves the HAL can sign before the
 * rotation commits, so a half-broken HAL leaves the old identity in place. */
@Volatile private var cachedIdentity: triad.net.RelayIdentity? = null

fun android.content.Context.relayIdentity(): triad.net.RelayIdentity {
    cachedIdentity?.let { return it }
    val ident = keystoreRelayIdentity() ?: spRelayIdentity()
    cachedIdentity = ident
    return ident
}

private const val KS_ALIAS = "triad-relay-id"

private fun android.content.Context.keystoreRelayIdentity(): triad.net.RelayIdentity? {
    if (android.os.Build.VERSION.SDK_INT < 33) return null
    val prefs = getSharedPreferences("triad", android.content.Context.MODE_PRIVATE)
    // a recorded SP verdict is sticky: HAL capabilities don't change under us
    if (prefs.getString("id_backend", null) == "sp") return null
    return try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = (ks.getEntry(KS_ALIAS, null) as? java.security.KeyStore.PrivateKeyEntry)
            ?: run {
                val kpg = java.security.KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
                kpg.initialize(android.security.keystore.KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_SIGN or
                        android.security.keystore.KeyProperties.PURPOSE_VERIFY).build())
                kpg.generateKeyPair()
                ks.getEntry(KS_ALIAS, null) as java.security.KeyStore.PrivateKeyEntry
            }
        val ident = object : triad.net.RelayIdentity {
            // the X.509 SPKI tail of an ed25519 key IS the raw 32-byte key we put on
            // the wire (the same bytes the SP path extracts)
            override val pubkeyHex =
                entry.certificate.publicKey.encoded.takeLast(32).toHex()
            override fun sign(message: String): String =
                java.security.Signature.getInstance("Ed25519").run {
                    initSign(entry.privateKey); update(message.toByteArray()); sign()
                }.toHex()
        }
        ident.sign("triad-keystore-selftest")   // prove signing works before committing
        prefs.edit().putString("id_backend", "ks")
            .remove("id_priv").remove("id_pub").apply()   // rotation: erase the SP copy
        ident
    } catch (_: Throwable) {
        prefs.edit().putString("id_backend", "sp").apply()
        null
    }
}

/** Legacy path: the persisted SPKI/PKCS8 base64 pair. No KeyFactory here — JCA
 *  ed25519 exists only on API 33+, and this runs during composition. The raw
 *  32-byte key is the tail of the SPKI blob; signing goes through TriadCrypto's
 *  JCA-then-BouncyCastle fallbacks, which work on every API level. */
private fun android.content.Context.spRelayIdentity(): triad.net.RelayIdentity {
    val (pub, priv) = TriadCrypto.ensureIdentity(this)
    return object : triad.net.RelayIdentity {
        override val pubkeyHex = java.util.Base64.getDecoder().decode(pub).takeLast(32).toHex()
        override fun sign(message: String): String = TriadCrypto.signHex(priv, message)
    }
}

private fun List<Byte>.toHex(): String = joinToString("") { "%02x".format(it) }
private fun ByteArray.toHex(): String = toList().toHex()
