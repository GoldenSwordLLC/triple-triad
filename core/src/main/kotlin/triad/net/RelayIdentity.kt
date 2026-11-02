package triad.net

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** Host identity for the online relay: the persisted ed25519 device keypair. The room
 *  code is bound to this key at HOST time and every parking heartbeat is signed by it,
 *  so a parked room cannot be kept alive by anyone but its host. */
interface RelayIdentity {
    val pubkeyHex: String                                  // 64 hex chars (32 bytes)
    fun sign(message: String): String                     // 128 hex chars (64 bytes)
}

/** Pure-JVM identity (tests, tools): java.security Ed25519, keys held in memory. */
class JcaRelayIdentity(keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()) : RelayIdentity {
    private val pub = keyPair.public.encoded
    private val priv = keyPair.private

    /** JCA gives SPKI/PKCS8 encodings — the last 32 bytes are the raw ed25519 key. */
    override val pubkeyHex: String =
        pub.copyOfRange(pub.size - 32, pub.size).joinToString("") { "%02x".format(it) }

    override fun sign(message: String): String {
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(priv); update(message.toByteArray())
        }.sign()
        return sig.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Bridge an app-persisted pair (Base64 SPKI/PKCS8) into an identity. */
        fun fromBase64(pubB64: String, privB64: String): JcaRelayIdentity {
            val kfPub = java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
            val kfPriv = java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
            return JcaRelayIdentity(KeyPair(kfPub, kfPriv))
        }
    }
}

/** Canonical identity message formats — must match server/relay.py exactly. */
object RelayIdentityProto {
    fun hostMessage(pubkeyHex: String, unixSeconds: Long) = "TRIAD-HOST|$pubkeyHex|$unixSeconds"
    fun pongMessage(code: String, nonce: String) = "TRIAD-PONG|$code|$nonce"
}
