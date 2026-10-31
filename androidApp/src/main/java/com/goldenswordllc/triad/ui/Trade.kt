package com.goldenswordllc.triad.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenswordllc.triad.net.isOnWifi
import com.goldenswordllc.triad.net.lanIp
import com.goldenswordllc.triad.net.parseInvite
import triad.core.randomHex
import com.goldenswordllc.triad.net.qrBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import triad.core.CardDef
import triad.core.Owner
import triad.net.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TradeHostViewModel(
    private val lanIpAvailable: String?,
    private val catalog: Map<String, CardDef>,
    private val myCollection: Map<String, Int>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val qrPayload = mutableStateOf<String?>(null)
    val relayCode = mutableStateOf<String?>(null)
    val session = mutableStateOf<TradeSession?>(null)
    val error = mutableStateOf<String?>(null)
    private var wire: Wire? = null
    private var server: ServerSocket? = null
    private var eventsJob: kotlinx.coroutines.Job? = null
    private val claimed = AtomicBoolean(false)

    fun start() {
        if (qrPayload.value != null || relayCode.value != null) return
        val token = randomHex(4)
        // BOTH radios concurrently: the LAN branch blocks in accept(), so a sequential
        // relay call would never run while the QR waits — the code must be requested
        // in parallel; `claimed` arbitrates whichever peer lands first
        if (lanIpAvailable != null) {
            scope.launch {
                try {
                    val s = ServerSocket(0)
                    server = s
                    qrPayload.value = "triad://v1?tr=tcp&mode=trade&ip=$lanIpAvailable&port=${s.localPort}&t=$token"
                    val sock = withContext(Dispatchers.IO) { runCatching { s.accept() }.getOrNull() }
                    runCatching { s.close() }
                    if (sock != null && claimed.compareAndSet(false, true)) {
                        attach(TcpWire(sock, scope), token)
                    }
                } catch (_: Exception) {}
            }
        }
        scope.launch {
            try {
                val (w, code) = hostRelay(RELAY_HOST, RELAY_PORT, scope)
                if (claimed.compareAndSet(false, true)) {
                    wire = w; relayCode.value = code
                    attach(w, code)
                } else w.close()
            } catch (e: Exception) {
                if (lanIpAvailable == null && !claimed.get()) error.value = e.message ?: "Could not reach the relay"
            }
        }
    }

    private fun attach(w: Wire, token: String) {
        val s = TradeSession(w, isHost = true, myName = "Host", token = token,
            catalog = catalog, myCollection = myCollection)
        s.start()
        // parking at the relay is NOT a peer: hold the session until the joiner's
        // collection declaration arrives (Ready), then hand the event stream to the
        // trade room (channels are exclusive-consumer, so cancel ourselves first)
        eventsJob = scope.launch {
            s.eventsFlow.collect { e ->
                when (e) {
                    is TradeEvent.Ready -> {
                        session.value = s
                        eventsJob?.cancel()
                    }
                    is TradeEvent.Fault -> if (session.value == null) error.value = "${e.code}: ${e.detail}"
                    TradeEvent.PeerGone -> if (session.value == null) error.value = "Connection lost while waiting, host again."
                    else -> {}
                }
            }
        }
    }

    fun shutdown() { eventsJob?.cancel(); runCatching { wire?.close() }; runCatching { server?.close() }; scope.cancel() }
}

@Composable
fun TradeHostScreen(
    catalog: Map<String, CardDef>,
    myCollection: Map<String, Int>,
    onTradeReady: (TradeSession) -> Unit,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lan = remember { if (isOnWifi(context)) lanIp() else null }
    val vm = remember { TradeHostViewModel(lan, catalog, myCollection) }
    LaunchedEffect(Unit) {
        vm.start()
        while (vm.session.value == null) delay(100)
        onTradeReady(vm.session.value!!)
    }
    // NO shutdown-on-dispose: navigating to the trade room disposes this screen, and
    // closing the wire there would hang up mid-trade (same trap as the online match)

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TRADE CARDS", color = Color(255, 240, 200), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.padding(vertical = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.Center) {
                    Text("SCAN THIS QR, OR READ THE CODE", color = Color(245, 238, 220),
                         fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    vm.qrPayload.value?.let { payload ->
                        var img by remember(payload) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(payload) {
                            img = withContext(Dispatchers.Default) { qrBitmap(payload, 480) }
                        }
                        img?.let {
                            Box(Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(12.dp))
                                    .background(Color.White).border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                                    .padding(6.dp)) {
                                Image(it.asImageBitmap(), "Trade QR", Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                        }
                    }
                    vm.relayCode.value?.let { c ->
                        Spacer(Modifier.height(10.dp))
                        Text("online code: ${c.uppercase()}", color = Color(255, 240, 200),
                             fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("waiting for your friend…", color = Color(190, 175, 145), fontSize = 12.sp)
                    vm.error.value?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    GoldAction("✕  CANCEL", filled = false, modifier = Modifier.fillMaxWidth()) { onExit() }
                }
            }
        }
    }
}

@Composable
fun TradeJoinScreen(
    catalog: Map<String, CardDef>,
    myCollection: Map<String, Int>,
    onTradeReady: (TradeSession) -> Unit,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wireScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var mode by remember { mutableStateOf("choose") }      // choose | scan | code | busy
    var code by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun attach(w: Wire, token: String) {
        val s = TradeSession(w, isHost = false, myName = "Joiner", token = token,
            catalog = catalog, myCollection = myCollection)
        s.start()
        onTradeReady(s)
    }

    when (mode) {
        "scan" -> com.goldenswordllc.triad.net.QrScannerScreen(
            onResult = { raw ->
                scope.launch {
                    val inv = raw?.let { parseInvite(it) }
                    if (inv == null || inv.mode != "trade") {
                        errorMsg = if (inv?.mode == "match") "That's a match invite, use TRADE CARDS."
                                   else "Not a Triad trade invite."
                        mode = "choose"
                    } else {
                        mode = "busy"
                        try {
                            val ip = inv.ip ?: throw java.io.IOException("invite missing ip")
                            val port = inv.port ?: 44000
                            val sock = withContext(Dispatchers.IO) {
                                Socket().apply { connect(java.net.InetSocketAddress(ip, port), 3_000) }
                            }
                            attach(TcpWire(sock, wireScope), inv.token)
                        } catch (e: Exception) {
                            errorMsg = "Could not connect, try the online code instead."
                            mode = "code"
                        }
                    }
                }
            },
            onExit = { mode = "choose" },
        )
        else -> Box(Modifier.fillMaxSize()) {
            MatBackground(Modifier.matchParentSize(), dim = 0.3f)
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("JOIN A TRADE", color = Color(255, 240, 200), fontSize = 22.sp,
                     fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                Box(Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                           horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.Center) {
                        errorMsg?.let {
                            Text(it, color = Color(255, 120, 100), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                 textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                        }
                        GoldButton("SCAN QR  ▶", filled = true) { mode = "scan" }
                        Spacer(Modifier.height(12.dp))
                        Text("or enter the online code", color = Color(190, 175, 145), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.OutlinedTextField(code, { code = it; errorMsg = null },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("53DA92", color = Color(150, 130, 90), fontSize = 13.sp) },
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFD4AF37), unfocusedBorderColor = Color(150, 130, 90),
                                focusedTextColor = Color(245, 238, 220), unfocusedTextColor = Color(245, 238, 220),
                                cursorColor = Color(0xFFD4AF37)))
                        Spacer(Modifier.height(10.dp))
                        GoldButton(if (mode == "busy") "JOINING…" else "JOIN  ▶", filled = true,
                                   enabled = mode != "busy" && code.isNotBlank()) {
                            mode = "busy"
                            scope.launch {
                                try {
                                    val canonical = canonicalRelayCode(code)
                                        ?: throw java.io.IOException("Codes look like 53DA92.")
                                    attach(joinRelay(RELAY_HOST, RELAY_PORT, canonical, wireScope), canonical)
                                } catch (e: Exception) {
                                    errorMsg = e.message ?: "Could not reach the relay"
                                    mode = "code"
                                }
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

@Composable
fun TradeRoomScreen(
    session: TradeSession,
    catalog: Map<String, CardDef>,
    myCollection: Map<String, Int>,
    onDone: (gained: List<CardDef>, lost: List<CardDef>) -> Unit,
    onExit: () -> Unit,
) {
    val offered = remember { mutableStateListOf<String>() }
    var peerOffer by remember { mutableStateOf<List<String>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<Pair<List<CardDef>, List<CardDef>>?>(null) }
    var fault by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            session.eventsFlow.collect { e ->
                when (e) {
                    is TradeEvent.Ready -> ready = true
                    is TradeEvent.PeerOffer -> peerOffer = e.ids
                    is TradeEvent.Done -> finished = e.gained to e.lost
                    is TradeEvent.Fault -> fault = "${e.code}: ${e.detail}"
                    TradeEvent.PeerGone -> fault = "connection lost"
                }
            }
        }
        onDispose { job.cancel() }
    }
    finished?.let { (gained, lost) ->
        LaunchedEffect(gained, lost) { onDone(gained, lost) }
    }

    val owned = myCollection.filterValues { it > 0 }.keys.mapNotNull { catalog[it] }
        .sortedWith(compareBy({ it.level }, { it.no }))
    fun remaining(id: String) = (myCollection[id] ?: 0) - offered.count { it == id }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.3f)
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("TRADING", color = Color(255, 240, 200), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (ready) "tap cards to offer them" else "connecting…", color = Color(190, 175, 145), fontSize = 11.sp)
            fault?.let {
                Text(it, color = Color(255, 120, 100), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                     modifier = Modifier.padding(vertical = 6.dp))
                GoldAction("◀  BACK", filled = false) { onExit() }
                return@Column
            }
            finished?.let { (gained, lost) ->
                Spacer(Modifier.height(8.dp))
                Text("TRADE COMPLETE", color = Color(140, 200, 140), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (gained.isNotEmpty()) Text("gained: ${gained.joinToString { it.name }}", color = Color(245, 238, 220), fontSize = 12.sp)
                if (lost.isNotEmpty()) Text("lost: ${lost.joinToString { it.name }}", color = Color(245, 238, 220), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                GoldAction("◀  DONE", filled = true, modifier = Modifier.fillMaxWidth()) { onExit() }
            } ?: Column(Modifier.weight(1f)) {
                if (peerOffer.isNotEmpty()) {
                    Text("THEY OFFER", color = Color(230, 205, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        peerOffer.take(5).forEach { id ->
                            catalog[id]?.let {
                                CardThumb(it, conflict = false, owner = Owner.red,
                                          modifier = Modifier.weight(1f).aspectRatio(CardSheets.ASPECT))
                            }
                        }
                    }
                }
                Text("YOUR COLLECTION, tap to offer", color = Color(230, 205, 160), fontSize = 11.sp,
                     fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.weight(1f).fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(Color(22, 15, 6).copy(alpha = 0.75f))) {
                    LazyVerticalGrid(columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        itemsIndexed(owned, key = { _, c -> c.id }) { _, def ->
                            val left = remaining(def.id)
                            Box(Modifier.aspectRatio(CardSheets.ASPECT).clickable(enabled = left > 0) {
                                offered.add(def.id)
                            }) {
                                CardThumb(def, conflict = false, selected = left == 0, owner = Owner.blue,
                                          modifier = Modifier.fillMaxWidth().aspectRatio(CardSheets.ASPECT))
                            }
                        }
                    }
                }
                if (offered.isNotEmpty()) {
                    Text("your offer: ${offered.size} card(s), tap a slot to remove",
                         color = Color(190, 175, 145), fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        offered.take(8).forEachIndexed { i, id ->
                            catalog[id]?.let {
                                CardThumb(it, conflict = false, owner = Owner.blue,
                                          modifier = Modifier.width(42.dp).aspectRatio(CardSheets.ASPECT)
                                              .clickable { offered.removeAt(i) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GoldAction("SEND OFFER", filled = true, enabled = offered.isNotEmpty(),
                               modifier = Modifier.weight(1f)) { session.propose(offered.toList()) }
                    if (peerOffer.isNotEmpty() && offered.isNotEmpty()) {
                        GoldAction("ACCEPT ▶", filled = true, modifier = Modifier.weight(1f)) { session.accept() }
                    }
                }
            }
        }
    }
}
