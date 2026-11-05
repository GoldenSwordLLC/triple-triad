package triad

import triad.net.LineAssembler
import kotlin.test.*

class LineAssemblerTest {
    @Test fun `splits multiple lines and keeps partial tails`() {
        val a = LineAssembler()
        val got = mutableListOf<String>()
        assertTrue(a.feed("one\ntw".toByteArray()) { got.add(it) })
        assertTrue(a.feed("o".toByteArray()) { got.add(it) })
        assertTrue(a.feed("\nthree\n".toByteArray()) { got.add(it) })
        assertEquals(listOf("one", "two", "three"), got)
    }

    @Test fun `empty lines are dropped`() {
        val a = LineAssembler()
        val got = mutableListOf<String>()
        assertTrue(a.feed("\n\nx\n".toByteArray()) { got.add(it) })
        assertEquals(listOf("x"), got)
    }

    @Test fun `a line past the cap is refused`() {
        val a = LineAssembler(maxBytes = 16)
        assertFalse(a.feed(ByteArray(32) { 'a'.code.toByte() }) {})
    }

    @Test fun `refusal happens as soon as the pending line crosses the cap`() {
        val a = LineAssembler(maxBytes = 16)
        assertTrue(a.feed("ab".toByteArray()) {})
        assertTrue(a.feed(ByteArray(13) { 'c'.code.toByte() }) {})   // 15 pending, still legal
        assertFalse(a.feed(ByteArray(2) { 'd'.code.toByte() }) {})  // 17 pending: refused
    }

    @Test fun `full lines before an oversized tail still deliver`() {
        val b = LineAssembler(maxBytes = 8)
        val seen = mutableListOf<String>()
        assertFalse(b.feed("short\n12345678901234".toByteArray()) { seen.add(it) })
        assertEquals(listOf("short"), seen)
    }
}
