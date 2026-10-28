package com.goldenswordllc.triad

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import triad.core.CardDef
import triad.core.GameState
import triad.core.Ai
import triad.core.activeDeckCards
import triad.core.addCards
import triad.core.applyTransfers
import triad.core.expertThreats
import triad.core.withGameMemory
import triad.core.Owner
import triad.core.PlayerSave
import triad.core.withRecord
import triad.net.P2pSession
import com.goldenswordllc.triad.ui.CardSheets
import com.goldenswordllc.triad.ui.MatCache
import com.goldenswordllc.triad.ui.MatBackground
import com.goldenswordllc.triad.ui.PackScreen
import com.goldenswordllc.triad.ui.CollectionScreen
import com.goldenswordllc.triad.ui.HostLobbyScreen
import com.goldenswordllc.triad.ui.JoinFlowScreen
import com.goldenswordllc.triad.ui.CreditsScreen
import com.goldenswordllc.triad.ui.PassRedDeckScreen
import com.goldenswordllc.triad.ui.OnlineHostScreen
import com.goldenswordllc.triad.ui.OnlineJoinScreen
import com.goldenswordllc.triad.ui.parseRelayAddr
import com.goldenswordllc.triad.ui.DeckManagerScreen
import com.goldenswordllc.triad.ui.MenuScreen
import com.goldenswordllc.triad.ui.MultiplayerScreen
import com.goldenswordllc.triad.ui.P2pMatchScreen
import com.goldenswordllc.triad.ui.SettingsScreen
import com.goldenswordllc.triad.ui.SinglePlayerScreen
import com.goldenswordllc.triad.ui.TutorialScreen

@Serializable
private data class CatalogFile(val cards: List<CardDef>)

private val catalogJson = Json { ignoreUnknownKeys = true }

fun loadCatalog(context: Context): Map<String, CardDef> =
    catalogJson.decodeFromString(
        CatalogFile.serializer(),
        context.assets.open("cards.json").bufferedReader().use { it.readText() },
    ).cards.associateBy { it.id }

private fun battleDeck(save: triad.core.PlayerSave, catalog: Map<String, CardDef>): List<CardDef> {
    val active = save.activeDeckCards()
    val activeOwned = triad.core.validateDeck(
        active, catalog.keys, save.collection, false, emptySet()).isEmpty()
    if (activeOwned && active.size == 5) return active.map { catalog.getValue(it) }
    val ownedDistinct = save.collection.filterValues { it > 0 }.keys.mapNotNull { catalog[it] }
    if (ownedDistinct.size >= 5) return ownedDistinct.shuffled().take(5)
    val lowLevels = catalog.values.filter { it.level in 1..5 }
    return lowLevels.shuffled().take(5)
}

private fun redPassDeck(save: triad.core.PlayerSave, blue: List<CardDef>, catalog: Map<String, CardDef>): List<CardDef> {
    val used = blue.groupingBy { it.id }.eachCount()
    val pool = save.collection.mapNotNull { (id, n) ->
        if (n - (used[id] ?: 0) > 0) catalog[id] else null
    }
    val picked = pool.shuffled().take(5).toMutableList()
    val lowLevels = catalog.values.filter { it.level in 1..5 }
    while (picked.size < 5) {
        val next = lowLevels.filter { l -> picked.none { it.id == l.id } }.randomOrNull() ?: break
        picked += next
    }
    return picked
}

private val STARTER_COLLECTION = listOf(
    "geezard", "funguar", "bitebug", "redbat", "blobra", "gayla", "gesper",
    "fastitocalonf", "bloodsoul", "caterchipillar", "cockatrice", "caterchipillar",
)

private fun ensureStarterCollection(save: triad.core.PlayerSave, catalog: Map<String, CardDef>) =
    if (save.collection.any { it.value > 0 }) save
    else save.addCards(STARTER_COLLECTION.mapNotNull { catalog[it] })

private sealed interface Screen {
    data object Menu : Screen
    data object Tutorial : Screen
    data object Settings : Screen
    data object Credits : Screen
    data object Decks : Screen
    data object Multiplayer : Screen
    data class Game(val difficulty: String, val redDeck: List<CardDef>? = null) : Screen
    data class PassRedPick(val blueDeck: List<CardDef>) : Screen
    data object Collection : Screen
    data object PackClaim : Screen
    data object OnlineHost : Screen
    data object OnlineJoin : Screen
    data object Host : Screen
    data object Join : Screen
    data class P2pMatch(val session: P2pSession, val state: GameState, val myColor: Owner) : Screen
}

class MainActivity : ComponentActivity() {
    private fun readLastCrash(): String? = runCatching {
        java.io.File(filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.take(900)
    }.getOrNull()

    private fun saveErrorToFile() {
        val out = java.io.File(
            java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath),
            "triad-diagnostics.txt")
        runCatching {
            out.parentFile?.mkdirs()
            out.writeText(readLastCrash() ?: "No crashes recorded.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    "${e::class.java.name}: ${e.message ?: ""}\n\n" + e.stackTraceToString())
            }
            priorHandler?.uncaughtException(thread, e)
        }
        val catalog = loadCatalog(this)
        setContent {
            MaterialTheme {
                LaunchedEffect(Unit) { MatCache.load(this@MainActivity) }
                val save = remember {
                    var s = ensureStarterCollection(loadSave(this@MainActivity), catalog)
                    val prefs = getSharedPreferences("triad", MODE_PRIVATE)
                    val marker = prefs.getString("spActive", null)
                    if (marker != null) {
                        val diff = marker.substringBefore("|", "easy")
                        val hand = marker.substringAfter("|", "").split(",").filter { it.isNotEmpty() }
                        s = s.withRecord(false, false).withGameMemory(hand, false, diff == "expert")
                        persist(this@MainActivity, s)
                        prefs.edit().remove("spActive").apply()
                    }
                    mutableStateOf(s)
                }

                val activeIds = save.value.activeDeckCards()
                val hasValidDeck = activeIds.size == 5 && triad.core.validateDeck(
                    activeIds, catalog.keys, save.value.collection, false, emptySet()).isEmpty()
                var p2p by remember { mutableStateOf<Triple<P2pSession, GameState, Owner>?>(null) }
                var screen by remember {
                    mutableStateOf<Screen>(if (save.value.tutorialSeen) Screen.Menu else Screen.Tutorial)
                }

                var packGate by remember { mutableStateOf<(() -> Unit)?>(null) }
                fun gatePack(navigate: () -> Unit) {
                    if (save.value.pendingPack.isEmpty()) navigate() else packGate = navigate
                }
                if (packGate != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(30, 22, 8),
                        title = { Text("You have a pack to open", color = Color(255, 240, 200), fontWeight = FontWeight.Bold) },
                        text = { Text("Your last win earned a pack that's still waiting. Open it now, or forfeit it and play on without it.",
                                      color = Color(245, 238, 220)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                packGate = null
                                screen = Screen.PackClaim
                            }) { Text("OPEN PACK", color = Color(255, 200, 100), fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                save.value = save.value.copy(pendingPack = emptyList())
                                persist(this@MainActivity, save.value)
                                val go = packGate; packGate = null; go?.invoke()
                            }) { Text("FORFEIT & PLAY", color = Color(255, 150, 130), fontWeight = FontWeight.Bold) }
                        },
                    )
                }
                when (val s = screen) {
                    Screen.Tutorial ->
                        TutorialScreen(onDone = {
                            save.value = save.value.copy(tutorialSeen = true)
                            persist(this@MainActivity, save.value)
                            screen = Screen.Menu
                        })
                    Screen.Menu ->
                        MenuScreen(
                            record = save.value,
                            hasValidDeck = hasValidDeck,
                            onStart = { tier -> gatePack { screen = Screen.Game(tier) } },
                            onTutorial = { screen = Screen.Tutorial },
                            onSettings = { screen = Screen.Settings },
                            onDecks = { screen = Screen.Decks },
                            onMultiplayer = { screen = Screen.Multiplayer },
                        )
                    Screen.OnlineHost -> {
                        val addr = parseRelayAddr(getSharedPreferences("triad", MODE_PRIVATE).getString("relay", "") ?: "")!!
                        OnlineHostScreen(
                            relayHost = addr.first, relayPort = addr.second,
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            cardCount = save.value.collection.values.sum(),
                            onMatchStarted = { session, state, color ->
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.OnlineJoin -> {
                        val addr = parseRelayAddr(getSharedPreferences("triad", MODE_PRIVATE).getString("relay", "") ?: "")!!
                        OnlineJoinScreen(
                            relayHost = addr.first, relayPort = addr.second,
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            onMatchStarted = { session, state, color ->
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.Multiplayer ->
                        MultiplayerScreen(
                            cardCount = save.value.collection.values.sum(),
                            hasActiveDeck = hasValidDeck,
                            onDecks = { screen = Screen.Decks },
                            onPassPlay = { gatePack { screen = Screen.PassRedPick(battleDeck(save.value, catalog)) } },
                            onHost = { gatePack { screen = Screen.Host } },
                            onJoin = { gatePack { screen = Screen.Join } },
                            relayConfigured = parseRelayAddr(getSharedPreferences("triad", MODE_PRIVATE).getString("relay", "") ?: "") != null,
                            onHostOnline = { gatePack { screen = Screen.OnlineHost } },
                            onJoinOnline = { gatePack { screen = Screen.OnlineJoin } },
                            onBack = { screen = Screen.Menu },
                        )
                    Screen.Decks ->
                        DeckManagerScreen(
                            catalog = catalog,
                            save = save.value,
                            onUpdate = {
                                save.value = it
                                persist(this@MainActivity, save.value)
                            },
                            onDone = { screen = Screen.Menu },
                            onViewCollection = { screen = Screen.Collection },
                        )
                    is Screen.PassRedPick -> {
                        val blue = s.blueDeck
                        val pool = remember(s) {
                            save.value.collection.filterValues { it > 0 }.keys
                                .mapNotNull { catalog[it] }
                                .filter { c -> blue.none { it.id == c.id } }
                                .sortedWith(compareBy({ it.level }, { it.no }))
                        }
                        PassRedDeckScreen(
                            pool = pool,
                            onReady = { red -> screen = Screen.Game("pass", redDeck = red) },
                            onBack = { screen = Screen.Multiplayer },
                        )
                    }
                    Screen.Collection ->
                        CollectionScreen(catalog = catalog, save = save.value, onBack = { screen = Screen.Decks })
                    Screen.PackClaim -> {
                        val cards = save.value.pendingPack.mapNotNull { catalog[it] }
                        if (cards.isEmpty()) {
                            screen = Screen.Menu
                        } else {
                            Box(Modifier.fillMaxSize()) {
                                MatBackground(Modifier.matchParentSize(), dim = 0.3f)
                                PackScreen(cards = cards) {
                                    save.value = save.value.addCards(cards).copy(pendingPack = emptyList())
                                    persist(this@MainActivity, save.value)
                                    screen = Screen.Menu
                                }
                            }
                        }
                    }
                    Screen.Settings ->
                        SettingsScreen(
                            record = save.value,
                            lastCrash = readLastCrash(),
                            onWipeRecord = {
                                save.value = save.value.copy(wins = 0, losses = 0, draws = 0)
                                persist(this@MainActivity, save.value)
                            },
                            onSaveErrorToFile = { saveErrorToFile() },
                            onCredits = { screen = Screen.Credits },
                            onBackToMain = { screen = Screen.Menu },
                        )
                    Screen.Credits ->
                        CreditsScreen(catalog = catalog, onBack = { screen = Screen.Settings })
                    is Screen.Game -> {

                        val blueDeck = remember { battleDeck(save.value, catalog) }
                        if (s.difficulty != "pass") {
                            LaunchedEffect(s) {
                                getSharedPreferences("triad", MODE_PRIVATE).edit()
                                    .putString("spActive", s.difficulty + "|" + blueDeck.joinToString(",") { it.id })
                                    .apply()
                            }
                        }
                        val redDeck = remember(blueDeck, s.redDeck) { s.redDeck }
                        val pendingCards = remember(save.value.pendingPack) {
                            save.value.pendingPack.mapNotNull { catalog[it] }
                        }
                        SinglePlayerScreen(
                            catalog = catalog,
                            difficulty = s.difficulty,
                            myDeck = blueDeck,
                            redDeck = redDeck,
                            threats = expertThreats(save.value) + save.value.expertBeatenWith,
                            beatenCards = save.value.expertBeatenWith.mapNotNull { catalog[it] },
                            aiBudgetMs = Ai.expertBudget(save.value.expertStreak),
                            pendingPack = pendingCards,
                            onPackPending = { cards ->

                                save.value = save.value.copy(
                                    pendingPack = save.value.pendingPack + cards.map { it.id })
                                persist(this@MainActivity, save.value)
                            },
                            onClaimPendingPack = {
                                save.value = save.value.addCards(pendingCards).copy(pendingPack = emptyList())
                                persist(this@MainActivity, save.value)
                            },
                            onRecord = { won, drawn ->
                                getSharedPreferences("triad", MODE_PRIVATE).edit().remove("spActive").apply()
                                save.value = save.value.withRecord(won = won, drawn = drawn)
                                persist(this@MainActivity, save.value)
                                if (won && s.difficulty == "hard") {
                                    val prefs = getSharedPreferences("triad", MODE_PRIVATE)
                                    prefs.edit().putInt("hardWins", prefs.getInt("hardWins", 0) + 1).apply()
                                }
                            },
                            onMemory = { handIds, won ->
                                save.value = save.value.withGameMemory(handIds, won, s.difficulty == "expert")
                                persist(this@MainActivity, save.value)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                    }
                    Screen.Host ->
                        HostLobbyScreen(
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            cardCount = save.value.collection.values.sum(),
                            onMatchStarted = { session, state, color ->
                                p2p = Triple(session, state, color)
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                    Screen.Join ->
                        JoinFlowScreen(
                            catalog = catalog,
                            myDeckIds = battleDeck(save.value, catalog).map { it.id },
                            cardCount = save.value.collection.values.sum(),
                            onTransfers = { transfers, myColor ->
                                save.value = save.value.applyTransfers(transfers, myColor)
                                persist(this@MainActivity, save.value)
                            },
                            onMatchStarted = { session, state, color ->
                                p2p = Triple(session, state, color)
                                screen = Screen.P2pMatch(session, state, color)
                            },
                            onExit = { screen = Screen.Menu },
                        )
                    is Screen.P2pMatch ->
                        P2pMatchScreen(
                            session = s.session,
                            catalog = catalog,
                            initialState = s.state,
                            initialColor = s.myColor,
                            onExit = { screen = Screen.Menu },
                        )
                }
            }
        }
    }
}
