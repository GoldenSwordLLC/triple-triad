package triad.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Socket

interface Wire {
    var onLine: ((String) -> Unit)?
    var onClose: (() -> Unit)?
    fun send(line: String)
    fun close()
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
                val r = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotEmpty()) deliver(line)
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
