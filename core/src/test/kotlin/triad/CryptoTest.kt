package triad

import triad.core.Ed25519
import kotlin.test.*

class CryptoTest {
    @Test fun `sign and verify roundtrip`() {
        val kp = Ed25519.generate()
        val sig = Ed25519.sign(Ed25519.encodePrivate(kp), "token123".toByteArray())
        assertTrue(Ed25519.verify(Ed25519.encodePublic(kp), sig, "token123".toByteArray()))
        assertFalse(Ed25519.verify(Ed25519.encodePublic(kp), sig, "tampered".toByteArray()))
    }

    @Test fun `wrong key fails verification`() {
        val a = Ed25519.generate()
        val b = Ed25519.generate()
        val sig = Ed25519.sign(Ed25519.encodePrivate(a), "m".toByteArray())
        assertFalse(Ed25519.verify(Ed25519.encodePublic(b), sig, "m".toByteArray()))
        assertTrue(Ed25519.verify(Ed25519.encodePublic(a), sig, "m".toByteArray()))
    }
}
