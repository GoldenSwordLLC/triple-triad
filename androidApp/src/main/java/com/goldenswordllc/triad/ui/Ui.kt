package com.goldenswordllc.triad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import triad.core.*
import triad.core.maxLoss
import triad.net.*

@Composable
fun MatchSetupScreen(
    decks: List<NamedDeck>, activeDeckId: String?, pref: ColorPref,
    onPickDeck: (String) -> Unit, onPickPref: (ColorPref) -> Unit,
    onHost: () -> Unit, onJoin: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Deck", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(decks, key = { i, _ -> i }) { _, deck ->
                FilterChip(selected = deck.id == activeDeckId, onClick = { onPickDeck(deck.id) },
                    label = { Text("${deck.name} (${deck.ids.size})") })
            }
        }
        Text("Play as", style = MaterialTheme.typography.titleMedium)
        ColorPrefPicker(pref, onPickPref)
        Text("Your deck and color lock in once you host or join, no counter-picking.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onHost, modifier = Modifier.weight(1f)) { Text("Host (show QR)") }
            Button(onClick = onJoin, modifier = Modifier.weight(1f)) { Text("Join (scan QR)") }
        }
    }
}

@Composable
fun ColorPrefPicker(current: ColorPref, onPick: (ColorPref) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorPref.entries.forEach { p ->
            FilterChip(selected = current == p, onClick = { onPick(p) },
                label = { Text(when (p) { ColorPref.blue -> "Blue"; ColorPref.red -> "Red"; ColorPref.any -> "Either" }) })
        }
    }
}

sealed interface PreMatchUi {
    data object WaitingForChallenger : PreMatchUi
    data object Connecting : PreMatchUi
    data class Connected(val peer: String) : PreMatchUi
    data class AwaitingAccept(val peer: String) : PreMatchUi
    data class RulesReview(val peer: String, val settings: MatchSettings, val deckSizeProblem: Boolean) : PreMatchUi
    data class Verify(val view: DeckVerificationView, val awaitingPeer: Boolean) : PreMatchUi
    data class Ended(val reason: String) : PreMatchUi
}

class PreMatchViewModel {
    var session: P2pSession? = null
    val ui = MutableStateFlow<PreMatchUi>(PreMatchUi.Connecting)
    var onMatchStarted: ((GameState, Owner) -> Unit)? = null
    private var collectJob: Job? = null

    fun bind(s: P2pSession) {
        session = s
        collectJob?.cancel()
        collectJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            s.eventsFlow.collect { e -> when (e) {
                is SessionEvent.Connected -> ui.value = PreMatchUi.Connected(e.peerName)
                is SessionEvent.RulesProposed -> ui.value = PreMatchUi.RulesReview(
                    peer = s.peerName, settings = e.settings,
                    deckSizeProblem = !e.settings.randomHands && s.deckSize != 5)
                SessionEvent.PeerAccepted -> ui.value = PreMatchUi.AwaitingAccept(s.peerName)
                is SessionEvent.VerifyDecks -> ui.value = PreMatchUi.Verify(e.view, awaitingPeer = false)
                is SessionEvent.MatchStarted -> onMatchStarted?.invoke(e.state, e.myColor)
                is SessionEvent.Declined -> ui.value = PreMatchUi.Ended(e.detail)
                is SessionEvent.Disconnected -> ui.value = PreMatchUi.Ended(e.detail)
                is SessionEvent.ProtocolError -> ui.value = PreMatchUi.Ended("${e.code}: ${e.detail}")
                is SessionEvent.Forfeit -> ui.value = PreMatchUi.Ended(e.reason)
                else -> {}
            } }
        }
    }

    fun unbind() { collectJob?.cancel(); collectJob = null }
    fun accept() = session?.acceptRules()
    fun decline() = session?.decline()
    fun ready() {
        session?.ready()
        ui.updateIfVerify()
    }
    private fun MutableStateFlow<PreMatchUi>.updateIfVerify() {
        val cur = value
        if (cur is PreMatchUi.Verify) this.value = cur.copy(awaitingPeer = true)
    }
}

@Composable
fun PreMatchScreen(vm: PreMatchViewModel, onExit: () -> Unit, myCardCount: Int = Int.MAX_VALUE,
                   transportNote: String? = null) {
    when (val s = vm.ui.collectAsState().value) {
        PreMatchUi.WaitingForChallenger -> WaitingPane("Waiting for a challenger…", onExit)
        PreMatchUi.Connecting -> WaitingPane("Connecting…", onExit)
        is PreMatchUi.Connected -> WaitingPane("Connected to ${s.peer}…", onExit)
        is PreMatchUi.AwaitingAccept -> WaitingPane("${s.peer} is reviewing the rules…", onExit)
        is PreMatchUi.RulesReview -> {

            val risk = s.settings.trade.let { t ->
                if (myCardCount - t.maxLoss() < 5)
                    "⚠ These rules can take up to ${t.maxLoss()} cards if you lose, that could leave you " +
                    "without a deck. Decline unless you're willing to risk it."
                else null
            }
            RulesReviewBody(s, vm::accept, vm::decline, riskNote = risk, transportNote = transportNote)
        }
        is PreMatchUi.Verify -> DeckVerifyBody(s.view, s.awaitingPeer, vm::ready, vm::decline, transportNote)
        is PreMatchUi.Ended -> WaitingPane(s.reason, onExit)
    }
}

@Composable
private fun PrematchPanel(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(22, 15, 6).copy(alpha = 0.88f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
internal fun GoldAction(text: String, filled: Boolean, enabled: Boolean = true,
                       modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier
        .clip(RoundedCornerShape(12.dp))
        .background(if (!enabled) Color(30, 22, 8).copy(alpha = 0.5f)
                    else if (filled) Color(120, 85, 20) else Color(30, 22, 8).copy(alpha = 0.85f))
        .border(if (filled) 2.dp else 1.dp,
                if (enabled) Color(0xFFD4AF37) else Color(150, 130, 90).copy(alpha = 0.35f),
                RoundedCornerShape(12.dp))
        .clickable(enabled = enabled) { onClick() }
        .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color(255, 240, 200)
                       else Color(190, 175, 145).copy(alpha = 0.45f),
             fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransportNote(note: String?) {
    if (note != null) Text("connected via $note", color = Color(160, 200, 255),
                           fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun WaitingPane(text: String, onExit: () -> Unit) {
    val isWin = text.startsWith("WIN BY")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (isWin) Color(140, 200, 140) else Color(255, 240, 200),
                 fontSize = if (isWin) 24.sp else 16.sp, fontWeight = FontWeight.Bold,
                 textAlign = TextAlign.Center,
                 modifier = Modifier.clip(RoundedCornerShape(12.dp))
                     .background(Color(22, 15, 6).copy(alpha = 0.88f))
                     .padding(horizontal = 20.dp, vertical = 12.dp))
            Spacer(Modifier.height(14.dp))
            GoldAction("Back", filled = false) { onExit() }
        }
    }
}

@Composable
fun RulesReviewBody(s: PreMatchUi.RulesReview, onAccept: () -> Unit, onDecline: () -> Unit,
                    riskNote: String? = null, transportNote: String? = null) {
    PrematchPanel {
        Text("${s.peer} proposes:", style = MaterialTheme.typography.titleMedium, color = Color(255, 240, 200))
        RulesSummary(s.settings)
        if (s.deckSizeProblem) {
            Text("Host requires 5-card decks, switch your deck before accepting.",
                color = Color(255, 120, 100))
        }
        if (riskNote != null) {
            Text(riskNote, color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
        }
        TransportNote(transportNote)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldAction("Decline", filled = false, modifier = Modifier.weight(1f), onClick = onDecline)
            GoldAction("Accept", filled = true, enabled = !s.deckSizeProblem,
                       modifier = Modifier.weight(2f), onClick = onAccept)
        }
    }
}

@Composable
fun RulesSummary(s: MatchSettings) {
    val active = buildList {
        if (s.rules.same) add("Same"); if (s.rules.plus) add("Plus")
        if (s.rules.sameWall) add("Same Wall"); if (s.rules.elemental) add("Elemental")
    }.ifEmpty { listOf("Basic") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Rules: ${active.joinToString(" + ")}", color = Color(245, 238, 220))
        Text("Hands: ${if (s.open) "Open" else "Closed"}${if (s.randomHands) " + Random" else ""}",
             color = Color(245, 238, 220))
        Text("Trade: ${s.trade.name}${if (s.suddenDeath) " + Sudden Death" else ""}",
             color = Color(245, 238, 220))
    }
}

@Composable
fun DeckVerifyBody(view: DeckVerificationView, awaitingPeer: Boolean, onReady: () -> Unit,
                   onDecline: () -> Unit, transportNote: String? = null) {
    val conflicts = view.bannedByMe.isNotEmpty() || view.bannedByThem.isNotEmpty()
    PrematchPanel {
        ColorBanner(view.myColor)
        if (view.bannedByMe.isNotEmpty())
            ConflictNotice("You refuse to face (bypass for this game only):", view.bannedByMe, owner = view.myColor.other)
        if (view.bannedByThem.isNotEmpty())
            ConflictNotice("Opponent refuses to face:", view.bannedByThem, owner = view.myColor)
        Text(if (view.randomHands) "Your pool, 5 drawn at random" else "Your deck",
             color = Color(230, 205, 160), fontWeight = FontWeight.Bold)
        DeckRow(view.myDeck, highlight = view.bannedByThem.map { it.id }.toSet(), owner = view.myColor)
        Text(if (view.randomHands) "${view.peerName}'s pool, 5 drawn at random" else "${view.peerName}'s deck",
             color = Color(230, 205, 160), fontWeight = FontWeight.Bold)
        DeckRow(view.peerDeck, highlight = view.bannedByMe.map { it.id }.toSet(), owner = view.myColor.other)
        if (conflicts) Text("⚠ cards marked red were refused for this game",
                            color = Color(190, 175, 145), fontSize = 10.sp)
        TransportNote(transportNote)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GoldAction("Decline", filled = false, modifier = Modifier.weight(1f), onClick = onDecline)
            GoldAction(when { awaitingPeer -> "Waiting…"; conflicts -> "Play anyway"; else -> "Ready" },
                       filled = true, enabled = !awaitingPeer,
                       modifier = Modifier.weight(2f), onClick = onReady)
        }
    }
}

@Composable
fun ColorBanner(myColor: Owner) {
    Surface(
        color = if (myColor == Owner.blue) Color(0xFF1565C0) else Color(0xFFB71C1C),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
    ) {
        Text("You are ${if (myColor == Owner.blue) "BLUE" else "RED"}",
            Modifier.padding(vertical = 10.dp), color = Color.White,
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConflictNotice(text: String, cards: List<CardDef>, owner: Owner) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(70, 25, 18).copy(alpha = 0.85f))
            .border(1.dp, Color(183, 28, 28), RoundedCornerShape(10.dp))
            .padding(8.dp)) {
        Text(text, color = Color(255, 170, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        DeckRow(cards, highlight = cards.map { it.id }.toSet(), owner = owner)
    }
}

@Composable
fun DeckRow(deck: List<CardDef>, highlight: Set<String>, owner: Owner) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(deck, key = { i, _ -> i }) { _, def ->
            CardThumb(def, conflict = def.id in highlight, owner = owner, modifier = Modifier.width(76.dp).height(102.dp))
        }
    }
}

@Composable
fun CardThumb(def: CardDef, conflict: Boolean, selected: Boolean = false,
              owner: Owner? = null, modifier: Modifier = Modifier) {

    val border = when {
        selected -> Color(0xFFD4AF37)
        conflict -> Color(0xFFB71C1C)
        owner == null -> elementColor(def.element)
        else -> Color.Transparent
    }
    val borderWidth = when {
        selected -> 3.dp
        conflict -> 2.dp
        else -> 0.dp
    }
    Card(modifier = modifier, border = BorderStroke(borderWidth, border)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CardArt(cardNo = def.no, owner = owner ?: Owner.blue, modifier = Modifier.fillMaxSize())
        }
    }
}

fun elementColor(e: Element) = when (e) {
    Element.fire -> Color(0xFFD84315); Element.ice -> Color(0xFF4FC3F7)
    Element.thunder -> Color(0xFFF9A825); Element.poison -> Color(0xFF8E24AA)
    Element.holy -> Color(0xFFE0D6C2); Element.wind -> Color(0xFF7CB342)
    Element.earth -> Color(0xFF8D6E63); Element.water -> Color(0xFF00838F)
    Element.dark -> Color(0xFF455A64); Element.none -> Color(0xFF616161)
}

@Composable
fun TradePickScreen(
    pending: PendingPick, peerName: String, owner: Owner,
    onConfirm: (List<String>) -> Unit,
) {
    val selected = remember { mutableStateListOf<Int>() }
    Box(Modifier.fillMaxSize()
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                       indication = null) {}
    ) {

        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
               horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (pending.count == pending.candidates.size) "You won! Review the cards you're taking from ${peerName}:"
                else "You won! Choose ${pending.count} card${if (pending.count > 1) "s" else ""} from ${peerName}:",
                color = Color(255, 240, 200), fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(pending.candidates, key = { i, _ -> i }) { i, def ->
                    CardThumb(
                        def, conflict = false, selected = i in selected, owner = owner,
                        modifier = Modifier.fillMaxWidth().aspectRatio(CardSheets.ASPECT).clickable {
                            if (i in selected) selected.remove(i)
                            else if (selected.size < pending.count) selected.add(i)
                        })
                }
            }
            GoldAction(
                if (selected.size == pending.count) "Take ${selected.size}/${pending.count}"
                else "Choose ${pending.count - selected.size} more",
                filled = true, enabled = selected.size == pending.count,
                modifier = Modifier.fillMaxWidth(),
            ) { onConfirm(selected.map { pending.candidates[it].id }) }
        }
    }
}

data class ResultState(
    val outcome: SessionEvent.Outcome,
    val transfers: List<CardTransfer> = emptyList(),
    val awaitingPeerPick: Boolean = false,
    val applied: Boolean = false,
)

@Composable
fun EndOfMatchHost(
    result: ResultState, peerName: String, myColor: Owner,
    onConfirmPick: (List<String>) -> Unit, onDone: () -> Unit,
    onProposeTrade: (() -> Unit)? = null,
) {
    val pending = (result.outcome as? SessionEvent.Outcome.Won)?.pendingPick
    if (pending != null && !result.applied) TradePickScreen(pending, peerName, myColor.other, onConfirmPick)
    else MatchResultScreen(result, myColor, onDone, onProposeTrade)
}

@Composable
fun MatchResultScreen(state: ResultState, myColor: Owner, onDone: () -> Unit,
                      onProposeTrade: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize()
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                       indication = null) {}
    ) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val (label, color) = when (state.outcome) {
                is SessionEvent.Outcome.Won -> "VICTORY" to Color(0xFF2E7D32)
                SessionEvent.Outcome.Lost -> "DEFEAT" to Color(0xFFB71C1C)
                SessionEvent.Outcome.Drawn -> "DRAW" to Color(0xFF616161)
            }
            Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(label, Modifier.padding(vertical = 20.dp), color = Color.White,
                     style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            }
            if (state.awaitingPeerPick) {
                Text("Opponent is choosing a card to take…", color = Color(255, 240, 200),
                     modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            val gained = state.transfers.filter { it.to == myColor }.map { it.card }
            val lost = state.transfers.filter { it.from == myColor }.map { it.card }
            if (gained.isNotEmpty()) TransferRow("Cards gained", gained, owner = myColor.other)
            if (lost.isNotEmpty()) TransferRow("Cards lost", lost, owner = myColor)
            Spacer(Modifier.weight(1f))
            if (onProposeTrade != null && !state.awaitingPeerPick) {
                Text("⚖  PROPOSE TRADE", color = Color(230, 205, 160), fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                         .clip(RoundedCornerShape(12.dp))
                         .background(Color(30, 22, 8))
                         .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                         .clickable { onProposeTrade() }.padding(vertical = 10.dp))
                Spacer(Modifier.height(8.dp))
            }
            Text("DONE", color = Color(255, 240, 200), fontWeight = FontWeight.Bold,
                 textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                     .clip(RoundedCornerShape(12.dp))
                     .background(Color(120, 85, 20))
                     .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                     .clickable { onDone() }.padding(vertical = 12.dp))
        }
    }
}

@Composable
fun TransferRow(label: String, cards: List<CardDef>, owner: Owner) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(cards, key = { i, _ -> i }) { _, def ->
                CardThumb(def, conflict = false, owner = owner, modifier = Modifier.width(76.dp).height(102.dp))
            }
        }
    }
}
