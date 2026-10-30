package triad

import kotlinx.coroutines.*
import triad.core.*
import triad.net.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RealRelayTest {
    private fun pythonOk(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    @Test fun `full match against the real python relay`() {
        org.junit.Assume.assumeTrue("python3 not available", pythonOk())

        val script = sequenceOf(File("server/relay.py"), File("../server/relay.py"))
            .firstOrNull { it.exists() } ?: fail("relay.py not found")
        val db = File.createTempFile("relay", ".db")
        val proc = ProcessBuilder(
            "python3", script.absolutePath, "--port", "0", "--park", "60", "--db", db.absolutePath
        ).redirectErrorStream(true).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var port = -1
            val deadline = System.currentTimeMillis() + 15_000
            while (port < 0 && System.currentTimeMillis() < deadline) {
                if (proc.inputStream.available() == 0) { Thread.sleep(50); continue }
                val line = proc.inputStream.bufferedReader().readLine() ?: break
                val m = Regex("listening on [^ ]+:(\\d+)").find(line)
                if (m != null) port = m.groupValues[1].toInt()
            }
            assertTrue(port > 0, "relay never announced its port")

            runBlocking {
                val catalog = (1..10).map { i ->
                    val r = if (i <= 5) 6 else 1
                    CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
                }.associateBy { it.id }

                val (hostWire, code) = hostRelay("127.0.0.1", port, scope)
                assertTrue(Regex("^[2-9]{2}[A-HJ-KM-NP-Z]{2}[2-9]{2}$").matches(code), "code shape: $code")
                val host = P2pSession(hostWire, isHost = true, myName = "Host", token = code,
                    catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.blue),
                    hostSettings = MatchSettings(trade = TradeRule.none))

                val sloppy = code.lowercase().chunked(2).joinToString(" ")
                val joinWire = joinRelay("127.0.0.1", port, sloppy, scope)
                val joiner = P2pSession(joinWire, isHost = false, myName = "Joiner", token = code,
                    catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.red),
                    hostSettings = null)

                var hostColor = Owner.blue
                val hostOver = CompletableDeferred<SessionEvent.Outcome>()
                val joinOver = CompletableDeferred<SessionEvent.Outcome>()
                val gameFault = CompletableDeferred<String>()

                fun drive(s: P2pSession, done: CompletableDeferred<SessionEvent.Outcome>, isHostSide: Boolean) {
                    scope.launch {
                        s.eventsFlow.collect { e ->
                            when (e) {
                                is SessionEvent.RulesProposed -> s.acceptRules()
                                is SessionEvent.VerifyDecks -> s.ready()
                                is SessionEvent.MatchStarted -> {
                                    if (isHostSide) hostColor = e.myColor
                                    if (!e.state.isOver && e.state.turn == e.myColor)
                                        s.play(0, e.state.board.cells.indices.first { e.state.board[it].card == null })
                                }
                                is SessionEvent.MoveApplied -> {
                                    val mine = if (isHostSide) hostColor else hostColor.other
                                    if (!e.state.isOver && e.state.turn == mine)
                                        s.play(0, e.state.board.cells.indices.first { e.state.board[it].card == null })
                                }
                                is SessionEvent.GameOver -> done.complete(e.outcome)
                                is SessionEvent.ProtocolError -> gameFault.complete("${e.code} ${e.detail}")
                                is SessionEvent.Disconnected -> gameFault.complete("disconnected")
                                is SessionEvent.Forfeit -> gameFault.complete("forfeit")
                                else -> {}
                            } }
                    }
                }
                drive(host, hostOver, true)
                drive(joiner, joinOver, false)
                host.start(); joiner.start()

                val outcome = withTimeoutOrNull(30_000) {
                    while (!hostOver.isCompleted && !joinOver.isCompleted && !gameFault.isCompleted) delay(100)
                    val h = if (hostOver.isCompleted) hostOver.await() else null
                    val j = if (joinOver.isCompleted) joinOver.await() else null
                    when {
                        gameFault.isCompleted -> "FAULT: ${gameFault.await()}"
                        else -> "DONE host=${h?.javaClass?.simpleName} joiner=${j?.javaClass?.simpleName}"
                    }
                }
                assertNotNull(outcome, "GAME FROZE mid-match against the real relay (30s, no outcome, no fault)")
                println("OUTCOME: $outcome")
                assertTrue(!outcome.startsWith("FAULT"), outcome)

                val hostClosed = CountDownLatch(1)
                hostWire.onClose = { hostClosed.countDown() }
                joinWire.close()
                assertTrue(hostClosed.await(10, TimeUnit.SECONDS), "host wire never learned the peer was gone")
            }
            Unit
        } finally {
            scope.cancel()
            proc.destroy()
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
            proc.destroyForcibly()
            db.delete()
        }
    }
}
