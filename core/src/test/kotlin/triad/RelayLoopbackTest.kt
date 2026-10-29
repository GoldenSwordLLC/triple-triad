package triad

import kotlinx.coroutines.*
import triad.core.*
import triad.net.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/** A minimal in-JVM relay implementing the same wire contract as server/relay.py, so the
 *  full online path (hostRelay/joinRelay handshake -> spliced game protocol) is testable
 *  without Python. Mirrors: HOST/CODE, JOIN/OK/ERR, byte splice, PEER_GONE to the survivor. */
private class StubRelay {
    private val hosts = ConcurrentHashMap<String, Socket>()
    private val paired = ConcurrentHashMap<String, Socket>()   // joiner socket per code
    private val early = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile var server: ServerSocket? = null
    val port: Int get() = server!!.localPort

    fun start(scope: CoroutineScope) {
        val s = ServerSocket(0)
        server = s
        scope.launch(Dispatchers.IO) {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                Thread { handle(sock) }.start()
            }
        }
    }

    private fun handle(sock: Socket) {
        val r = BufferedReader(InputStreamReader(sock.getInputStream()))
        val first = r.readLine() ?: return sock.close()
        when (first.trim().uppercase().substringBefore(" ")) {
            "HOST" -> {
                val code = "53DA92"
                hosts[code] = sock
                early[code] = mutableListOf()
                sock.getOutputStream().write("CODE $code\n".toByteArray())
                sock.getOutputStream().flush()
                val joiner = park(sock, r, code) ?: return
                sock.soTimeout = 0
                early.remove(code)?.forEach { line ->
                    joiner.getOutputStream().write((line + "\n").toByteArray())
                }
                joiner.getOutputStream().flush()
                splice(sock, joiner)
                hosts.remove(code)
            }
            "JOIN" -> {
                val wanted = canonicalRelayCode(first.substringAfter("JOIN"))
                val host = wanted?.let { hosts[it] }
                if (host == null) {
                    sock.getOutputStream().write("ERR nohost\n".toByteArray())
                    sock.getOutputStream().flush(); sock.close(); return
                }
                if (wanted != null) paired[wanted] = sock
                sock.getOutputStream().write("OK\n".toByteArray())
                sock.getOutputStream().flush()
                // the HOST side owns the splice; wait for it to finish before closing here
                val deadline = System.currentTimeMillis() + 30_000
                while (hosts.containsKey(wanted) && System.currentTimeMillis() < deadline) Thread.sleep(50)
            }
            else -> {
                sock.getOutputStream().write("ERR protocol\n".toByteArray())
                sock.getOutputStream().flush(); sock.close()
            }
        }
    }

    private fun park(sock: Socket, r: BufferedReader, code: String): Socket? {
        sock.soTimeout = 250
        try {
            while (hosts.containsKey(code)) {
                paired[code]?.let { return it }
                try {
                    val line = r.readLine() ?: return null
                    early[code]?.add(line)
                } catch (_: java.net.SocketTimeoutException) {}
            }
        } catch (_: Exception) { return null } finally { sock.soTimeout = 0 }
        return null
    }

    private fun splice(a: Socket, b: Socket) {
        Thread {
            pump(a, b)
            try { b.getOutputStream().write("PEER_GONE\n".toByteArray()); b.getOutputStream().flush() } catch (_: Exception) {}
            runCatching { a.close() }; runCatching { b.close() }
        }.start()
        Thread {
            pump(b, a)
            try { a.getOutputStream().write("PEER_GONE\n".toByteArray()); a.getOutputStream().flush() } catch (_: Exception) {}
            runCatching { a.close() }; runCatching { b.close() }
        }.start()
    }

    private fun pump(src: Socket, dst: Socket) {
        try {
            val r = BufferedReader(InputStreamReader(src.getInputStream()))
            while (true) {
                val line = r.readLine() ?: break
                dst.getOutputStream().write((line + "\n").toByteArray())
                dst.getOutputStream().flush()
            }
        } catch (_: Exception) {}
    }
}

class RelayLoopbackTest {
    @Test fun `a full match plays over the relay path with the code as the token`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relay = StubRelay()
        relay.start(scope)
        delay(200)
        val realPort = relay.port

        val catalog = (1..10).map { i ->
            val r = if (i <= 5) 6 else 1
            CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
        }.associateBy { it.id }

        val (hostWire, code) = hostRelay("127.0.0.1", relay.port, scope)
        assertEquals("53DA92", code)
        val host = P2pSession(hostWire, isHost = true, myName = "Host", token = code,
            catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.blue),
            hostSettings = MatchSettings(trade = TradeRule.one))

        val joinWire = joinRelay("127.0.0.1", relay.port, "53 da 92", scope)
        val joiner = P2pSession(joinWire, isHost = false, myName = "Joiner", token = code,
            catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.red),
            hostSettings = null)

        var hostColor = Owner.blue
        val hostDone = CompletableDeferred<SessionEvent.Outcome>()
        val joinDone = CompletableDeferred<SessionEvent.Outcome>()
        fun CoroutineScope.drive(s: P2pSession, done: CompletableDeferred<SessionEvent.Outcome>) = launch {
            s.eventsFlow.collect { e ->
                when (e) {
                    is SessionEvent.RulesProposed -> s.acceptRules()
                    is SessionEvent.VerifyDecks -> s.ready()
                    is SessionEvent.MatchStarted -> {
                        if (s === host) hostColor = e.myColor
                        if (!e.state.isOver && e.state.turn == e.myColor)
                            s.play(0, e.state.board.cells.indices.first { e.state.board[it].card == null })
                    }
                    is SessionEvent.MoveApplied -> {
                        if (!e.state.isOver && e.state.turn == (if (s === host) hostColor else hostColor.other))
                            s.play(0, e.state.board.cells.indices.first { e.state.board[it].card == null })
                    }
                    is SessionEvent.GameOver -> {
                        val pick = (e.outcome as? SessionEvent.Outcome.Won)?.pendingPick
                        if (pick != null) s.tradePick(pick.candidates.take(pick.count).map { it.id })
                        done.complete(e.outcome)
                    }
                    is SessionEvent.TradeResolved -> s.teardown()
                    else -> {}
                } }
        }
        drive(host, hostDone); drive(joiner, joinDone)
        host.start(); joiner.start()
        val ho = withTimeout(30_000) { hostDone.await() }
        val jo = withTimeout(30_000) { joinDone.await() }
        assertTrue(ho is SessionEvent.Outcome.Won && jo is SessionEvent.Outcome.Lost, "host=$ho joiner=$jo")
        relay.server?.close()
        Unit
    }

    @Test fun `a wrong code is refused and canonicalization rejects junk`() {
        assertEquals("53DA92", canonicalRelayCode("53da92"))
        assertEquals("53DA92", canonicalRelayCode("53 DA 92"))
        assertEquals("53DA92", canonicalRelayCode("53-da-92"))
        assertNull(canonicalRelayCode("53DA9"))          // too short
        assertNull(canonicalRelayCode("53DA921"))        // too long
        assertNull(canonicalRelayCode("53IA91"))         // I excluded
        assertNull(canonicalRelayCode("53LA91"))         // L excluded
        assertNull(canonicalRelayCode("53OA91"))         // O excluded
        assertNull(canonicalRelayCode("50DA91"))         // 0 excluded
        assertNull(canonicalRelayCode("51DA91"))         // 1 excluded
        assertNull(canonicalRelayCode("DA5391"))         // wrong block order
        assertNull(canonicalRelayCode("leviathan fira balamb"))
        assertNull(canonicalRelayCode(""))
    }
}
