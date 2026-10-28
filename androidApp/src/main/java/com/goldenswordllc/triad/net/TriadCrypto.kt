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
