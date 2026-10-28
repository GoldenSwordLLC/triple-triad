package triad

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import triad.core.*
import triad.net.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ProtocolLoopbackTest {
    @Test fun `full match, both sessions agree`() = runBlocking {
        val repeats = System.getProperty("loopback.repeat")?.toInt() ?: 1
        repeat(repeats) { run ->
            if (repeats > 1) println("=== loopback iteration ${run + 1}/$repeats ===")
            oneMatch(run)
        }
    }

    private suspend fun CoroutineScope.oneMatch(iteration: Int) {
        val catalog = (1..10).map { i ->
            val r = if (i <= 5) 6 else 1
            CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
        }.associateBy { it.id }
        val hostDeck = (1..5).map { "c$it" }
        val joinDeck = (6..10).map { "c$it" }

        val server = ServerSocket(0)
        val hostSockAsync = async(Dispatchers.IO) { server.accept() }
        val joinSock = Socket("127.0.0.1", server.localPort)
        val hostSock = hostSockAsync.await()
        server.close()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = MatchSettings(trade = TradeRule.one)
        val host = P2pSession(TcpWire(hostSock, scope), isHost = true, myName = "Host", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal(hostDeck, emptyList(), ColorPref.blue),
            hostSettings = settings)
        val joiner = P2pSession(TcpWire(joinSock, scope), isHost = false, myName = "Joiner", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal(joinDeck, emptyList(), ColorPref.red),
            hostSettings = null)

        val failures = CopyOnWriteArrayList<SessionEvent>()
        class Side(val s: P2pSession, val tag: String) {
            var color = Owner.blue
            var sawTrade: List<CardTransfer> = emptyList()
            var lastEvent: SessionEvent? = null
        }
        fun maybePlay(side: Side, state: GameState) {
            if (!state.isOver && state.turn == side.color)
                side.s.play(0, state.board.cells.indices.first { state.board[it].card == null })
        }
        suspend fun drive(side: Side, done: CompletableDeferred<SessionEvent.Outcome>) {
            side.s.eventsFlow.collect { e ->
                side.lastEvent = e
                when (e) {
                is SessionEvent.RulesProposed -> side.s.acceptRules()
                is SessionEvent.VerifyDecks -> side.s.ready()
                is SessionEvent.MatchStarted -> { side.color = e.myColor; maybePlay(side, e.state) }
                is SessionEvent.MoveApplied -> maybePlay(side, e.state)
                is SessionEvent.RematchStarted -> maybePlay(side, e.state)
                is SessionEvent.GameOver -> {
                    val pick = (e.outcome as? SessionEvent.Outcome.Won)?.pendingPick
                    if (pick != null) side.s.tradePick(pick.candidates.take(pick.count).map { it.id })
                    if (pick == null && (e.outcome is SessionEvent.Outcome.Drawn || settings.trade == TradeRule.none))
                        side.s.teardown()
                    done.complete(e.outcome)
                }
                is SessionEvent.TradeResolved -> { side.sawTrade = e.transfers; side.s.teardown() }
                is SessionEvent.Forfeit, is SessionEvent.Declined,
                is SessionEvent.Disconnected, is SessionEvent.ProtocolError -> failures.add(e)
                else -> {}
            } }
        }

        val hostDone = CompletableDeferred<SessionEvent.Outcome>()
        val joinDone = CompletableDeferred<SessionEvent.Outcome>()
        val hostSide = Side(host, "HOST"); val joinerSide = Side(joiner, "JOIN")
        val c1 = launch { drive(hostSide, hostDone) }
        val c2 = launch { drive(joinerSide, joinDone) }
        host.start(); joiner.start()

        fun stall(e: Side) = e.lastEvent?.let { it::class.simpleName } ?: "nothing"
        val ho = try { withTimeout(30_000) { hostDone.await() } } catch (t: Throwable) {
            throw AssertionError(
                "loopback stalled: host last=[${stall(hostSide)}] joiner last=[${stall(joinerSide)}] failures=$failures", t)
        }
        val jo = try { withTimeout(30_000) { joinDone.await() } } catch (t: Throwable) {
            throw AssertionError(
                "loopback stalled after host outcome: host last=[${stall(hostSide)}] joiner last=[${stall(joinerSide)}] failures=$failures", t)
        }
        withTimeout(10_000) { c1.join(); c2.join() }

        assertTrue(failures.isEmpty(), "unexpected events: $failures")
        assertTrue(ho is SessionEvent.Outcome.Won && jo is SessionEvent.Outcome.Lost, "host=$ho joiner=$jo")
        assertEquals(1, hostSide.sawTrade.size, "trade=one → exactly 1 transfer")
        assertEquals(hostSide.sawTrade.map { it.card.id }.sorted(), joinerSide.sawTrade.map { it.card.id }.sorted(),
            "both devices must derive the SAME transfer")
    }

    @Test fun `friendly trade exchanges offered cards with no winner or loser`() = runBlocking {
        val catalog = (1..10).map { i ->
            val r = if (i <= 5) 6 else 1
            CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
        }.associateBy { it.id }
        val server = ServerSocket(0)
        val hostSockAsync = async(Dispatchers.IO) { server.accept() }
        val joinSock = Socket("127.0.0.1", server.localPort)
        val hostSock = hostSockAsync.await()
        server.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = P2pSession(TcpWire(hostSock, scope), isHost = true, myName = "Host", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.blue),
            hostSettings = MatchSettings(trade = TradeRule.none))
        val joiner = P2pSession(TcpWire(joinSock, scope), isHost = false, myName = "Joiner", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.red),
            hostSettings = null)

        class Side(val s: P2pSession, val tag: String) {
            var color = Owner.blue
            var gameOver = false
            var sawOffer: List<String> = emptyList()
            val traded = CompletableDeferred<List<CardTransfer>>()
        }
        val h = Side(host, "H"); val j = Side(joiner, "J")
        suspend fun drive(side: Side) {
            side.s.eventsFlow.collect { e ->
                when (e) {
                    is SessionEvent.RulesProposed -> side.s.acceptRules()
                    is SessionEvent.VerifyDecks -> side.s.ready()
                    is SessionEvent.MatchStarted -> {
                        side.color = e.myColor
                        val st = e.state
                        if (!st.isOver && st.turn == side.color)
                            side.s.play(0, st.board.cells.indices.first { st.board[it].card == null })
                    }
                    is SessionEvent.MoveApplied -> {
                        val st = e.state
                        if (!st.isOver && st.turn == side.color)
                            side.s.play(0, st.board.cells.indices.first { st.board[it].card == null })
                    }
                    is SessionEvent.GameOver -> side.gameOver = true
                    is SessionEvent.FriendlyOffered -> side.sawOffer = e.ids
                    is SessionEvent.TradeResolved -> side.traded.complete(e.transfers)
                    else -> {}
                }
            }
        }
        val c1 = launch { drive(h) }; val c2 = launch { drive(j) }
        host.start(); joiner.start()
        withTimeout(20_000) { while (!(h.gameOver && j.gameOver)) delay(50) }

        host.proposeFriendlyTrade(listOf("c1", "c2"))
        withTimeout(10_000) { while (j.sawOffer.isEmpty()) delay(50) }
        joiner.proposeFriendlyTrade(listOf("c8"))
        withTimeout(10_000) { while (h.sawOffer.isEmpty()) delay(50) }

        host.acceptFriendlyTrade()
        joiner.acceptFriendlyTrade()
        val ht = withTimeout(10_000) { h.traded.await() }
        val jt = withTimeout(10_000) { j.traded.await() }
        c1.cancel(); c2.cancel()

        fun ids(t: List<CardTransfer>, from: Owner, to: Owner) =
            t.filter { it.from == from && it.to == to }.map { it.card.id }.sorted()
        assertEquals(listOf("c1", "c2"), ids(ht, Owner.blue, Owner.red))
        assertEquals(listOf("c8"), ids(ht, Owner.red, Owner.blue))
        assertEquals(ids(ht, Owner.blue, Owner.red), ids(jt, Owner.blue, Owner.red), "both phones derive the SAME exchange")
        host.teardown(); joiner.teardown()
    }

    @Test fun `a mid-match disconnect forfeits to the remaining player`() = runBlocking {
        val catalog = (1..10).map { i ->
            CardDef("c$i", "C$i", 1, Element.none, 6, 6, 6, 6)
        }.associateBy { it.id }
        val server = ServerSocket(0)
        val hostSockAsync = async(Dispatchers.IO) { server.accept() }
        val joinSock = Socket("127.0.0.1", server.localPort)
        val hostSock = hostSockAsync.await()
        server.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = P2pSession(TcpWire(hostSock, scope), isHost = true, myName = "Host", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.blue),
            hostSettings = MatchSettings(trade = TradeRule.none))
        val joiner = P2pSession(TcpWire(joinSock, scope), isHost = false, myName = "Joiner", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.red),
            hostSettings = null)

        val hostStarted = CompletableDeferred<Owner>()
        val hostForfeit = CompletableDeferred<SessionEvent.Forfeit>()
        val c1 = launch { host.eventsFlow.collect { e ->
            when (e) {
                is SessionEvent.RulesProposed -> host.acceptRules()
                is SessionEvent.VerifyDecks -> host.ready()
                is SessionEvent.MatchStarted -> hostStarted.complete(e.myColor)
                is SessionEvent.Forfeit -> hostForfeit.complete(e)
                else -> {}
            } } }
        val c2 = launch { joiner.eventsFlow.collect { e ->
            when (e) {
                is SessionEvent.RulesProposed -> joiner.acceptRules()
                is SessionEvent.VerifyDecks -> joiner.ready()
                else -> {}
            } } }
        host.start(); joiner.start()
        val hostColor = withTimeout(15_000) { hostStarted.await() }

        joiner.teardown()   // the app-kill: wire dies with NO resign message
        val f = withTimeout(15_000) { hostForfeit.await() }
        c1.cancel(); c2.cancel()

        assertEquals(hostColor, f.winner, "the remaining player is awarded the win")
        assertTrue(f.reason.contains("disconnected"))
        host.teardown()
    }

    @Test fun `a duplicate deck declaration faults the session before any match`() = runBlocking {
        val catalog = (1..10).map { i -> CardDef("c$i", "C$i", 1, Element.none, 6, 6, 6, 6) }
            .associateBy { it.id }
        val server = ServerSocket(0)
        val hostSockAsync = async(Dispatchers.IO) { server.accept() }
        val joinSock = Socket("127.0.0.1", server.localPort)
        val hostSock = hostSockAsync.await()
        server.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = P2pSession(TcpWire(hostSock, scope), isHost = true, myName = "Host", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal(listOf("c1", "c2", "c3", "c4", "c5"), emptyList(), ColorPref.blue),
            hostSettings = MatchSettings(trade = TradeRule.one))

        val joiner = P2pSession(TcpWire(joinSock, scope), isHost = false, myName = "Joiner", token = "tok1",
            catalog = catalog, me = P2pSession.PlayerLocal(listOf("c6", "c6", "c7", "c8", "c9"), emptyList(), ColorPref.red),
            hostSettings = null)

        val hostEnded = CompletableDeferred<SessionEvent.ProtocolError>()
        val joinEnded = CompletableDeferred<SessionEvent.ProtocolError>()
        val c1 = launch { host.eventsFlow.collect { e ->
            if (e is SessionEvent.ProtocolError) hostEnded.complete(e)
            if (e is SessionEvent.RulesProposed) host.acceptRules()
        } }
        val c2 = launch { joiner.eventsFlow.collect { e ->
            if (e is SessionEvent.ProtocolError) joinEnded.complete(e)
            if (e is SessionEvent.RulesProposed) joiner.acceptRules()
            if (e is SessionEvent.VerifyDecks) joiner.ready()
        } }
        host.start(); joiner.start()

        val he = withTimeout(15_000) { hostEnded.await() }
        val je = withTimeout(15_000) { joinEnded.await() }
        c1.cancel(); c2.cancel()
        assertTrue(he.detail.contains("duplicate"), "host saw: ${he.code} ${he.detail}")
        assertTrue(je.detail.contains("duplicate"), "joiner saw: ${je.code} ${je.detail}")
        host.teardown(); joiner.teardown()
    }
}
