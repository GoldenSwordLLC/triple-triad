package triad

import kotlinx.coroutines.*
import triad.core.*
import triad.net.*
import kotlin.test.*

class LiveRelayProbe {
    @Test fun `prematch handshake against the production relay`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Boolean>()
            val fault = CompletableDeferred<String?>()

            val catalog = (1..10).map { i ->
                val r = if (i <= 5) 6 else 1
                CardDef("c$i", "C$i", 1, Element.none, r, r, r, r)
            }.associateBy { it.id }

            val (hw, code) = hostRelay("triad.goldenswordllc.com", 44000, scope)
            println("hosted, code $code")
            val hs = P2pSession(hw, isHost = true, myName = "Host", token = code,
                catalog = catalog, me = P2pSession.PlayerLocal((1..5).map { "c$it" }, emptyList(), ColorPref.blue),
                hostSettings = MatchSettings(trade = TradeRule.none))
            scope.launch { hs.eventsFlow.collect { e ->
                when (e) {
                    is SessionEvent.RulesProposed -> hs.acceptRules()
                    is SessionEvent.Connected -> println("HOST: connected")
                    is SessionEvent.VerifyDecks -> { println("HOST: verify"); hs.ready() }
                    is SessionEvent.MatchStarted -> { println("HOST: match started"); started.complete(true) }
                    is SessionEvent.ProtocolError -> fault.complete("host: ${e.code} ${e.detail}")
                    is SessionEvent.Disconnected -> fault.complete("host: disconnected")
                    else -> {}
                } } }
            hs.start()

            val jw = joinRelay("triad.goldenswordllc.com", 44000, code.lowercase(), scope)
            val js = P2pSession(jw, isHost = false, myName = "Joiner", token = code,
                catalog = catalog, me = P2pSession.PlayerLocal((6..10).map { "c$it" }, emptyList(), ColorPref.red),
                hostSettings = null)
            scope.launch { js.eventsFlow.collect { e ->
                when (e) {
                    is SessionEvent.RulesProposed -> { println("JOINER: rules"); js.acceptRules() }
                    is SessionEvent.Connected -> println("JOINER: connected")
                    is SessionEvent.VerifyDecks -> { println("JOINER: verify"); js.ready() }
                    is SessionEvent.MatchStarted -> { println("JOINER: match started"); started.complete(true) }
                    is SessionEvent.ProtocolError -> fault.complete("joiner: ${e.code} ${e.detail}")
                    is SessionEvent.Disconnected -> fault.complete("joiner: disconnected")
                    else -> {}
                } } }
            js.start()

            val result = withTimeoutOrNull(20_000) { selectFirst(started, fault) }
            when (result) {
                null -> fail("HANDSHAKE STUCK against live relay (20s, no start, no error)")
                is String -> fail("handshake faulted: $result")
                else -> println("MATCH STARTED against the live relay — client stack is fine")
            }
            scope.cancel()
        }
        Unit
    }

    private suspend fun selectFirst(a: CompletableDeferred<Boolean>, b: CompletableDeferred<String?>): Any? {
        while (true) {
            if (a.isCompleted) return a.await()
            if (b.isCompleted) return b.await()
            delay(100)
        }
    }
}
