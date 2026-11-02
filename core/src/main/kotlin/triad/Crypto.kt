package triad.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Ed25519 {
    fun generate(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    fun encodePublic(kp: KeyPair): String = Base64.getEncoder().encodeToString(kp.public.encoded)
    fun encodePrivate(kp: KeyPair): String = Base64.getEncoder().encodeToString(kp.private.encoded)

    fun sign(privB64: String, msg: ByteArray): String {
        val key = KeyFactoryB64.privateKey(privB64)
        val sig = Signature.getInstance("Ed25519").apply { initSign(key) }.apply { update(msg) }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    fun verify(pubB64: String, sigB64: String, msg: ByteArray): Boolean = runCatching {
        val key = KeyFactoryB64.publicKey(pubB64)
        val sig = Base64.getDecoder().decode(sigB64)
        Signature.getInstance("Ed25519").apply { initVerify(key) }.apply { update(msg) }.verify(sig)
    }.getOrDefault(false)

    private object KeyFactoryB64 {
        fun publicKey(b64: String) = java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
        fun privateKey(b64: String) = java.security.KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
    }
}
