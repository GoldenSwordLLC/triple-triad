package triad.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import triad.core.CardDef
import triad.core.CardTransfer
import triad.core.Owner

sealed interface TradeEvent {
    data class Ready(val peerName: String, val peerCollection: Map<String, Int>) : TradeEvent
    data class PeerOffer(val ids: List<String>) : TradeEvent
    data class Done(val gained: List<CardDef>, val lost: List<CardDef>) : TradeEvent
    data class Fault(val code: String, val detail: String) : TradeEvent
    data object PeerGone : TradeEvent
}

/** Standalone card trading: two phones connect (LAN QR or relay code), declare their
 *  collections, freely offer cards, and swap on mutual accept — no match, no stakes.
 *  The trust model matches the rest of the app: offers are validated against the peer's
 *  DECLARED collection; your save only ever applies what they said they own. */
class TradeSession(
    private val wire: Wire,
    private val isHost: Boolean,
    private val myName: String,
    private val token: String,
    private val catalog: Map<String, CardDef>,
    private val myCollection: Map<String, Int>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val events = Channel<TradeEvent>(Channel.UNLIMITED)
    val eventsFlow = events.receiveAsFlow()

    private var peerCollection = emptyMap<String, Int>()
    private var myOffer = emptyList<String>()
    private var peerOffer = emptyList<String>()
    private var iAccepted = false
    private var peerAccepted = false
    private var concluded = false

    fun start() {
        wire.onLine = { line -> scope.launch { mutex.withLock { handle(line) } } }
        wire.onClose = { scope.launch { if (!concluded) events.send(TradeEvent.PeerGone) } }
        scope.launch { send(Msg.Hello(myName, token)) }
    }

    fun propose(ids: List<String>) = scope.launch { mutex.withLock {
        val mine = ids.groupingBy { it }.eachCount()
        if (mine.any { (id, n) -> (myCollection[id] ?: 0) < n }) return@launch
        // never trade yourself below a playable deck
        val kept = myCollection.entries.sumOf { (_, n) -> n } - ids.size
        if (kept < 5) { events.send(TradeEvent.Fault("floor", "that would leave you fewer than 5 cards")); return@launch }
        myOffer = ids
        iAccepted = false; peerAccepted = false
        send(Msg.FriendlyOffer(ids))
        maybeResolve()
    } }

    fun accept() = scope.launch { mutex.withLock {
        // accept only what you can SEE: an accept registered before the peer's offer is
        // processed locally would be voided by that offer's arrival and deadlock both sides
        if (myOffer.isEmpty() || peerOffer.isEmpty() || iAccepted) { println("ACC[$isHost] NO-OP"); return@launch }
        iAccepted = true
        send(Msg.FriendlyAccept)
        maybeResolve()
    } }

    // resolution binds LATE: both offers present + both accepts seen, whichever order
    // they arrived in (an accept can legitimately precede the peer's offer locally)
    private fun maybeResolve() {
        if (myOffer.isEmpty() || peerOffer.isEmpty() || !iAccepted || !peerAccepted) return
        if (concluded) return
        concluded = true
        scope.launch {
            val gained = peerOffer.mapNotNull { catalog[it] }
            val lost = myOffer.mapNotNull { catalog[it] }
            events.send(TradeEvent.Done(gained, lost))
        }
    }

    private suspend fun handle(line: String) {
        val msg = runCatching { wireJson.decodeFromString<Msg>(line) }.getOrElse {
            events.send(TradeEvent.Fault("json", line.take(80))); return
        }
        when (msg) {
            is Msg.Hello -> {
                if (msg.token != token) { events.send(TradeEvent.Fault("token", "wrong token")); return }
                send(Msg.TradeCollection(myCollection))
            }
            is Msg.TradeCollection -> {
                peerCollection = msg.counts.filterValues { it > 0 }
                events.send(TradeEvent.Ready(myNameOf(), peerCollection))
            }
            is Msg.FriendlyOffer -> {
                val counts = msg.ids.groupingBy { it }.eachCount()
                if (counts.any { (id, n) -> (peerCollection[id] ?: 0) < n }) {
                    events.send(TradeEvent.Fault("offer", "offered cards outside their collection")); return
                }
                peerOffer = msg.ids
                iAccepted = false; peerAccepted = false
                events.send(TradeEvent.PeerOffer(msg.ids))
                maybeResolve()
            }
            is Msg.FriendlyAccept -> {
                peerAccepted = true
                maybeResolve()
            }
            is Msg.Error -> events.send(TradeEvent.Fault(msg.code, msg.detail))
            else -> {}
        }
    }

    private fun myNameOf() = "Peer"
    private suspend fun send(m: Msg) { wire.send(wireJson.encodeToString(Msg.serializer(), m)) }
    fun teardown() { concluded = true; wire.close(); scope.cancel() }
}
