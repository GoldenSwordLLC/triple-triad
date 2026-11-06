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
        // server/relay.py is PRIVATE (gitignored) — a CI checkout never has it;
        // skip (not fail) exactly like the python3 check above
        val script = sequenceOf(File("server/relay.py"), File("../server/relay.py"))
            .firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("relay.py not present (private file)", script != null)
        val scriptFile = script!!
        val db = File.createTempFile("relay", ".db")
        val tc = File.createTempFile("tcert", ".pem"); val tk = File.createTempFile("tkey", ".pem")
        ProcessBuilder("openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", tk.absolutePath, "-out", tc.absolutePath,
            "-days", "1", "-nodes", "-subj", "/CN=localhost").start().waitFor()
        triad.net.TEST_UNTRUSTED_TLS = true
        // --min-version pins the gate (no GitHub lookups from tests); the clients
        // below must report exactly this version or the relay refuses them
        val testVer = "9.9.9"
        val proc = ProcessBuilder(
            "python3", scriptFile.absolutePath, "--port", "0", "--park", "60", "--db", db.absolutePath,
            "--min-version", testVer,
            "--tls-cert", tc.absolutePath, "--tls-key", tk.absolutePath
        ).redirectErrorStream(true).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var port = -1
            // read the banner on a helper thread: available() on the raw stream lies
            // once a BufferedReader has buffered ahead, and a blocking readLine on
            // the test thread could hang forever on a silent relay
            val portFuture = java.util.concurrent.CompletableFuture<Int>()
            Thread {
                runCatching {
                    val stdout = proc.inputStream.bufferedReader()
                    while (true) {
                        val line = stdout.readLine() ?: break
                        val m = Regex("TLS on [^ ]+:(\\d+)").find(line)
                        if (m != null) { portFuture.complete(m.groupValues[1].toInt()); break }
                    }
                }
            }.apply { isDaemon = true }.start()
            try {
                port = portFuture.get(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {}
            assertTrue(port > 0, "relay never announced its port")

            runBlocking {
                val catalog = (1..10).map { i ->
                    val r = if (i <= 5) 6 else 1
                    CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
                }.associateBy { it.id }

                val (hostWire, code) = hostRelay("127.0.0.1", port, scope, triad.net.JcaRelayIdentity(), testVer)
                assertTrue(Regex("^[2-9]{2}[A-HJ-KM-NP-Z]{2}[2-9]{2}$").matches(code), "code shape: $code")
                val host = P2pSession(hostWire, isHost = true, myName = "Host", token = code,
                    catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.any),
                    hostSettings = MatchSettings(trade = TradeRule.none))

                val sloppy = code.lowercase().chunked(2).joinToString(" ")
                val joinWire = joinRelay("127.0.0.1", port, sloppy, scope, testVer)
                val joiner = P2pSession(joinWire, isHost = false, myName = "Joiner", token = code,
                    catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.any),
                    hostSettings = null)

                // online screens use ColorPref.any on both sides, so colors come from
                // the per-game coin — every rematch re-coins them, and each side must
                // follow ITS OWN color from the events or it plays the wrong hand
                val hostOutcomes = java.util.concurrent.CopyOnWriteArrayList<SessionEvent.Outcome>()
                val joinOutcomes = java.util.concurrent.CopyOnWriteArrayList<SessionEvent.Outcome>()
                val gameFault = CompletableDeferred<String>()

                fun drive(s: P2pSession, into: java.util.concurrent.CopyOnWriteArrayList<SessionEvent.Outcome>) {
                    fun playFirst(st: GameState, mine: Owner) {
                        if (!st.isOver && st.turn == mine)
                            s.play(0, st.board.cells.indices.first { st.board[it].card == null })
                    }
                    scope.launch {
                        var mine = Owner.blue
                        s.eventsFlow.collect { e ->
                            when (e) {
                                is SessionEvent.RulesProposed -> s.acceptRules()
                                is SessionEvent.VerifyDecks -> s.ready()
                                is SessionEvent.MatchStarted -> { mine = e.myColor; playFirst(e.state, mine) }
                                is SessionEvent.RematchStarted -> { mine = e.myColor; playFirst(e.state, mine) }
                                is SessionEvent.MoveApplied -> playFirst(e.state, mine)
                                is SessionEvent.GameOver -> into.add(e.outcome)
                                is SessionEvent.ProtocolError -> gameFault.complete("${e.code} ${e.detail}")
                                is SessionEvent.Disconnected -> gameFault.complete("disconnected")
                                is SessionEvent.Forfeit -> gameFault.complete("forfeit")
                                else -> {}
                            } }
                    }
                }
                drive(host, hostOutcomes)
                drive(joiner, joinOutcomes)
                host.start(); joiner.start()

                suspend fun waitGames(n: Int) = withTimeoutOrNull(30_000) {
                    while ((hostOutcomes.size < n || joinOutcomes.size < n) && !gameFault.isCompleted) delay(100)
                }
                assertNotNull(waitGames(1), "GAME 1 FROZE against the real relay (30s, no outcome, no fault)")
                if (gameFault.isCompleted) fail("GAME 1 FAULT: ${gameFault.getCompleted()}")

                // the button press — game 2 must run to an agreed outcome through the
                // relay, with the freshly-coined colors
                host.rematch()
                assertNotNull(waitGames(2), "REMATCH FROZE against the real relay (30s)")
                if (gameFault.isCompleted) fail("REMATCH FAULT: ${gameFault.getCompleted()}")
                assertEquals(2, hostOutcomes.size)
                assertEquals(2, joinOutcomes.size)
                assertTrue(hostOutcomes.all { it is SessionEvent.Outcome.Won }, "host=${hostOutcomes}")
                assertTrue(joinOutcomes.all { it is SessionEvent.Outcome.Lost }, "joiner=${joinOutcomes}")

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
