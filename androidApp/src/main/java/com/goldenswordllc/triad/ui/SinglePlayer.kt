package com.goldenswordllc.triad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.*
import triad.core.*

val FrameBlue = Color(0xFF1565C0)
private val FrameRed = Color(0xFFB71C1C)
private val Gold = Color(0xFFD4AF37)
private val GoldText = Color(0xFFFFF0C8)
private val SlotFill = Color(40, 28, 12).copy(alpha = 0.55f)
private val SlotOutline = Color(120, 95, 50)
private val CREAM = Color(245, 238, 220)

@Composable
fun MenuScreen(record: PlayerSave, hasValidDeck: Boolean, onStart: (String) -> Unit, onTutorial: () -> Unit,
               onSettings: () -> Unit, onDecks: () -> Unit, onMultiplayer: () -> Unit) {

    val prefs = LocalContext.current.getSharedPreferences("triad", android.content.Context.MODE_PRIVATE)
    var difficulty by remember { mutableStateOf(prefs.getString("difficulty", "easy") ?: "easy") }

    val expertUnlocked = prefs.getInt("hardWins", 0) >= 3
    var askForDeck by remember { mutableStateOf(false) }
    if (askForDeck && !hasValidDeck) {
        NoDeckDialog(
            onBuild = { askForDeck = false; onDecks() },
            onPlayAnyway = { askForDeck = false; onStart(difficulty) },
            onDismiss = { askForDeck = false },
        )
    }
    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.24f, bottomScrim = true)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(0.9f))

            CardFan()

            Text("TRIAD", color = GoldText, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                 style = LocalTextStyle.current.copy(
                     shadow = androidx.compose.ui.graphics.Shadow(
                         color = Color(80, 55, 10), blurRadius = 8f,
                         offset = androidx.compose.ui.geometry.Offset(2f, 2f))))

            Text("a Triple Triad fan game", color = Color(245, 238, 220), fontSize = 12.sp,
                 modifier = Modifier.clip(RoundedCornerShape(8.dp))
                     .background(Color(22, 15, 6).copy(alpha = 0.55f))
                     .padding(horizontal = 10.dp, vertical = 2.dp))
            Spacer(Modifier.height(18.dp))

            Text("SINGLE PLAYER", color = Color(230, 205, 160), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("easy", "medium", "hard") + if (expertUnlocked) listOf("expert") else emptyList()).forEach { diff ->
                    val sel = difficulty == diff

                    Box(Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Gold else Color(30, 22, 8).copy(alpha = 0.85f))
                        .border(
                            if (sel) 2.dp else 1.dp,
                            if (sel) Gold else Gold.copy(alpha = 0.85f),
                            RoundedCornerShape(10.dp))
                        .clickable {
                            difficulty = diff
                            prefs.edit().putString("difficulty", diff).apply()
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text(diff.uppercase(),
                             color = if (sel) Color(55, 38, 10) else GoldText,
                             fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (!expertUnlocked) {
                Text("Beat HARD 3 times to unlock EXPERT", color = Color(190, 175, 145), fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            GoldButton("PLAY  ▶", filled = true) {
                if (hasValidDeck) onStart(difficulty) else askForDeck = true
            }
            GoldButton("DECKS", sub = "manage your collection") { onDecks() }
            GoldButton("MULTIPLAYER", sub = "pass & play · host · join") { onMultiplayer() }

            Spacer(Modifier.height(10.dp))
            Row {
                TextButton(onTutorial) {
                    Text("HOW TO PLAY", color = Color(230, 205, 160), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onSettings) {
                    Text("SETTINGS", color = Color(230, 205, 160), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.weight(1f))
            Text("W ${record.wins}  ·  L ${record.losses}",
                 color = Color(200, 180, 140), fontSize = 10.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}

@Composable
private fun CardFan() {

    val fan = remember {
        (1..110).shuffled().take(3).mapIndexed { i, no ->
            Triple(no, if ((0..1).random() == 0) Owner.blue else Owner.red, i)
        }
    }
    val offsets = listOf(-12.dp to -8f, 14.dp to 12f, 0.dp to -2f)
    val w = 100.dp
    val h = w / CardSheets.ASPECT
    Box(Modifier.fillMaxWidth().height(h + 10.dp), contentAlignment = Alignment.Center) {
        fan.forEach { (no, owner, slot) ->
            val (dx, rot) = offsets[slot]
            CardArt(no, owner, Modifier.align(Alignment.Center).offset(x = dx).rotate(rot).size(w, h))
        }
    }
}

@Composable
fun GoldButton(text: String, filled: Boolean = false, enabled: Boolean = true,
                       sub: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        !enabled -> Color.Transparent
        filled -> Color(120, 85, 20)

        else -> Color(30, 22, 8).copy(alpha = 0.78f)
    }

    val outline = if (enabled) Gold else Color(150, 130, 90).copy(alpha = 0.35f)
    val label = if (enabled) GoldText else Color(190, 175, 145).copy(alpha = 0.45f)
    Box(Modifier
        .padding(horizontal = 36.dp, vertical = 6.dp)
        .fillMaxWidth()
        .clip(shape)
        .background(bg)
        .border(if (filled) 2.dp else 1.dp, outline, shape)
        .clickable(enabled = enabled) { onClick() }
        .padding(vertical = if (sub == null) 13.dp else 8.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = label.copy(alpha = 0.8f), fontSize = 9.sp)
        }
    }
}

class SPGameViewModel(
    private val catalog: Map<String, CardDef>,
    private val difficulty: String,
    private val myDeck: List<CardDef>,
    private val redDeck: List<CardDef>? = null,
    private val threats: Set<String> = emptySet(),
    private val beatenCards: List<CardDef> = emptyList(),
    private val aiBudgetMs: Long = 900L,
    private val onRecord: (won: Boolean, drawn: Boolean) -> Unit,
    private val onMemory: (handIds: List<String>, won: Boolean) -> Unit = { _, _ -> },
) {

    private val aiRange: IntRange = when (difficulty) {
        "easy" -> 1..3
        "hard" -> 3..10
        "expert" -> 8..10
        "pass" -> 1..5
        else -> 1..6
    }

    var passAndPlay: Boolean = difficulty == "pass"
        private set
    var generation by mutableStateOf(0); private set
    var coinText by mutableStateOf<String?>(null); private set
    var state by mutableStateOf(newGame()); private set
    var lastFlips by mutableStateOf<List<Flip>>(emptyList()); private set
    var selected by mutableStateOf<Int?>(null); private set
    var aiThinking by mutableStateOf(false); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorded = false
    private var aiJob: Job? = null

    init {
        maybeAiTurn()

        scope.launch {
            while (isActive) {
                delay(500)
                if (!passAndPlay && !state.isOver && state.turn == Owner.red && !aiThinking) maybeAiTurn()
            }
        }
    }

    private fun newGame(): GameState {
        val byLevel = catalog.values.groupBy { it.level }
        fun sample(range: IntRange): List<CardDef> = List(5) { byLevel.getValue(range.random()).random() }
        val turn = if ((0..1).random() == 0) Owner.blue else Owner.red
        coinText = if (passAndPlay)
            (if (turn == Owner.blue) "BLUE GOES FIRST" else "RED GOES FIRST")
        else null
        generation++
        return GameState(
            board = Board(List(9) { Cell() }),
            blueHand = myDeck.shuffled(),
            redHand = when {
                difficulty == "expert" -> counterDeck()
                redDeck != null -> redDeck.shuffled()
                else -> sample(aiRange)
            },
            turn = turn,
            rules = Rules(),
        )
    }

    private fun counterDeck(): List<CardDef> {
        val sides = Side.entries
        fun opp(s: Side) = sides[(s.ordinal + 2) % 4]
        val pool = catalog.values.filter { it.level >= 8 }.toMutableList()
        return myDeck.map { p ->
            val pick = pool.maxByOrNull { c ->
                sides.count { s -> c.rank(s) > p.rank(s) } * 100 +
                    sides.count { s -> c.rank(s) >= p.rank(opp(s)) } * 40 +
                    sides.sumOf { s -> c.rank(s) - p.rank(s) } +
                    beatenCards.sumOf { b ->
                        sides.count { s -> c.rank(s) > b.rank(opp(s)) } * 60
                    }
            } ?: catalog.values.random()
            pool.remove(pick)
            pick
        }
    }

    fun rematch() {
        recorded = false
        aiJob?.cancel()
        aiThinking = false; lastFlips = emptyList(); selected = null
        state = newGame()
        maybeAiTurn()
    }

    fun tapCard(index: Int) {
        if (state.isOver || aiThinking) return
        selected = if (selected == index) null else index
    }

    fun tapCell(cell: Int) {
        val sel = selected ?: return
        if (state.isOver || aiThinking || state.board[cell].card != null) return
        if (!passAndPlay && state.turn != Owner.blue) return
        if (sel !in state.hand(state.turn).indices) return
        selected = null
        apply(sel, cell)
    }

    private fun apply(cardIndex: Int, cell: Int) {
        val r = Engine.place(state, cardIndex, cell)
        state = r.state; lastFlips = r.flips
        if (state.isOver) finish() else maybeAiTurn()
    }

    private fun maybeAiTurn() {
        if (passAndPlay || state.isOver || state.turn != Owner.red || aiThinking) return
        aiThinking = true
        val gen = generation
        val snapshot = state
        aiJob = scope.launch {
            try {
                delay(450)
                val move = try {
                    when (difficulty) {
                        "easy" -> Ai.easy(snapshot)
                        "hard" -> Ai.hard(snapshot, budgetMs = 900)

                        "expert" -> Ai.hard(snapshot, budgetMs = aiBudgetMs, threats = threats)
                        else -> Ai.medium(snapshot)
                    }
                } catch (e: Exception) {
                    Ai.easy(snapshot)
                }
                if (gen != generation) return@launch
                val r = Engine.place(snapshot, move.cardIndex, move.cellIndex)
                if (gen != generation) return@launch
                state = r.state; lastFlips = r.flips
                if (state.isOver) finish()
            } finally {

                if (gen == generation) aiThinking = false
            }
        }
    }

    private fun finish() {
        if (recorded) return
        recorded = true
        val blue = state.score(Owner.blue); val red = state.score(Owner.red)
        val won = blue > red
        onRecord(won, blue == red)

        onMemory(myDeck.map { it.id }, won)
    }

    fun concede() {
        if (recorded || state.isOver) return
        recorded = true
        onRecord(false, false)
        onMemory(myDeck.map { it.id }, false)
    }

    fun dispose() { scope.cancel() }
}

@Composable
fun SinglePlayerScreen(
    catalog: Map<String, CardDef>,
    difficulty: String,
    myDeck: List<CardDef>,
    redDeck: List<CardDef>? = null,
    threats: Set<String> = emptySet(),
    beatenCards: List<CardDef> = emptyList(),
    aiBudgetMs: Long = 900L,
    onRecord: (won: Boolean, drawn: Boolean) -> Unit,
    onMemory: (handIds: List<String>, won: Boolean) -> Unit = { _, _ -> },
    pendingPack: List<CardDef> = emptyList(),
    onPackPending: (List<CardDef>) -> Unit = {},
    onClaimPendingPack: () -> Unit = {},
    onExit: () -> Unit,
) {

    val sittingThreats = remember { threats }
    val sittingBeaten = remember { beatenCards }
    val sittingBudget = remember { aiBudgetMs }
    val vm = remember(catalog, difficulty, myDeck, redDeck) {
        SPGameViewModel(catalog, difficulty, myDeck, redDeck, sittingThreats, sittingBeaten, sittingBudget,
                        { won, drawn -> onRecord(won, drawn) }, onMemory)
    }
    var packClaimed by remember(vm.generation) { mutableStateOf(false) }
    var rolledFor by remember { mutableStateOf(-1) }
    var showPack by remember { mutableStateOf(false) }
    var viewBoard by remember { mutableStateOf(false) }

    DisposableEffect(vm) { onDispose { vm.dispose() } }

    val state = vm.state
    var confirmExit by remember { mutableStateOf(false) }
    var gameMenuOpen by remember { mutableStateOf(false) }
    var coinPhase by remember { mutableStateOf<Int?>(null) }
    var infoCard by remember { mutableStateOf<Pair<CardDef, Owner>?>(null) }

    val boardRotation by animateFloatAsState(
        targetValue = if (vm.passAndPlay && state.turn == Owner.red) 180f else 0f,
        label = "boardFlip")

    LaunchedEffect(state.isOver, vm.generation) {
        if (state.isOver && !vm.passAndPlay &&
            state.score(Owner.blue) > state.score(Owner.red) && rolledFor != vm.generation) {
            rolledFor = vm.generation
            onPackPending(rollPack(difficulty, catalog.values, kotlin.random.Random.Default))
        }
    }
    Box(Modifier.fillMaxSize()) {
        MatBackground(Modifier.matchParentSize(), dim = 0.14f)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { gameMenuOpen = true }) {
                    Text("Menu", color = Color(255, 250, 235), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                ScorePill(red = state.score(Owner.red), blue = state.score(Owner.blue),
                          rotate = vm.passAndPlay && state.turn == Owner.red)
            }

            HandRow(state.hand(Owner.red), owner = Owner.red,
                      selected = if (state.turn == Owner.red) vm.selected else null,
                      active = vm.passAndPlay && !state.isOver,
                      flipped = vm.passAndPlay,
                      onTap = { idx -> if (vm.passAndPlay && state.turn == Owner.red) vm.tapCard(idx) },
                      onInfo = { idx -> state.hand(Owner.red).getOrNull(idx)?.let {
                          infoCard = it to Owner.red } })

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val (pillText, pillColor) = when {
                    state.isOver -> "MATCH OVER" to Color(70, 60, 40)
                    vm.passAndPlay -> if (state.turn == Owner.blue) "BLUE'S TURN" to FrameBlue
                                      else "RED'S TURN" to FrameRed
                    vm.aiThinking -> "AI IS THINKING…" to Color(70, 60, 40)
                    state.turn == Owner.blue -> "YOUR TURN" to FrameBlue
                    else -> "OPPONENT'S TURN" to Color(70, 60, 40)
                }
                Box(Modifier
                        .graphicsLayer {
                            rotationZ = if (vm.passAndPlay && state.turn == Owner.red) 180f else 0f
                        }
                        .clip(RoundedCornerShape(11.dp)).background(pillColor)
                        .padding(horizontal = 14.dp, vertical = 5.dp)) {
                    Text(pillText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {

                BoardCells(state = state, lastFlips = vm.lastFlips,
                           selectActive = vm.selected != null,
                           onTapCell = { vm.tapCell(it) },
                           onInfo = { def, owner -> infoCard = def to owner },
                           onCardTap = null,
                           modifier = Modifier.fillMaxHeight().aspectRatio(1f)
                               .graphicsLayer { rotationZ = boardRotation })
            }

            HandRow(state.hand(Owner.blue), owner = Owner.blue,
                      selected = if (state.turn == Owner.blue) vm.selected else null,
                      active = !state.isOver && (vm.passAndPlay || state.turn == Owner.blue),
                      onTap = { idx -> if (state.turn == Owner.blue) vm.tapCard(idx) },
                      onInfo = { idx -> state.hand(Owner.blue).getOrNull(idx)?.let {
                          infoCard = it to Owner.blue } })

        }

        if (state.isOver && !viewBoard) {
            val blue = state.score(Owner.blue); val red = state.score(Owner.red)
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0, 0, 0, 140))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {  },
                contentAlignment = Alignment.Center,
            ) {
                val (label, color) = when {
                    vm.passAndPlay -> (if (blue > red) "BLUE WINS" else "RED WINS") to
                            (if (blue > red) Color(21, 101, 192) else Color(183, 28, 28))
                    blue > red -> "VICTORY" to Color(46, 125, 50)
                    else -> "DEFEAT" to Color(183, 28, 28)
                }
                Column(
                    Modifier
                        .padding(horizontal = 32.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(30, 22, 8))
                        .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("$blue - $red", color = Color(230, 205, 160), fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    if (blue > red && difficulty != "pass") {
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (packClaimed) Color(40, 30, 14) else Color(46, 125, 50))
                                .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                                .clickable(enabled = !packClaimed && pendingPack.isNotEmpty()) {
                                    showPack = true
                                }.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (packClaimed) "PACK CLAIMED" else "OPEN PACK  ▶",
                                 color = Color(255, 240, 200), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(120, 85, 20))
                            .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                            .clickable { vm.rematch() }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("REMATCH", color = Color(255, 240, 200), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(30, 22, 8))
                            .border(2.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                            .clickable { onExit() }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("MENU", color = Color(255, 240, 200), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Color(30, 22, 8))
                            .border(1.dp, Color(0xFFD4AF37), RoundedCornerShape(12.dp))
                            .clickable { viewBoard = true }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text("VIEW BOARD", color = Color(230, 205, 160), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (state.isOver && viewBoard) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(22, 15, 6).copy(alpha = 0.88f))
                        .border(1.dp, Gold, RoundedCornerShape(12.dp))
                        .clickable { viewBoard = false }
                        .padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text("◀  BACK TO RESULT", color = GoldText, fontWeight = FontWeight.Bold)
                }
            }
        }

        infoCard?.let { (def, owner) -> CardInfoDialog(def = def, owner = owner) { infoCard = null } }

        coinPhase?.let { phase ->
            Box(Modifier.fillMaxSize()
                    .background(Color(0, 0, 0, 120))
                    .clickable(indication = null,
                               interactionSource = remember { MutableInteractionSource() }) {},
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(210.dp).clip(CircleShape)
                            .background(Color(25, 32, 62))
                            .border(6.dp, Gold, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (phase == 0) {
                            Text("FLIPPING", color = GoldText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("◆", color = CREAM, fontSize = 26.sp,
                                 modifier = Modifier.align(Alignment.Center).padding(top = 40.dp))
                        } else {
                            Text(vm.coinText ?: "", color = Color(120, 190, 255), fontSize = 22.sp,
                                 fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        if (gameMenuOpen) {
            Box(Modifier.fillMaxSize().background(Color(0, 0, 0, 110))
                    .clickable(indication = null,
                               interactionSource = remember { MutableInteractionSource() }) { gameMenuOpen = false },
                contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(30, 22, 8))
                        .border(2.dp, Gold, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GAME MENU", color = Color(230, 205, 160), fontSize = 15.sp,
                         fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                         modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    GameMenuItem("▶  Continue") { gameMenuOpen = false }
                    GameMenuItem("↻  Restart") { gameMenuOpen = false; vm.rematch() }
                    GameMenuItem("⇤  Concede & Exit", destructive = true) { gameMenuOpen = false; vm.concede(); onExit() }
                }
            }
        }

        if (showPack && pendingPack.isNotEmpty()) {
            PackScreen(cards = pendingPack) {
                onClaimPendingPack()
                packClaimed = true
                showPack = false
            }
        }
        if (confirmExit) {
            LeaveMatchDialog(
                onLeave = onExit,
                onStay = { confirmExit = false },
                warning = "Your progress in this game will be lost.",
            )
        }
    }
}

@Composable
fun ScorePill(red: Int, blue: Int, rotate: Boolean = false) {
    Row(Modifier
        .graphicsLayer { rotationZ = if (rotate) 180f else 0f }
        .clip(RoundedCornerShape(12.dp)).background(Color(30, 22, 8))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RED $red", color = Color(255, 120, 100), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("·", color = Color(230, 210, 170), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("$blue BLUE", color = Color(120, 190, 255), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HandRow(hand: List<CardDef>, owner: Owner, selected: Int?, active: Boolean,
            onTap: (Int) -> Unit, onInfo: (Int) -> Unit = {}, flipped: Boolean = false) {

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            hand.forEachIndexed { i, def ->
                val isSel = selected == i && active
                Box(
                    Modifier
                        .width(96.dp)
                        .aspectRatio(CardSheets.ASPECT)
                        .graphicsLayer {
                            translationY = if (isSel) -12.dp.toPx() else 0f
                            rotationZ = if (flipped) 180f else 0f
                        }
                        .zIndex(if (isSel) 1f else 0f)
                ) {
                    CardThumb(def, conflict = false, selected = isSel, owner = owner,
                              modifier = Modifier.fillMaxSize().combinedClickable(
                                  onClick = { onTap(i) },
                                  onLongClick = { onInfo(i) }))
                }
            }
        }
        val canScrollForward by remember {
            derivedStateOf { scroll.maxValue > 0 && scroll.value < scroll.maxValue }
        }
        val canScrollBack by remember {
            derivedStateOf { scroll.value > 0 }
        }
        if (canScrollBack) {
            Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier
                     .align(Alignment.CenterStart)
                     .padding(start = 2.dp)
                     .background(Color(0, 0, 0, 90), RoundedCornerShape(8.dp))
                     .padding(horizontal = 5.dp))
        }
        if (canScrollForward) {
            Text("›", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier
                     .align(Alignment.CenterEnd)
                     .padding(end = 2.dp)
                     .background(Color(0, 0, 0, 90), RoundedCornerShape(8.dp))
                     .padding(horizontal = 5.dp))
        }
    }
}

@Composable
fun BoardCells(state: GameState, lastFlips: List<Flip>, selectActive: Boolean,
                       onTapCell: (Int) -> Unit, onInfo: (CardDef, Owner) -> Unit = { _, _ -> },
                       onCardTap: (() -> Unit)? = null,
                       modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp),
           horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0..2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0..2) {
                    val idx = row * 3 + col
                    val cell = state.board[idx]
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (cell.card == null) {
                            val outline = when {
                                selectActive -> Gold
                                else -> SlotOutline
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize().clickable { onTapCell(idx) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, outline),
                                color = SlotFill,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (cell.element != Element.none) {
                                        Text("◆", color = elementColor(cell.element),
                                             style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        } else {
                            BoardCell(idx, cell, lastFlips, onInfo, onCardTap)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameMenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (destructive) Color(70, 25, 18) else Color(46, 36, 18))
            .border(1.dp, if (destructive) Color(183, 28, 28) else Color(0xFFD4AF37), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (destructive) Color(255, 150, 130) else Color(255, 240, 200),
             fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
