package triad.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader

private const val PEER_GONE = "PEER_GONE"

/** The game's transport over the online relay: a plain TCP wire whose reader filters the
 *  relay's one control token (PEER_GONE) and turns it into a connection close, so the
 *  session's existing closed-wire handling applies (mid-match = forfeit to the survivor,
 *  pre-match = disconnected). Everything else flows through verbatim, exactly like TcpWire. */
class RelayWire(socket: Socket, scope: CoroutineScope) : Wire {
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
                    if (line == PEER_GONE) { close(); break }
                    if (line.isNotEmpty()) deliver(line)
                }
            } catch (_: IOException) {}
            close()
        }
    }

    private fun deliver(line: String) { onLine?.invoke(line) ?: pendingLines.addLast(line) }
    override fun send(line: String) { outbound.trySend(line) }
    override fun close() { outbound.close() }
}

/** Host-side relay handshake: dial out, HOST, and receive the room code. The code doubles
 *  as the session token, so it is returned alongside the wire. */
suspend fun hostRelay(relayHost: String, relayPort: Int, scope: CoroutineScope): Pair<Wire, String> {
    val socket = dialRelay(relayHost, relayPort)
    withContext(Dispatchers.IO) {
        val out = socket.getOutputStream()
        out.write("HOST\n".toByteArray()); out.flush()
    }
    val reply = readControlLine(socket)
    if (!reply.startsWith("CODE ")) throw IOException(makeErrMsg(reply))
    return Pair(RelayWire(socket, scope), reply.substring(5).trim())
}

/** Joiner-side relay handshake: dial out, JOIN with a forgiving code (case and separators
 *  are normalized — the canonical form is lowercase words joined by hyphens). */
suspend fun joinRelay(relayHost: String, relayPort: Int, code: String, scope: CoroutineScope): Wire {
    val canonical = canonicalRelayCode(code) ?: throw IOException("Codes look like 53DA92.")
    val socket = dialRelay(relayHost, relayPort)
    withContext(Dispatchers.IO) {
        val out = socket.getOutputStream()
        out.write(("JOIN $canonical\n").toByteArray()); out.flush()
    }
    val reply = readControlLine(socket)
    if (reply != "OK") throw IOException(makeErrMsg(reply))
    return RelayWire(socket, scope)
}

/** "53da91", "53 DA 91", "53-da-91" etc -> "53DA92" (2 digits, 2 letters, 2 digits,
 *  confusable-free alphabet: no I/O/L/0/1). Returns null for any other shape. */
fun canonicalRelayCode(input: String): String? {
    val compact = input.uppercase().filter { it.isLetterOrDigit() }
    return if (Regex("^[2-9]{2}[A-HJ-KM-NP-Z]{2}[2-9]{2}$").matches(compact)) compact else null
}

private fun makeErrMsg(reply: String) = when {
    reply.startsWith("ERR nohost") -> "No match with that code, check it and try again."
    reply.startsWith("ERR limit") -> "Too many attempts, try again in 15 minutes."
    else -> "Relay: $reply"
}

private suspend fun dialRelay(host: String, port: Int): Socket = withContext(Dispatchers.IO) {
    // TLS only, hostname-verified against system CAs; tries every resolved address
    // family (a domain may carry A and AAAA; cellular prefers v6)
    tryConnect(host, port, tls = true)
        ?: throw java.io.IOException("relay unreachable")
}

internal var TEST_UNTRUSTED_TLS = false   // tests run against self-signed certs only

private fun tryConnect(host: String, port: Int, tls: Boolean): Socket? {
    val addrs = java.net.InetAddress.getAllByName(host)
    for (a in addrs) {
        try {
            val raw = Socket().apply { connect(InetSocketAddress(a, port), 3_000) }
            if (!tls) return raw
            val factory: javax.net.ssl.SSLSocketFactory = if (TEST_UNTRUSTED_TLS) {
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }), java.security.SecureRandom())
                ctx.socketFactory
            } else {
                javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            }
            val ssl = factory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            if (!TEST_UNTRUSTED_TLS) {
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"   // verify cert against hostname
                }
            }
            ssl.startHandshake()
            return ssl
        } catch (_: Exception) { /* next address */ }
    }
    return null
}

private suspend fun readControlLine(socket: Socket): String = withContext(Dispatchers.IO) {
    // one byte at a time straight off the socket: a buffering wrapper could swallow
    // game-protocol bytes that arrive hot on the heels of the control line
    val r = socket.getInputStream()
    val sb = StringBuilder()
    while (true) {
        val c = r.read()
        if (c <= 0 || c == '\n'.code) break
        sb.append(c.toChar())
    }
    sb.toString()
}
