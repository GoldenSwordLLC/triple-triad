package triad.net

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlin.math.abs
import triad.core.*

class DeckViolation(val cardId: String) : Exception()

class DeckReferee(private val declared: Map<String, Int>) {
    private val played = mutableMapOf<String, Int>()
    fun checkAndConsume(id: String) {
        if ((declared[id] ?: 0) - (played[id] ?: 0) <= 0) throw DeckViolation(id)
        played.merge(id, 1, Int::plus)
    }
}

fun resolveColors(hostPref: ColorPref, joinerPref: ColorPref, coin: Int): Pair<Owner, Owner> {
    fun concrete(p: ColorPref) = if (p == ColorPref.blue) Owner.blue else Owner.red
    fun contested() = if (coin == 0) Owner.blue to Owner.red else Owner.red to Owner.blue
    return when {
        hostPref == ColorPref.any && joinerPref == ColorPref.any -> contested()
        hostPref == ColorPref.any -> concrete(joinerPref).other to concrete(joinerPref)
        joinerPref == ColorPref.any -> concrete(hostPref) to concrete(hostPref).other
        hostPref == joinerPref -> contested()
        else -> concrete(hostPref) to concrete(joinerPref)
    }
}

sealed interface SessionEvent {
    data class Connected(val peerName: String) : SessionEvent
    data class RulesProposed(val settings: MatchSettings) : SessionEvent
    data object PeerAccepted : SessionEvent
    data class VerifyDecks(val view: DeckVerificationView) : SessionEvent
    data class MatchStarted(val state: GameState, val myColor: Owner) : SessionEvent
    data class MoveApplied(val flips: List<Flip>, val state: GameState) : SessionEvent
    data class RematchStarted(val state: GameState) : SessionEvent
    sealed interface Outcome {
        data class Won(val transfers: List<CardTransfer>, val pendingPick: PendingPick?) : Outcome
        data object Lost : Outcome
        data object Drawn : Outcome
    }
    data class GameOver(val outcome: Outcome) : SessionEvent
    data class TradeResolved(val transfers: List<CardTransfer>) : SessionEvent
    data class Forfeit(val winner: Owner, val reason: String) : SessionEvent
    data class Declined(val detail: String) : SessionEvent
    data class Disconnected(val detail: String) : SessionEvent
    data class ProtocolError(val code: String, val detail: String) : SessionEvent
}

data class DeckVerificationView(
    val peerName: String, val myDeck: List<CardDef>, val peerDeck: List<CardDef>,
    val bannedByMe: List<CardDef>, val bannedByThem: List<CardDef>,
    val myColor: Owner, val randomHands: Boolean)

class P2pSession(
    private val wire: Wire,
    val isHost: Boolean,
    private val myName: String,
    private val token: String,
    private val catalog: Map<String, CardDef>,
    me: PlayerLocal,
    private val hostSettings: MatchSettings?,
) {
    data class PlayerLocal(val deckIds: List<String>, val neverFace: List<String>, val pref: ColorPref)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<SessionEvent>(Channel.UNLIMITED)
    val eventsFlow = events.receiveAsFlow()
    private val inbound = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    private var me = me
    private var deckSent = false

    private val myNonce = randomHex(16)
    private var peerCommit: String? = null
    private var revealed = false
    private var seedHex = ""
    private var seed = 0L
    var peerName: String = "?"; private set
    private var peerPub: String = ""
    val deckSize get() = me.deckIds.size
    private var settings: MatchSettings? = null
    private var peerDeck: Msg.Deck? = null

    private var state: GameState? = null
    private var rematchCount = 0
    private var myColor = Owner.blue
    private var seq = 0
    private var referee: DeckReferee? = null
    private var broughtBlue: List<CardDef> = emptyList()
    private var broughtRed: List<CardDef> = emptyList()
    private var pendingPick: PendingPick? = null
    @Volatile private var concluded = false

    companion object { private const val CLOSED = "\u0000__wire_closed__" }
    private var myAckSent = false; private var peerAcked = false
    private var done = false

    init {
        require(me.deckIds.size in 5..10) { "deck must be 5–10 cards" }
        me.deckIds.forEach { require(catalog.containsKey(it)) { "unknown card in deck: $it" } }
    }

    fun start() {
        wire.onLine = { inbound.trySend(it) }

        wire.onClose = { inbound.trySend(CLOSED) }

        send(Msg.Hello(myName, token))
        send(Msg.Commit(sha256Hex(myNonce)))
        scope.launch {
            for (line in inbound) {
                if (line == CLOSED) {
                    if (!concluded) {
                        if (state != null) terminate(SessionEvent.Forfeit(myColor, "Opponent disconnected"))
                        else terminate(SessionEvent.Disconnected("connection lost"))
                    }
                    return@launch
                }
                val msg = runCatching { wireJson.decodeFromString<Msg>(line) }.getOrNull()
                if (msg == null) { terminate(SessionEvent.ProtocolError("json", line.take(80))); return@launch }
                try { mutex.withLock { handle(msg) } }
                catch (f: SessionFault) { send(Msg.Error(f.code, f.detail)); terminate(SessionEvent.ProtocolError(f.code, f.detail)); return@launch }
                catch (e: Exception) { terminate(SessionEvent.ProtocolError("internal", e.message ?: "")); return@launch }
            }
        }
    }

    private suspend fun handle(m: Msg) { when (m) {
        is Msg.Hello -> {
            if (m.proto != 1) fault("proto", "version ${m.proto}")
            if (m.token != token) fault("token", "token mismatch")
            peerName = m.name
            events.send(SessionEvent.Connected(peerName))
        }
        is Msg.Commit -> { peerCommit = m.hash; send(Msg.Reveal(myNonce)) }
        is Msg.Reveal -> {
            if (sha256Hex(m.nonce) != peerCommit) fault("commit", "reveal ≠ commit")
            if (revealed) fault("protocol", "double reveal")
            revealed = true
            seedHex = sha256Hex(if (isHost) myNonce + m.nonce else m.nonce + myNonce)
            seed = seedFromHex(seedHex)
            if (isHost) { val hs = hostSettings ?: fault("host", "settings missing"); settings = hs; send(Msg.Settings(hs)) }
        }
        is Msg.Settings -> {
            if (isHost) fault("protocol", "settings from joiner")
            if (!m.settings.randomHands && me.deckIds.size != 5)
                fault("deck", "host requires 5-card decks; yours is ${me.deckIds.size}")
            settings = m.settings
            events.send(SessionEvent.RulesProposed(m.settings))
        }
        is Msg.Accept -> {
            if (!isHost) fault("protocol", "accept from joiner")
            events.send(SessionEvent.PeerAccepted)
            deckSent = true
            send(Msg.Deck(me.deckIds, me.neverFace, me.pref))
        }
        is Msg.Decline -> terminate(SessionEvent.Declined("Opponent declined"))
        is Msg.Deck -> {
            if (settings == null) fault("protocol", "deck before settings")
            if (peerDeck != null) fault("protocol", "duplicate deck")
            validatePeerDeck(m)
            peerDeck = m
            events.send(SessionEvent.VerifyDecks(buildVerification()))
        }
        is Msg.DeckAck -> { peerAcked = true; maybeBegin() }
        is Msg.Move -> onPeerMove(m)
        is Msg.TradePick -> onTradePick(m)
        is Msg.Rematch -> mutex.withLock {
            val s = state
            if (s != null && s.isOver && !concluded) {
                concluded = false
                startRematch()
            }
        }
        is Msg.TradeCollection -> fault("protocol", "trade message in a match session")
        is Msg.FriendlyOffer -> fault("protocol", "trade message in a match session")
        is Msg.FriendlyAccept -> fault("protocol", "trade message in a match session")
        is Msg.Resign -> terminate(SessionEvent.Forfeit(myColor, "Opponent resigned"))
        is Msg.Error -> terminate(SessionEvent.ProtocolError(m.code, m.detail))
    } }

    private fun validatePeerDeck(m: Msg.Deck) {
        val s = settings!!
        val ok = if (s.randomHands) m.ids.size in 5..10 else m.ids.size == 5
        if (!ok) fault("deck", "size ${m.ids.size}")
        m.ids.forEach { if (it !in catalog) fault("deck", "unknown card $it") }

        val dupe = m.ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()
        if (dupe != null) fault("deck", "duplicate card $dupe")
        m.bans.forEach { if (it !in catalog) fault("deck", "unknown ban $it") }
    }

    private suspend fun maybeBegin() {
        if (!myAckSent || !peerAcked || state != null) return
        state = buildMatch()
        referee = DeckReferee(peerDeck!!.ids.groupingBy { it }.eachCount())
        events.send(SessionEvent.MatchStarted(state!!, myColor))
    }

    private fun buildMatch(): GameState {
        val r = SplitMix64(seed)
        val coin = r.nextInt(2)
        val hostPref = if (isHost) me.pref else peerDeck!!.pref
        val joinerPref = if (isHost) peerDeck!!.pref else me.pref
        val (hostColor, joinerColor) = resolveColors(hostPref, joinerPref, coin)
        myColor = if (isHost) hostColor else joinerColor
        val s = settings!!
        val cells = List(9) {
            if (s.rules.elemental && r.nextInt(5) == 0) Cell(element = Element.entries[r.nextInt(9) + 1]) else Cell()
        }
        val hostPool = if (isHost) me.deckIds else peerDeck!!.ids
        val joinerPool = if (isHost) peerDeck!!.ids else me.deckIds
        fun sample(pool: List<String>) = if (s.randomHands && pool.size > 5) r.sample5(pool) else pool.take(5)
        val blueHand = sample(if (hostColor == Owner.blue) hostPool else joinerPool).map { catalog.getValue(it) }
        val redHand  = sample(if (hostColor == Owner.red)  hostPool else joinerPool).map { catalog.getValue(it) }
        val first = if (r.nextInt(2) == 0) Owner.blue else Owner.red
        broughtBlue = blueHand; broughtRed = redHand
        return GameState(board = Board(cells), blueHand = blueHand, redHand = redHand, turn = first, rules = s.rules)
    }

    private fun buildVerification() = DeckVerificationView(
        peerName = peerName,
        myDeck = me.deckIds.map { catalog.getValue(it) },
        peerDeck = peerDeck!!.ids.map { catalog.getValue(it) },
        bannedByMe = peerDeck!!.ids.map { catalog.getValue(it) }.filter { it.id in me.neverFace },
        bannedByThem = me.deckIds.map { catalog.getValue(it) }.filter { it.id in peerDeck!!.bans },
        myColor = resolveColors(
            if (isHost) me.pref else peerDeck!!.pref,
            if (isHost) peerDeck!!.pref else me.pref,
            SplitMix64(seed).nextInt(2)).let { (h, j) -> if (isHost) h else j },
        randomHands = settings!!.randomHands)

    fun acceptRules() = scope.launch { mutex.withLock {
        send(Msg.Accept); deckSent = true
        send(Msg.Deck(me.deckIds, me.neverFace, me.pref))
    } }
    fun switchDeck(ids: List<String>) { check(!deckSent); me = me.copy(deckIds = ids) }
    fun ready() = scope.launch { mutex.withLock { send(Msg.DeckAck); myAckSent = true; maybeBegin() } }
    fun decline() = scope.launch { send(Msg.Decline); terminate(SessionEvent.Declined("You declined")) }
    fun rematch() = scope.launch { mutex.withLock {
        val s = state ?: return@launch
        if (!s.isOver || concluded) return@launch
        concluded = false
        startRematch()
        send(Msg.Rematch)
    } }

    private suspend fun startRematch() {
        rematchCount++
        // fresh shared seed derived from the original handshake nonces so both phones
        // rebuild the identical new game without another commit/reveal round
        seed = seedFromHex(sha256Hex(seedHex + "#rematch#" + rematchCount))
        state = buildMatch()
        referee = DeckReferee(peerDeck!!.ids.groupingBy { it }.eachCount())
        events.send(SessionEvent.RematchStarted(state!!))
    }

    fun resign() = scope.launch { send(Msg.Resign); terminate(SessionEvent.Forfeit(myColor.other, "You resigned")) }

    fun play(handIndex: Int, cell: Int) = scope.launch { mutex.withLock {
        val s = state ?: return@launch
        if (s.isOver || s.turn != myColor || cell !in 0..8 || s.board[cell].card != null) return@launch
        if (handIndex !in s.hand(myColor).indices) return@launch
        val card = s.hand(myColor)[handIndex]
        val res = Engine.place(s, handIndex, cell)
        state = res.state; seq++
        send(Msg.Move(handIndex, cell, card.id, seq, canonical(res.state, seq)))
        events.send(SessionEvent.MoveApplied(res.flips, res.state))
        if (res.state.isOver) resolveEnd()
    } }

    private suspend fun onPeerMove(m: Msg.Move) {
        val s = state ?: fault("protocol", "move before match")
        if (m.seq != seq + 1) fault("seq", "expected ${seq + 1}, got ${m.seq}")
        if (s.isOver) fault("protocol", "move after game over")
        if (s.turn != myColor.other) fault("turn", "move out of turn")
        if (m.cell !in 0..8 || s.board[m.cell].card != null) fault("cell", "cell ${m.cell}")
        if (m.handIndex !in s.hand(myColor.other).indices) fault("hand", "index ${m.handIndex}")
        try { referee!!.checkAndConsume(m.card) }
        catch (e: DeckViolation) {
            send(Msg.Error("deck_violation", e.cardId))
            terminate(SessionEvent.Forfeit(myColor, "Opponent played an undeclared card: ${e.cardId}"))
            return
        }
        if (s.hand(myColor.other)[m.handIndex].id != m.card)
            fault("hand", "card outside declared hand")
        val res = Engine.place(s, m.handIndex, m.cell)
        seq++
        if (canonical(res.state, seq) != m.hash) fault("desync", "engines disagree")
        state = res.state
        events.send(SessionEvent.MoveApplied(res.flips, res.state))
        if (res.state.isOver) resolveEnd()
    }

    private suspend fun resolveEnd() {
        val s = state!!; val st = settings!!
        val blue = s.score(Owner.blue); val red = s.score(Owner.red)

        val winner = if (blue > red) Owner.blue else Owner.red
        run {
            concluded = true
            val iWon = winner == myColor
                val loserBrought = if (winner == Owner.blue) broughtRed else broughtBlue
                when (st.trade) {
                    TradeRule.direct -> {
                        val t = s.board.cells.mapNotNull { it.card }
                            .filter { it.owner != it.originalOwner }
                            .map { CardTransfer(it.def, it.originalOwner, it.owner) }
                        events.send(SessionEvent.GameOver(if (iWon) SessionEvent.Outcome.Won(t, null) else SessionEvent.Outcome.Lost))
                        events.send(SessionEvent.TradeResolved(t))
                    }
                    TradeRule.none -> events.send(SessionEvent.GameOver(if (iWon) SessionEvent.Outcome.Won(emptyList(), null) else SessionEvent.Outcome.Lost))
                    else -> {
                        concluded = true
                        val n = when (st.trade) {
                            TradeRule.one -> 1
                            TradeRule.diff -> minOf(abs(blue - red), loserBrought.size)
                            else -> loserBrought.size
                        }
                        if (iWon) {
                            pendingPick = PendingPick(n, loserBrought)
                            events.send(SessionEvent.GameOver(SessionEvent.Outcome.Won(emptyList(), pendingPick)))
                        } else events.send(SessionEvent.GameOver(SessionEvent.Outcome.Lost))
                    }
                }
            }
    }

    fun tradePick(ids: List<String>) = scope.launch { mutex.withLock {
        val p = pendingPick ?: return@launch
        val counts = p.candidates.map { it.id }.groupingBy { it }.eachCount()
        if (ids.size != p.count || ids.groupingBy { it }.eachCount().any { (id, c) -> (counts[id] ?: 0) < c })
            return@launch
        send(Msg.TradePick(ids))
        pendingPick = null
        events.send(SessionEvent.TradeResolved(ids.map { CardTransfer(catalog.getValue(it), myColor.other, myColor) }))
    } }

    private suspend fun onTradePick(m: Msg.TradePick) {
        val s = state ?: fault("protocol", "trade before match")
        if (!s.isOver) fault("protocol", "trade while playing")
        val blue = s.score(Owner.blue); val red = s.score(Owner.red)
        val winner = if (blue > red) Owner.blue else Owner.red
        if (winner != myColor.other) fault("protocol", "tradePick but I won")
        val mine = if (myColor == Owner.blue) broughtBlue else broughtRed
        val n = when (settings!!.trade) {
            TradeRule.one -> 1
            TradeRule.diff -> minOf(abs(blue - red), mine.size)
            else -> mine.size
        }
        if (m.ids.size != n) fault("trade", "picked ${m.ids.size}, expected $n")
        val counts = mine.map { it.id }.groupingBy { it }.eachCount()
        if (m.ids.groupingBy { it }.eachCount().any { (id, c) -> (counts[id] ?: 0) < c })
            fault("trade", "picked outside my hand")
        events.send(SessionEvent.TradeResolved(m.ids.map { CardTransfer(catalog.getValue(it), myColor, myColor.other) }))
    }

    private fun canonical(s: GameState, seq: Int): String = buildString {
        append(seq); append('|')
        s.board.cells.forEachIndexed { i, c ->
            if (i > 0) append(';')
            c.card?.let {
                append(it.def.id).append(':')
                append(if (it.originalOwner == Owner.blue) 'b' else 'r')
                append(if (it.owner == Owner.blue) 'b' else 'r')
            } ?: append('-')
            append(':').append(c.element.ordinal)
        }
    }

    private class SessionFault(val code: String, val detail: String) : Exception(detail)
    private fun fault(code: String, detail: String): Nothing = throw SessionFault(code, detail)

    private fun send(m: Msg) { if (!done) wire.send(wireJson.encodeToString(m)) }

    private fun terminate(e: SessionEvent) {
        if (done) return
        done = true
        wire.close()
        scope.launch { events.send(e); events.close() }
    }
    fun teardown() {
        if (done) return
        done = true
        wire.close()
        scope.launch { events.close() }
    }
}
