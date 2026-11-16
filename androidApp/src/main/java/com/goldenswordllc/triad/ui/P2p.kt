package com.goldenswordllc.triad.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goldenswordllc.triad.net.HostViewModel
import com.goldenswordllc.triad.net.QrScannerScreen
import com.goldenswordllc.triad.net.parseScannedInvite
import kotlinx.coroutines.*
import triad.core.CardDef
import triad.core.GameState
import triad.core.MatchSettings
import triad.core.Rules
import triad.core.Owner
import triad.net.ColorPref
import triad.net.P2pSession
import triad.net.SessionEvent

private val P2pCream = Color(255, 240, 200)

@Composable
fun PreMatchFlow(session: P2pSession, myCardCount: Int = Int.MAX_VALUE,
                 transportNote: String? = null,
                 onMatchStarted: (GameState, Owner) -> Unit, onExit: () -> Unit) {
    val vm = remember { PreMatchViewModel() }
    DisposableEffect(session) {
        vm.onMatchStarted = onMatchStarted
        vm.bind(session)
        onDispose { vm.unbind() }
    }
    PreMatchScreen(vm, onExit, myCardCount, transportNote)
}

@Composable
fun HostLobbyScreen(
    catalog: Map<String, CardDef>,
    myDeckIds: List<String>,
    onMatchStarted: (P2pSession, GameState, Owner) -> Unit,
    onExit: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: HostViewModel = viewModel(initializer = { HostViewModel(app, catalog) })
    val qr by vm.qrPayload.collectAsState()
    val session by vm.session.collectAsState()
    val error by vm.error.collectAsState()
    val transport by vm.transport.collectAsState()
    var started by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(Rules()) }

    val context = LocalContext.current
    val btAdapter = remember {
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }
    val onWifi = remember { com.goldenswordllc.triad.net.isOnWifi(context) }
    var screenError by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf(false) }

    fun begin(bleEnabled: Boolean) {
        started = true
        vm.start(
            settings = MatchSettings(rules = rules),
            me = P2pSession.PlayerLocal(myDeckIds, emptyList(), ColorPref.any),
            bleEnabled = bleEnabled,
        )
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (pendingEnable) {
            pendingEnable = false
            if (result.resultCode == android.app.Activity.RESULT_OK) begin(bleEnabled = true)
            else screenError = "Bluetooth is off and there's no Wi-Fi, hosting needs one of them."
        }
    }

    val btPerms = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf<String>()
    }
    var pendingStartFlag by remember { mutableStateOf(false) }
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (pendingStartFlag) {
            pendingStartFlag = false
            val bleOk = btPerms.isEmpty() || grants.values.all { it }
            val btOn = btAdapter?.isEnabled == true
            when {
                !bleOk && onWifi -> { screenError = "Bluetooth permission denied, hosting over Wi-Fi only."; begin(false) }
                !bleOk -> screenError = "Bluetooth permission denied and no Wi-Fi, hosting needs one of them."
                btOn -> begin(true)
                onWifi -> { screenError = "Bluetooth is off, hosting over Wi-Fi only."; begin(false) }
                else -> { pendingEnable = true
                          enableBtLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.24f, bottomScrim = true)
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                session != null -> {
                    val s = session!!
                    PreMatchFlow(s, transportNote = transport,
                                 onMatchStarted = { st, color -> onMatchStarted(s, st, color) },
                                 onExit = onExit)
                }
                started -> {

                    Box(Modifier.weight(1f).fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                        Column(Modifier.fillMaxSize().padding(16.dp),
                               horizontalAlignment = Alignment.CenterHorizontally,
                               verticalArrangement = Arrangement.Center) {
                            Text("SHOW THIS QR TO YOUR FRIEND", color = P2pCream, fontSize = 15.sp,
                                 fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            qr?.let { payload ->

                                var qrImage by remember(payload) { mutableStateOf<Bitmap?>(null) }
                                LaunchedEffect(payload) {
                                    qrImage = kotlinx.coroutines.withContext(Dispatchers.Default) {
                                        com.goldenswordllc.triad.net.qrBitmap(payload, 600)
                                    }
                                }
                                val shown = qrImage
                                if (shown == null) {
                                    Text("Generating QR…", color = P2pCream, fontSize = 12.sp)
                                } else {

                                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                            .background(Color.White)
                                            .border(3.dp, Color(0xFFD4AF37), RoundedCornerShape(16.dp))
                                            .padding(10.dp)) {
                                        Image(shown.asImageBitmap(), contentDescription = "Match invite QR code",
                                              modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                                    }
                                }
                            } ?: Text("Generating QR…", color = P2pCream, fontSize = 12.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("Waiting for a challenger…  (Wi-Fi or Bluetooth)", color = P2pCream,
                                 fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                 modifier = Modifier
                                     .clip(RoundedCornerShape(10.dp))
                                     .background(Color(0, 0, 0).copy(alpha = 0.35f))
                                     .padding(horizontal = 12.dp, vertical = 6.dp))
                            error?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(14.dp))
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
                            Text("HOST A MATCH", color = P2pCream, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            Text("MATCH RULES, optional", color = Color(230, 205, 160), fontSize = 11.sp,
                                 fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                            Spacer(Modifier.height(6.dp))
                            RulesPicker(rules = rules, onToggle = { i -> rules = rules.toggled(i) },
                                        modifier = Modifier.align(Alignment.Start))
                            Spacer(Modifier.height(14.dp))
                    Spacer(Modifier.height(14.dp))
                    GoldButton("SHOW QR  ▶", filled = true) {
                        screenError = null
                        if (btPerms.isEmpty()) {
                            val btOn = btAdapter?.isEnabled == true
                            when {
                                btOn -> begin(true)
                                onWifi -> begin(false)
                                btAdapter != null -> { pendingEnable = true
                                    enableBtLauncher.launch(android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                else -> screenError = "This phone has no Bluetooth and no Wi-Fi, can't host."
                            }
                        } else {
                            pendingStartFlag = true
                            btLauncher.launch(btPerms)
                        }
                    }
                    screenError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("ERROR: $it", color = Color(255, 120, 100), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(14.dp))
                    GoldAction("✕  CANCEL", filled = false, modifier = Modifier.fillMaxWidth()) {
                        vm.shutdown(); onExit()
                    }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JoinFlowScreen(
    catalog: Map<String, CardDef>,
    myDeckIds: List<String>,
    onMatchStarted: (P2pSession, GameState, Owner) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val wireScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var phase by remember { mutableStateOf("scan") }
    var invite by remember { mutableStateOf<com.goldenswordllc.triad.net.QrInvite?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var transport by remember { mutableStateOf<String?>(null) }
    var joinSession by remember { mutableStateOf<P2pSession?>(null) }

    fun startJoin(inv: com.goldenswordllc.triad.net.QrInvite) {
        invite = inv
        status = null
        errorMsg = null
        phase = "connecting"
        scope.launch {
            try {
                val me = P2pSession.PlayerLocal(myDeckIds, emptyList(), ColorPref.any)
                val s = com.goldenswordllc.triad.net.joinFromInvite(
                    context, inv, myName = "Joiner",
                    catalog = catalog, me = me, scope = wireScope,
                    onStatus = { status = it },
                    onTransport = { transport = it })
                joinSession = s
                phase = "prematch"
            } catch (e: Exception) {
                errorMsg = e.message ?: "Connection failed"
                phase = "scan"
            }
        }
    }

    val blePerms = remember {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
    var pendingJoin by remember { mutableStateOf(false) }
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val inv = invite
        if (pendingJoin && inv != null) {
            pendingJoin = false
            if (grants.values.all { it }) startJoin(inv)
            else {

                errorMsg = "Bluetooth permission denied, cross-network joins won't work."
                startJoin(inv)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.24f, bottomScrim = true)
        Column(Modifier.fillMaxSize()) {
            when {
                phase == "prematch" && joinSession != null -> {
                    val s = joinSession!!
                    PreMatchFlow(s, transportNote = transport,
                                 onMatchStarted = { st, color -> onMatchStarted(s, st, color) },
                                 onExit = onExit)
                }
                phase == "connecting" -> {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center)
                                .fillMaxWidth().padding(horizontal = 32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(22, 15, 6).copy(alpha = 0.88f))
                                .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("JOINING", color = P2pCream, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(status ?: "Connecting to ${invite?.name ?: "host"}…",
                                 color = Color(230, 205, 160), fontSize = 14.sp,
                                 textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        GoldAction("✕ Cancel", filled = false,
                                   modifier = Modifier.align(Alignment.BottomCenter)
                                       .padding(bottom = 28.dp)) { onExit() }
                    }
                }
                else -> {
                    QrScannerScreen(
                        onResult = { raw ->
                            scope.launch {
                                val inv = parseScannedInvite(raw)
                                if (inv == null && raw != null) {
                                    errorMsg = "That QR isn't a Triad invite, try again."
                                } else if (inv?.mode == "trade") {
                                    errorMsg = "Trade rooms are retired."
                                } else if (inv != null) {

                                    invite = inv
                                    errorMsg = null
                                    pendingJoin = true
                                    bleLauncher.launch(blePerms)
                                }
                            }
                        },
                        onExit = onExit,
                    )
                    errorMsg?.let {
                        Text(it, color = Color(255, 140, 120), fontSize = 13.sp,
                             fontWeight = FontWeight.Bold,
                             modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun MultiplayerScreen(hasActiveDeck: Boolean, onDecks: () -> Unit,
                      onPassPlay: () -> Unit, onHost: () -> Unit, onJoin: () -> Unit,
                      onHostOnline: () -> Unit = {}, onJoinOnline: () -> Unit = {},
                      onBack: () -> Unit) {
    var askForDeck by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (askForDeck != null && !hasActiveDeck) {
        val mode = askForDeck
        NoDeckDialog(
            onBuild = { askForDeck = null; onDecks() },
            onPlayAnyway = { askForDeck = null; mode?.invoke() },
            onDismiss = { askForDeck = null },
        )
    }
    fun gate(mode: () -> Unit): () -> Unit = if (hasActiveDeck) mode else { { askForDeck = mode } }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.24f, bottomScrim = true)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text("MULTIPLAYER", color = Color(255, 240, 200), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            Box(Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(Color(22, 15, 6).copy(alpha = 0.88f))) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp),
                       horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.Center) {
                        Text("ONE PHONE", color = Color(230, 205, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        GoldButton("PASS & PLAY", onClick = gate(onPassPlay))
                        Spacer(Modifier.height(14.dp))
                        Text("TWO PHONES, NEARBY", color = Color(230, 205, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        GoldButton("HOST A MATCH", onClick = gate(onHost))
                        GoldButton("JOIN A MATCH", onClick = gate(onJoin))
                        Spacer(Modifier.height(14.dp))
                        Text("ANY DISTANCE, ONLINE", color = Color(230, 205, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        GoldButton("HOST A ROOM", onClick = gate(onHostOnline))
                        GoldButton("JOIN WITH A CODE", onClick = gate(onJoinOnline))
            }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color(30, 22, 8)).border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                    .clickable { onBack() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("◀ BACK", color = Color(255, 240, 200), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

class P2pMatchViewModel(
    private val session: P2pSession, initialState: GameState, initialColor: Owner,
    private val onTransfers: (List<triad.core.CardTransfer>, Owner) -> Unit = { _, _ -> },
) {
    var state by mutableStateOf(initialState); private set
    var myColor by mutableStateOf(initialColor); private set
    var lastFlips by mutableStateOf<List<triad.core.Flip>>(emptyList()); private set
    var result by mutableStateOf<ResultState?>(null); private set
    var ended by mutableStateOf<String?>(null); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            session.eventsFlow.collect { e ->
                when (e) {
                    is SessionEvent.MatchStarted -> { state = e.state; myColor = e.myColor }
                    is SessionEvent.MoveApplied -> { state = e.state; lastFlips = e.flips }
                    // the rematch re-coins colors (both sides default to ColorPref.any),
                    // so the fresh myColor must replace the stale one or the UI plays
                    // the wrong hand
                    is SessionEvent.RematchStarted -> {
                        state = e.state; myColor = e.myColor; result = null; lastFlips = emptyList()
                    }
                    is SessionEvent.GameOver -> result = ResultState(outcome = e.outcome)
                    is SessionEvent.Forfeit -> ended = if (e.winner == myColor) {
                        if (e.reason.contains("resign")) "WIN BY RESIGN" else "WIN BY DISCONNECT"
                    } else e.reason
                    is SessionEvent.Declined -> ended = e.detail
                    is SessionEvent.Disconnected -> ended = e.detail
                    is SessionEvent.ProtocolError -> ended = "${e.code}: ${e.detail}"
                    else -> {}
                }
            }
        }
    }

    val myTurn: Boolean get() = state?.let { !it.isOver && it.turn == myColor } == true

    fun play(cardIndex: Int, cell: Int) = session.play(cardIndex, cell)
    fun rematch() = session.rematch()
    fun resign() = session.resign()
    fun done() = session.teardown()
    fun dispose() = scope.cancel()
}

@Composable
fun P2pMatchScreen(
    session: P2pSession,
    catalog: Map<String, CardDef>,
    initialState: GameState,
    initialColor: Owner,
    onTransfers: (List<triad.core.CardTransfer>, Owner) -> Unit = { _, _ -> },
    onExit: () -> Unit,
) {
    val vm = remember { P2pMatchViewModel(session, initialState, initialColor, onTransfers) }
    DisposableEffect(vm) { onDispose { vm.dispose() } }

    val state = vm.state ?: return
    var selected by remember { mutableStateOf<Int?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var infoCard by remember { mutableStateOf<Pair<CardDef, Owner>?>(null) }

    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.14f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmExit = true }) {
                    Text("Menu", color = Color(255, 250, 235), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                ScorePill(red = state.score(Owner.red), blue = state.score(Owner.blue))
            }

            HandRow(state.hand(vm.myColor.other), owner = vm.myColor.other,
                      selected = null, active = false,
                      onTap = {},
                      onInfo = { idx -> state.hand(vm.myColor.other).getOrNull(idx)?.let {
                          infoCard = it to vm.myColor.other } })

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val (pillText, pillColor) = when {
                    state.isOver -> "MATCH OVER" to Color(70, 60, 40)
                    vm.myTurn -> "YOUR TURN" to FrameBlue
                    else -> "OPPONENT'S TURN" to Color(70, 60, 40)
                }
                Box(Modifier.clip(RoundedCornerShape(11.dp)).background(pillColor)
                        .padding(horizontal = 14.dp, vertical = 5.dp)) {
                    Text(pillText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoardCells(state = state, lastFlips = vm.lastFlips,
                           selectActive = selected != null && vm.myTurn,
                           onTapCell = { cell ->
                               val sel = selected
                               if (sel != null && vm.myTurn) {
                                   selected = null
                                   vm.play(sel, cell)
                               }
                           },
                           onInfo = { def, owner -> infoCard = def to owner },
                           modifier = Modifier.fillMaxHeight().aspectRatio(1f))
            }

            HandRow(state.hand(vm.myColor), owner = vm.myColor,
                      selected = if (vm.myTurn) selected else null,
                      active = vm.myTurn,
                      onTap = { idx -> if (vm.myTurn) selected = if (selected == idx) null else idx },
                      onInfo = { idx -> state.hand(vm.myColor).getOrNull(idx)?.let {
                          infoCard = it to vm.myColor } })
        }

        var viewBoard by remember { mutableStateOf(false) }

        LaunchedEffect(vm.result) { viewBoard = false }
        when {
            vm.ended != null -> WaitingPane(vm.ended!!) { vm.done(); onExit() }
            vm.result != null && !viewBoard -> EndOfMatchHost(
                result = vm.result!!,
                peerName = session.peerName,
                myColor = vm.myColor,
                onDone = { vm.done(); onExit() },
                onRematch = vm::rematch,
                onViewBoard = { viewBoard = true },
            )
        }
        if (vm.result != null && viewBoard && vm.ended == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(22, 15, 6).copy(alpha = 0.88f))
                        .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                        .clickable { viewBoard = false }
                        .padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text("◀  BACK TO RESULT", color = Color(255, 240, 200), fontWeight = FontWeight.Bold)
                }
            }
        }

        infoCard?.let { (def, owner) -> CardInfoDialog(def = def, owner = owner) { infoCard = null } }
        if (confirmExit && vm.result == null && vm.ended == null) {
            LeaveMatchDialog(
                onLeave = { vm.resign(); onExit() },
                onStay = { confirmExit = false },
                warning = "Leaving now counts as a resignation, your opponent will be awarded the win.",
            )
        }
    }
}
