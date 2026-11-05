package triad.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.io.IOException
import java.net.Socket

interface Wire {
    var onLine: ((String) -> Unit)?
    var onClose: (() -> Unit)?
    fun send(line: String)
    fun close()
}

const val MAX_LINE_BYTES = 128 * 1024

/** Newline-framed line assembly with a hard cap. The largest legitimate message
 *  (a full collection declaration) is a few tens of KB, so anything past the cap
 *  is a hostile or broken peer: the wire must be killed, not the heap filled. */
class LineAssembler(private val maxBytes: Int = MAX_LINE_BYTES) {
    private val buf = java.io.ByteArrayOutputStream()

    /** Feed raw bytes; complete lines go to [onLine]. False = a line exceeded the
     *  cap — the caller should drop the connection. */
    fun feed(bytes: ByteArray, onLine: (String) -> Unit): Boolean {
        var start = 0
        while (true) {
            val nl = bytes.indexOfByte('\n'.code.toByte(), start)
            if (nl < 0) break
            if (buf.size() + (nl - start) > maxBytes) return false
            buf.write(bytes, start, nl - start)
            val line = buf.toString("UTF-8")
            buf.reset()
            if (line.isNotEmpty()) onLine(line)
            start = nl + 1
        }
        if (start < bytes.size) {
            if (buf.size() + (bytes.size - start) > maxBytes) return false
            buf.write(bytes, start, bytes.size - start)
        }
        return true
    }

    private fun ByteArray.indexOfByte(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }
}

class TcpWire(private val socket: Socket, private val scope: CoroutineScope) : Wire {
    private val pendingLines = ArrayDeque<String>()
    @Volatile private var closedFired = false

    override var onLine: ((String) -> Unit)? = null
        set(value) { field = value; if (value != null) while (pendingLines.isNotEmpty()) value(pendingLines.removeFirst()) }
    override var onClose: (() -> Unit)? = null
        set(value) { field = value; if (closedFired) value?.invoke() }

    private val outbound = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            val out = java.io.BufferedOutputStream(socket.getOutputStream())
            try {
                for (line in outbound) {
                    out.write((line + "\n").toByteArray()); out.flush()
                }
                out.flush()
            } catch (_: IOException) {}
            runCatching { socket.close() }
            if (!closedFired) { closedFired = true; onClose?.invoke() }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val inp = BufferedInputStream(socket.getInputStream())
                val asm = LineAssembler()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    if (!asm.feed(chunk.copyOf(n)) { deliver(it) }) break
                }
            } catch (_: IOException) {}
            close()
        }
    }

    private fun deliver(line: String) { onLine?.invoke(line) ?: pendingLines.addLast(line) }
    override fun send(line: String) { outbound.trySend(line) }
    override fun close() {

        outbound.close()
    }
}

fun sameIpv4Subnet(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    val pa = a.split('.'); val pb = b.split('.')
    if (pa.size != 4 || pb.size != 4) return false
    val na = pa.map { it.toIntOrNull() ?: return false }
    val nb = pb.map { it.toIntOrNull() ?: return false }
    if (na.any { it !in 0..255 } || nb.any { it !in 0..255 }) return false
    return na.take(3) == nb.take(3)
}
