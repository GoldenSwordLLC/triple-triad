package com.goldenswordllc.triad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import triad.net.hostRelay
import triad.net.canonicalRelayCode

import triad.net.joinRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import triad.core.CardDef
import triad.net.ColorPref
import triad.core.GameState
import triad.core.MatchSettings
import triad.core.Owner
import triad.core.TradeRule
import triad.core.maxLoss
import triad.net.P2pSession

const val RELAY_HOST = "triad.goldenswordllc.com"
const val RELAY_PORT = 443

class OnlineHostViewModel(
    private val relayHost: String,
    private val relayPort: Int,
    private val catalog: Map<String, CardDef>,
    private val myDeckIds: List<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val code = MutableStateFlow<String?>(null)
    val session = MutableStateFlow<P2pSession?>(null)
    val error = MutableStateFlow<String?>(null)
    private var wire: triad.net.Wire? = null
    private var eventsJob: kotlinx.coroutines.Job? = null

    fun start(settings: MatchSettings) {
        if (session.value != null || code.value != null) return
        scope.launch {
            try {
                val (w, room) = hostRelay(relayHost, relayPort, scope)
                wire = w
                code.value = room
                val s = P2pSession(w, isHost = true, myName = "Host", token = room, catalog = catalog,
                    me = P2pSession.PlayerLocal(myDeckIds, emptyList(), ColorPref.any),
                    hostSettings = settings)
                s.start()
                // keep the CODE on screen until a challenger actually arrives; this collector
                // hands the event stream to PreMatchFlow the moment one does (channels are
                // exclusive-consumer, so it cancels itself first)
                eventsJob = scope.launch {
                    s.eventsFlow.collect { e ->
                        when (e) {
                            is triad.net.SessionEvent.Connected -> {
                                session.value = s
                                eventsJob?.cancel()
                            }
                            is triad.net.SessionEvent.Disconnected ->
                                if (session.value == null) error.value = "Connection lost while waiting, host again."
                            is triad.net.SessionEvent.ProtocolError ->
                                if (session.value == null) error.value = "${e.code}: ${e.detail}"
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Could not reach the relay"
            }
        }
    }

    fun shutdown() { eventsJob?.cancel(); runCatching { wire?.close() }; scope.cancel() }
}

@Composable
fun OnlineHostScreen(
    relayHost: String,
    relayPort: Int,
    catalog: Map<String, CardDef>,
    myDeckIds: List<String>,
    cardCount: Int,
    onMatchStarted: (P2pSession, GameState, Owner) -> Unit,
    onExit: () -> Unit,
) {
    val vm = remember { OnlineHostViewModel(relayHost, relayPort, catalog, myDeckIds) }
    // NOTE: no shutdown-on-dispose here. Navigation to the match screen disposes this
    // composable, and closing the host wire at that moment would hang up on the joiner
    // mid-match; the VM's scope ends with its explicit Cancel or app exit instead.
    val code by vm.code.collectAsState()
    val session by vm.session.collectAsState()
    val error by vm.error.collectAsState()
    var trade by remember { mutableStateOf(TradeRule.one) }
    fun ruleAllowed(rule: TradeRule): Boolean = cardCount - rule.maxLoss() >= 5

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.24f)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                session != null -> {
                    val s = session!!
                    PreMatchFlow(s, transportNote = "online relay",
                        onMatchStarted = { st, color -> onMatchStarted(s, st, color) },
                        onExit = onExit)
                }
                code != null -> {
                    val ctx = LocalContext.current
                    DisposableEffect(Unit) {
                        val window = (ctx as? android.app.Activity)?.window
                        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                        Column(Modifier.fillMaxSize().padding(16.dp),
                               horizontalAlignment = Alignment.CenterHorizontally,
                               verticalArrangement = Arrangement.Center) {
                            Text("READ THIS CODE TO YOUR FRIEND", color = Color(255, 240, 200), fontSize = 14.sp,
                                 fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            Text((code ?: "").uppercase(), color = Color(255, 240, 200),
                                 fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                 lineHeight = 32.sp,
                                 modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                     .background(Color(0, 0, 0).copy(alpha = 0.35f))
                                     .padding(horizontal = 18.dp, vertical = 10.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("they type it under JOIN WITH A CODE", color = Color(190, 175, 145),
                                 fontSize = 11.sp)
                            Spacer(Modifier.height(18.dp))
                            Text("Waiting for your friend to join…", color = Color(255, 240, 200), fontSize = 13.sp)
                            error?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(18.dp))
                            GoldAction("✕  CANCEL", filled = false, modifier = Modifier.fillMaxWidth()) {
                                vm.shutdown(); onExit()
                            }
                        }
                    }
                }
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                               horizontalAlignment = Alignment.CenterHorizontally,
                               verticalArrangement = Arrangement.Center) {
                            Text("HOST ONLINE", color = Color(255, 240, 200), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            Text("TRADE RULE, what the loser gives up", color = Color(230, 205, 160),
                                 fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                 modifier = Modifier.align(Alignment.Start))
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(TradeRule.none, TradeRule.one, TradeRule.direct,
                                       TradeRule.diff, TradeRule.all).forEach { rule ->
                                    val sel = trade == rule
                                    val allowed = ruleAllowed(rule)
                                    Box(Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(when {
                                            sel && allowed -> Color(0xFFD4AF37)
                                            else -> Color(30, 22, 8).copy(alpha = 0.85f)
                                        })
                                        .border(if (sel) 2.dp else 1.dp,
                                                if (allowed) Color(0xFFD4AF37) else Color(150, 130, 90).copy(alpha = 0.35f),
                                                RoundedCornerShape(10.dp))
                                        .clickable(enabled = allowed) { if (allowed) trade = rule }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text(rule.name.uppercase(),
                                             color = if (sel && allowed) Color(55, 38, 10)
                                                     else if (allowed) Color(255, 240, 200)
                                                     else Color(190, 175, 145).copy(alpha = 0.45f),
                                             fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(when (trade) {
                                     TradeRule.none -> "Loser pays nothing, a friendly game."
                                     TradeRule.one -> "Winner takes 1 card from the loser's hand."
                                     TradeRule.direct -> "Every captured card goes to its captor, both ways."
                                     TradeRule.diff -> "Winner takes cards equal to the score gap."
                                     TradeRule.all -> "Winner takes the loser's whole hand."
                                 },
                                 color = Color(245, 238, 220), fontSize = 11.sp,
                                 textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(16.dp))
                            GoldButton("HOST A ROOM  ▶", filled = true) { vm.start(MatchSettings(trade = trade)) }
                            error?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(14.dp))
                            GoldAction("✕  CANCEL", filled = false, modifier = Modifier.fillMaxWidth()) { onExit() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineJoinScreen(
    relayHost: String,
    relayPort: Int,
    catalog: Map<String, CardDef>,
    myDeckIds: List<String>,
    onMatchStarted: (P2pSession, GameState, Owner) -> Unit,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wireScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var joinSession by remember { mutableStateOf<P2pSession?>(null) }
    DisposableEffect(Unit) { onDispose { } }

    when {
        joinSession != null -> {
            val s = joinSession!!
            PreMatchFlow(s, transportNote = "online relay",
                onMatchStarted = { st, color -> onMatchStarted(s, st, color) },
                onExit = onExit)
        }
        else -> Box(Modifier.fillMaxSize()) {
            MatBackground(Modifier.matchParentSize(), dim = 0.24f)
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                           horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.Center) {
                        Text("JOIN ONLINE", color = Color(255, 240, 200), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text("enter the 6-character code your friend reads you", color = Color(190, 175, 145),
                             fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(code, { code = it; errorMsg = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("53DA92", color = Color(150, 130, 90), fontSize = 13.sp) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFD4AF37),
                                unfocusedBorderColor = Color(150, 130, 90),
                                focusedTextColor = Color(245, 238, 220),
                                unfocusedTextColor = Color(245, 238, 220),
                                cursorColor = Color(0xFFD4AF37),
                            ))
                        errorMsg?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Color(255, 120, 100), fontWeight = FontWeight.Bold,
                                 fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(16.dp))
                        GoldButton(if (busy) "JOINING…" else "JOIN  ▶", filled = true, enabled = !busy && code.isNotBlank()) {
                            busy = true; errorMsg = null
                            scope.launch {
                                try {
                                    val canonical = canonicalRelayCode(code)
                                        ?: throw java.io.IOException("Codes look like 53DA92.")
                                    val wire = joinRelay(relayHost, relayPort, canonical, wireScope)
                                    val s = P2pSession(wire, isHost = false, myName = "Joiner", token = canonical,
                                        catalog = catalog,
                                        me = P2pSession.PlayerLocal(myDeckIds, emptyList(), ColorPref.any),
                                        hostSettings = null)
                                    s.start()
                                    joinSession = s
                                } catch (e: Exception) {
                                    errorMsg = e.message ?: "Could not reach the relay"
                                } finally { busy = false }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        GoldAction("✕  CANCEL", filled = false, modifier = Modifier.fillMaxWidth()) { onExit() }
                    }
                }
            }
        }
    }
}
