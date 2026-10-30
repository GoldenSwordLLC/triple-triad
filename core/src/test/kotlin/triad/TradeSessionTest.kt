package triad

import kotlinx.coroutines.*
import triad.core.*
import triad.net.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*

class TradeSessionTest {
    private fun pair(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope): Pair<java.net.Socket, java.net.Socket> =
        runBlocking {
            val server = ServerSocket(0)
            val a = async(Dispatchers.IO) { server.accept() }
            val b = Socket("127.0.0.1", server.localPort)
            val accepted = a.await()          // accept FIRST, close after — closing early
            server.close()                    // kills a queued accept and hangs the await
            accepted to b
        }

    @Test fun `standalone trade exchanges collection cards on mutual accept`() {
        val catalog = (1..10).map { CardDef("c$it", "C$it", 1, Element.none, 5, 5, 5, 5) }
            .associateBy { it.id }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = pair(scope)
        val a = TradeSession(TcpWire(sockets.first, scope), isHost = true, myName = "A", token = "TK",
            catalog = catalog, myCollection = mapOf("c1" to 3, "c2" to 1))
        val b = TradeSession(TcpWire(sockets.second, scope), isHost = false, myName = "B", token = "TK",
            catalog = catalog, myCollection = mapOf("c5" to 2, "c9" to 1))
        try {
            runBlocking {
                val aReady = CompletableDeferred<Map<String, Int>>()
                val aDone = CompletableDeferred<TradeEvent.Done>()
                val bDone = CompletableDeferred<TradeEvent.Done>()
                val bPeerOffer = CompletableDeferred<List<String>>()
                collectors.launch { a.eventsFlow.collect { e ->
                    when (e) {
                        is TradeEvent.Ready -> aReady.complete(e.peerCollection)
                        is TradeEvent.Done -> aDone.complete(e)
                        is TradeEvent.Fault -> fail("fault ${e.code} ${e.detail}")
                        TradeEvent.PeerGone -> fail("peer gone")
                        else -> {}
                    } } }
                collectors.launch { b.eventsFlow.collect { e ->
                    when (e) {
                        is TradeEvent.PeerOffer -> bPeerOffer.complete(e.ids)
                        is TradeEvent.Done -> bDone.complete(e)
                        is TradeEvent.Fault -> fail("fault ${e.code} ${e.detail}")
                        TradeEvent.PeerGone -> fail("peer gone")
                        else -> {}
                    } } }
                a.start(); b.start()

                val bCollection = withTimeout(10_000) { aReady.await() }
                assertEquals(mapOf("c5" to 2, "c9" to 1), bCollection)

                a.propose(listOf("c1", "c1", "c2"))
                withTimeout(10_000) { bPeerOffer.await() }
                b.propose(listOf("c9"))

                b.accept()                     // adversarial order: B before A
                delay(300)
                a.accept()

                val aRes = withTimeout(10_000) { aDone.await() }
                val bRes = withTimeout(10_000) { bDone.await() }
                assertEquals(listOf("c9"), aRes.gained.map { it.id })
                assertEquals(listOf("c1", "c1", "c2"), aRes.lost.map { it.id }.sorted())
                assertEquals(aRes.lost.map { it.id }.sorted(), bRes.gained.map { it.id }.sorted(),
                    "both phones derive the IDENTICAL exchange")
            }
        } finally {
            a.teardown(); b.teardown(); collectors.cancel(); scope.cancel()
        }
    }

    @Test fun `over-offering own cards never reaches the peer`() {
        val catalog = (1..4).map { CardDef("c$it", "C$it", 1, Element.none, 5, 5, 5, 5) }
            .associateBy { it.id }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = pair(scope)
        val a = TradeSession(TcpWire(sockets.first, scope), isHost = true, myName = "A", token = "TK",
            catalog = catalog, myCollection = mapOf("c1" to 1))
        val b = TradeSession(TcpWire(sockets.second, scope), isHost = false, myName = "B", token = "TK",
            catalog = catalog, myCollection = mapOf("c2" to 1))
        try {
            runBlocking {
                val bReady = CompletableDeferred<Boolean>()
                val strayOffer = CompletableDeferred<List<String>>()
                collectors.launch { b.eventsFlow.collect { e ->
                    when (e) {
                        is TradeEvent.Ready -> bReady.complete(true)
                        is TradeEvent.PeerOffer -> strayOffer.complete(e.ids)
                        else -> {}
                    } } }
                a.start(); b.start()
                withTimeout(10_000) { bReady.await() }

                a.propose(listOf("c1", "c1", "c1"))   // owns ONE copy: local guard drops it
                delay(1_000)
                assertFalse(strayOffer.isCompleted, "over-offer leaked to the peer")

                a.propose(listOf("c1"))
                assertEquals(listOf("c1"), withTimeout(10_000) { strayOffer.await() })
            }
        } finally {
            a.teardown(); b.teardown(); collectors.cancel(); scope.cancel()
        }
    }

    @Test fun `a wrong token faults the session`() {
        val catalog = (1..4).map { CardDef("c$it", "C$it", 1, Element.none, 5, 5, 5, 5) }
            .associateBy { it.id }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = pair(scope)
        val a = TradeSession(TcpWire(sockets.first, scope), isHost = true, myName = "A", token = "TK",
            catalog = catalog, myCollection = mapOf("c1" to 1))
        val b = TradeSession(TcpWire(sockets.second, scope), isHost = false, myName = "B", token = "WRONG",
            catalog = catalog, myCollection = mapOf("c2" to 1))
        try {
            runBlocking {
                val fault = CompletableDeferred<TradeEvent.Fault>()
                collectors.launch { a.eventsFlow.collect { e ->
                    if (e is TradeEvent.Fault) fault.complete(e) } }
                a.start(); b.start()
                assertEquals("token", withTimeout(10_000) { fault.await() }.code)
            }
        } finally {
            a.teardown(); b.teardown(); collectors.cancel(); scope.cancel()
        }
    }
}
