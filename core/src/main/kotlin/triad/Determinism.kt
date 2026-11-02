package triad.core

import java.security.MessageDigest

class SplitMix64(private var seed: Long) {
    fun nextULong(): Long {
        seed += -0x61C8864680B583EBL
        var z = seed
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
        return z xor (z ushr 31)
    }
    fun nextInt(bound: Int): Int = ((nextULong() ushr 33) % bound).toInt()
}

fun SplitMix64.sample5(pool: List<String>): List<String> {
    val p = pool.toMutableList(); val out = ArrayList<String>(5)
    repeat(5) { out.add(p.removeAt(nextInt(p.size))) }
    return out
}

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun seedFromHex(hex: String): Long = java.lang.Long.parseUnsignedLong(hex.take(16), 16)

fun randomHex(bytes: Int): String =
    ByteArray(bytes).also { kotlin.random.Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
